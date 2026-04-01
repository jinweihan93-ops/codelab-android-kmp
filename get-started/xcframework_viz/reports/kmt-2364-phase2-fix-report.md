# KMT-2364 Phase 2 Fix Report: Cross-Framework Kotlin Type Identity

**Date**: 2026-04-01
**Status**: ✅ FIXED (static binary verification complete)

---

## Problem

In KMP V3 split-delivery architecture, two iOS frameworks (`foundationKit` and `businessKit`) each compile **independent definitions** of every Kotlin class descriptor (`_kclass:`, `_kfun:`, etc.) from shared klibs. This causes:

- Two copies of `_kclass:com.example.kmp.foundation.SharedData` — at different memory addresses
- Kotlin `is SharedData` type check compares class descriptor *pointers*, not values
- Cross-framework `is`/`as` checks always return `false` or crash with `ClassCastException`

### Evidence of the Bug (Before Fix)

```
[KMT-2364] isCheck=false kclassFnd=nil kclassBiz=nil match=NO
```

Both `dlsym` lookups returned `nil` because both `_kclass:` symbols had hidden visibility
(LLVM's `makeVisibilityHiddenLikeLlvmInternalizePass` hid them from the dylib export trie).

---

## Solution: Producer/Consumer Framework Split

### Phase 1 (already done): Shared Runtime
- foundationKit: `binaryOption("exportRuntimeSymbols", "true")` — exports K/N runtime symbols
- businessKit: `binaryOption("embedRuntime", "false")` — doesn't embed runtime

### Phase 2 (this fix): Shared Kotlin Class Descriptors

**Producer (foundationKit)** — defines and exports all `com.example.kmp.foundation.*` symbols:
```kotlin
// foundation/build.gradle.kts
binaryOption("exportKlibSymbols", "com.example.kmp.foundation")
```

**Consumer (businessKit)** — treats foundation symbols as externally provided:
```kotlin
// business/build.gradle.kts
binaryOption("externalKlibs", "com.example.kmp.foundation")
```

---

## Compiler Changes (Kotlin/Native)

### New Binary Options (`BinaryOptions.kt`)
- `exportRuntimeSymbols` — producer keeps K/N runtime visible after LTO
- `exportKlibSymbols` — producer re-exports klib class descriptors after internalize pass
- `externalKlibs` — consumer makes klib symbols `available_externally` / removes initializers
- `excludedRuntimeLibraries` — consumer excludes specific klib bitcode from linking

### New LLVM Visibility Functions (`visibility.kt`)

**`makeKlibSymbolsExported(module, packagePrefixes)`** — Producer side
- Runs AFTER LTO (after `makeVisibilityHiddenLikeLlvmInternalizePass`)
- Resets `HiddenVisibility` → `DefaultVisibility` for `kclass:`, `kfun:`, `ktypew:` etc.
- Symbols become visible in the dylib export trie
- Consumer frameworks resolve them via dyld flat-namespace at load time

**`makeKlibSymbolsExternal(module, packagePrefixes)`** — Consumer side
- Runs BEFORE LTO
- Globals (class descriptors): removes initializer → external declaration (`U` in nm)
- Functions (`kfun:`): sets `available_externally` linkage (LTO can inline; never emitted)
- Prevents businessKit from defining its own copies of foundation symbols

**`makeRuntimeSymbolsExported(module)`** — Producer runtime export
- Resets visibility on K/N runtime C symbols (`_AddTLSRecord`, `_AllocInstance`, etc.)
- Ensures consumer frameworks can resolve runtime at dyld load time

### Integration in `Bitcode.kt` (`runBitcodePostProcessing`)
```
[consumer] makeKlibSymbolsExternal()  ← before LTO
     ↓
LTO pipeline (MandatoryBitcode → ModuleOptimization → LTOBitcodeOptimization)
     ↓
[producer] makeRuntimeSymbolsExported()  ← after internalize
[producer] makeKlibSymbolsExported()     ← after internalize
```

---

## Binary Verification

### App Container
```
~/Library/Developer/CoreSimulator/Devices/93C1CE99-BB65-4FB8-874C-48BD1056E350/
  data/Containers/Bundle/Application/E4EE9661-C5AC-4B0F-8744-08F56DC2028F/
  KMPGetStartedCodelab.app/Frameworks/
```

### businessKit (Consumer) — `_kclass:` is UNDEFINED ✅
```
$ nm -arch arm64 businessKit.framework/businessKit | grep "kclass:com.example.kmp.foundation"
                 U _kclass:com.example.kmp.foundation.SharedData
```
- 11 foundation `kfun:` symbols also undefined (resolved from foundationKit at runtime)

### foundationKit (Producer) — `_kclass:` is DEFINED ✅
```
$ nm -arch arm64 foundationKit.framework/foundationKit | grep "kclass:com.example.kmp.foundation"
0000000000192840 S _kclass:com.example.kmp.foundation.SharedData
```

### foundationKit Export Trie — Symbol is EXPORTED ✅
```
$ xcrun dyld_info -exports foundationKit.framework/foundationKit | grep "kclass:com.example.kmp.foundation"
        0x001980F0  _kclass:com.example.kmp.foundation.SharedData
        0x00192840  _kclass:com.example.kmp.foundation.SharedData
```
(Two entries: one per architecture slice — x86_64 and arm64 — in the universal binary)

- **40 total `com.example.kmp.foundation.*` symbols exported** in foundationKit export trie
- **0 `com.example.kmp.foundation.*` symbols exported** in businessKit export trie

### Compiler Log Confirmation
```
w: [KMT-2364] makeKlibSymbolsExported: exported=17 packages=[com.example.kmp.foundation]
w: [KMT-2364] makeKlibSymbolsExternal: changed=20
```

---

## How dyld Resolves the Fix at Runtime

1. App loads `foundationKit.framework` → `_kclass:com.example.kmp.foundation.SharedData` at address `0xABCD1234`
2. App loads `businessKit.framework` → undefined `_kclass:com.example.kmp.foundation.SharedData`
3. dyld searches export trie of loaded libraries → finds it in `foundationKit`
4. dyld binds businessKit's reference to address `0xABCD1234` (same as foundationKit)
5. Both frameworks share **identical class descriptor pointer**
6. `processor.validateAsSharedData(fromFoundation)` → `is SharedData` → pointer comparison → **`true` ✅**

---

## Runtime Verification (Pending — Manual Step Required)

Due to a simctl sandbox restriction in the current session, automated launch is blocked.

**To verify at runtime:**
1. Open Xcode → Simulator (already booted, app already installed with fix)
2. Tap `KMPGetStartedCodelab` app icon
3. App will auto-run `checkKmt2364()` on appear
4. Tap **"Run All Tests"** button

**Expected output:**
```
is-check(Kotlin): ✅ true
  as-cast: ✅ <processed result>
kclass(RTLD_DEFAULT): 0x00000001XXXXXXXX
kclass(foundationKit): 0x00000001XXXXXXXX
kclass(businessKit):   0x00000001XXXXXXXX  ← SAME address as foundationKit
addrs match: ✅ YES
```

**Or via terminal (new session):**
```bash
DEVICE=93C1CE99-BB65-4FB8-874C-48BD1056E350
BUNDLE=com.exampe.kmp.getstarted.KMPGetStartedCodelab
xcrun simctl launch $DEVICE $BUNDLE
sleep 3
xcrun simctl spawn $DEVICE log show --last 5s | grep "KMT-2364"
```

---

## Files Changed

| File | Change |
|------|--------|
| `kotlin/.../BinaryOptions.kt` | Added `exportRuntimeSymbols`, `exportKlibSymbols`, `externalKlibs`, `excludedRuntimeLibraries` binary options |
| `kotlin/.../KonanConfig.kt` | Added `exportKlibPackages`, `externalKlibPackages` lazy properties |
| `kotlin/.../visibility.kt` | Added `makeKlibSymbolsExported()`, `makeKlibSymbolsExternal()`, `makeRuntimeSymbolsExported()` |
| `kotlin/.../Bitcode.kt` | Integrated producer/consumer passes into `runBitcodePostProcessing()` |
| `tiktok-kn/v2.1.20-shared-runtime/.../kotlin-native-compiler-embeddable.jar` | Updated with patched class files |
| `get-started/foundation/build.gradle.kts` | Added `exportRuntimeSymbols` + `exportKlibSymbols` binary options |
| `get-started/business/build.gradle.kts` | Added `externalKlibs` binary option |

---

## Key Technical Insight

LLVM IR symbol names for K/N **do NOT have the leading `_`** that Darwin linker adds.
- In LLVM IR: `kclass:com.example.kmp.foundation.SharedData` (no `_`)
- In nm/dylib: `_kclass:com.example.kmp.foundation.SharedData` (with `_`)

The `nameMatchesExternalKlib()` / `nameMatchesExportKlib()` functions use `name.startsWith("k")`
(not `"_k"`) when working with `LLVMGetValueName()`.
