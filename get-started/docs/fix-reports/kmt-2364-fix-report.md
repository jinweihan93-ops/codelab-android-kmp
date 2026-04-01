# KMT-2364 修复报告：KMP V3 分体交付双运行时崩溃

> 日期: 2026-03-31
> 分支: claude/pensive-clarke
> 工程: get-started/ (foundationKit + businessKit)
> 状态: **✅ 阶段一完成** — BUILD SUCCESSFUL / BUILD SUCCEEDED / 运行时验证通过

---

## 执行摘要（中文）

KMT-2364 涉及 KMP V3 分体交付架构下的两阶段崩溃问题。

**第一阶段崩溃**：foundationKit 和 businessKit 各自内嵌完整的 K/N 运行时，两套运行时均向 iOS ObjC 运行时注册 `+[KotlinBase initialize]`，但它们共用同一份**全局适配器列表（global adapter list）**。结果是后注册的框架覆盖了先注册的适配器，导致 businessKit 调用 Kotlin 方法时触发 `unrecognized selector` 崩溃。

**第二阶段崩溃（KMT-2364 部分修复后引入）**：将 businessKit 切换为 `embedRuntime=false`（不内嵌运行时，从 foundationKit 共享）后，出现新崩溃：`dyld: Symbol not found: ___ZTI18ExceptionObjHolder`。根本原因是该 C++ RTTI 符号在 foundationKit 的 runtime.bc 中以 `linkonce_odr hidden` 链接性编译，经 LTO 后变为 `weak_any + hidden`（本地符号，`s` 类型），对 businessKit 不可见。

**完整修复方案**包含六个相互协作的组件：运行时 BC 补丁、编译器 visibility 后处理 pass、编译器 Bitcode.kt 调用点、foundationKit 构建配置、缓存禁用以及 businessKit 链接选项。三项构建均已验证通过（foundationKit XCFramework、businessKit XCFramework、iOS App）。

---

## 1. 问题背景

### 1.1 架构概述

KMP V3 分体交付架构的目标是将 Kotlin Multiplatform 能力拆分为两个独立交付的 XCFramework：

```
iOS App
├── foundationKit.xcframework   (embedRuntime=true,  提供 K/N 运行时)
└── businessKit.xcframework     (embedRuntime=false, 消费运行时，不重复内嵌)
```

两个框架独立编译，独立交付（可分别通过 App Store 动态下发），在运行时由 iOS dyld 加载并共享 foundationKit 中的 K/N 运行时。

### 1.2 第一阶段崩溃：`unrecognized selector`

**触发条件**：foundationKit 和 businessKit 均设置 `embedRuntime=true`，App 加载时同时存在两套完整 K/N 运行时。

**崩溃栈（示意）**：
```
[BusinessKitClass someKotlinMethod]
  -> unrecognized selector sent to instance
```

**根本原因**：

K/N 的 ObjC 导出机制通过 `+[KotlinBase initialize]` 向 ObjC 运行时注册 Kotlin 类适配器。该注册过程使用**全局静态适配器列表**（`KotlinBaseAdapters`），在进程空间中只有一份。

当两个框架各自内嵌运行时时：
1. foundationKit 加载 → `foundationKit!+[KotlinBase initialize]` 向全局列表注册适配器
2. businessKit 加载 → `businessKit!+[KotlinBase initialize]` 再次注册，**覆盖**全局列表中 foundationKit 注册的条目
3. businessKit 自身的 Kotlin 类期望的适配器来自 businessKit 的运行时实例，但全局列表已被混乱写入
4. 方法分发失败 → `unrecognized selector`

### 1.3 第二阶段崩溃：`Symbol not found: ___ZTI18ExceptionObjHolder`

**触发条件**：将 businessKit 切换为 `embedRuntime=false` 后，businessKit 期望在运行时从 foundationKit 动态解析 runtime 符号。

**崩溃信息**：
```
dyld: Symbol not found: ___ZTI18ExceptionObjHolder
  Referenced from: businessKit.framework/businessKit
  Expected in: foundationKit.framework/foundationKit
```

**根本原因**：

`__ZTI18ExceptionObjHolder` 是 `ExceptionObjHolder` 类的 C++ RTTI（运行时类型信息）typeinfo 对象。在 K/N 的 `runtime.bc` 中，该符号以 `linkonce_odr hidden` 链接性声明：

```llvm
; runtime.bc 中
@_ZTI18ExceptionObjHolder = linkonce_odr hidden constant { ... }
```

经过 LTO（链接时优化）后，该符号的可见性变为 `weak_any + hidden`。在 Mach-O 符号表中表现为 `s`（小写，即 local symbol），对其他 dylib 不可见。

businessKit（`embedRuntime=false`）在链接时和运行时均无法解析该符号，导致 dyld 加载失败。

---

## 2. 修复方案详解

修复方案由六个组件构成，每个组件解决问题链条中的一个具体环节。

### 2.1 Runtime BC Patch

**文件**: `/tmp/patch_kmt2364_objc_runtime.py`

**问题**：`+[KotlinBase initialize]` 使用全局适配器列表，两个框架的运行时相互干扰。

**方案**：在 `runtime.bc` 中新增函数 `Kotlin_ObjCExport_initializeClassWithAdapters`，将适配器列表从全局改为**框架本地**（framework-local），并修补 `objc.bc` 中的 `+[KotlinBase initialize]` 调用该新函数。

**LLVM IR 层面的变更**：

```llvm
; 新增函数签名（添加到 runtime.bc）
declare void @Kotlin_ObjCExport_initializeClassWithAdapters(
    ptr %cls,
    ptr %adapters,
    i32 %count
)

; 修补后的 +[KotlinBase initialize]（objc.bc）
; 原来：调用全局 initializeClass
; 修补后：调用 initializeClassWithAdapters，传入框架本地的 adapters 指针
call void @Kotlin_ObjCExport_initializeClassWithAdapters(
    ptr %cls,
    ptr @framework_local_adapters,  ; 框架本地，不再是全局
    i32 %adapter_count
)
```

**覆盖目标**：所有 iOS targets
- `ios_simulator_arm64`
- `ios_arm64`
- `ios_x64`（模拟器 x86_64）

### 2.2 K/N 编译器：`makeRuntimeSymbolsExported`

**文件**: `kotlin-native/backend.native/compiler/.../llvm/visibility.kt`

**问题**：runtime.bc 中的 C++ RTTI 符号（`_ZTI18ExceptionObjHolder` 等）经 LTO 后可见性变为 `hidden`，businessKit 无法在运行时解析。

**方案**：实现一个 post-LTO pass，将指定的 runtime 符号可见性从 `hidden` 重置为 `DEFAULT + EXTERNAL`。

**实现逻辑**：

```kotlin
// visibility.kt (示意)
fun makeRuntimeSymbolsExported(module: LLVMModuleRef) {
    // 目标符号列表：C++ RTTI typeinfo 和 typestring
    val runtimeExportSymbols = listOf(
        "_ZTI18ExceptionObjHolder",   // typeinfo object
        "_ZTS18ExceptionObjHolder"    // typestring
    )

    for (symbolName in runtimeExportSymbols) {
        val global = LLVMGetNamedGlobal(module, symbolName)
        if (global != null) {
            // 将 hidden 可见性改为 default（对外可见）
            LLVMSetVisibility(global, LLVMDefaultVisibility)
            // 将 linkage 从 weak_any 改为 external
            LLVMSetLinkage(global, LLVMExternalLinkage)
        }
    }
}
```

**部署方式**：更新编译器 jar 至 `/Users/bytedance/tiktok-kn/v2.1.20-shared-runtime/konan/lib/`

### 2.3 K/N 编译器：`Bitcode.kt`

**文件**: `kotlin-native/backend.native/compiler/.../driver/phases/Bitcode.kt`

**方案**：在 `runBitcodePostProcessing` 函数中，当构建选项 `exportRuntimeSymbols=true` 时，调用 2.2 中实现的 `makeRuntimeSymbolsExported` pass。

```kotlin
// Bitcode.kt（示意）
fun runBitcodePostProcessing(context: Context, module: LLVMModuleRef) {
    // ... 其他 post-processing steps ...

    if (context.config.configuration.get(KonanConfigKeys.EXPORT_RUNTIME_SYMBOLS) == true) {
        makeRuntimeSymbolsExported(module)
    }
}
```

该修改确保 `makeRuntimeSymbolsExported` 只在需要时（foundationKit 构建）执行，不影响普通 K/N 构建。

### 2.4 foundationKit 构建配置

**文件**: `foundation/build.gradle.kts`

**方案**：对所有 iOS target 启用 `exportRuntimeSymbols` 选项，触发 2.2/2.3 中的 visibility pass。

```kotlin
// foundation/build.gradle.kts
kotlin {
    listOf(
        iosArm64(),
        iosX64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework("foundationKit") {
            // 触发 makeRuntimeSymbolsExported pass
            binaryOption("exportRuntimeSymbols", "true")
            // foundationKit 内嵌完整运行时
            embedBitcode(Framework.BitcodeEmbeddingMode.DISABLE)
        }
    }
}
```

### 2.5 缓存禁用

**文件**: `local.properties`

**问题**：K/N 的全局静态缓存（`libstdlib-cache.a`）是在 runtime.bc 补丁**之前**预编译的，因此缓存中不包含 `Kotlin_ObjCExport_initializeClassWithAdapters` 函数。如果使用缓存，补丁中的新函数在链接阶段不存在，导致链接失败或行为不符合预期。

**方案**：禁用 K/N 编译缓存，强制进行 IR 级别的全量链接（IR-level linking），确保补丁后的 `runtime.bc` 被完整纳入编译。

```properties
# local.properties
kotlin.native.cacheKind.iosSimulatorArm64=none
kotlin.native.cacheKind.iosArm64=none
kotlin.native.cacheKind.iosX64=none
```

**机制说明**：

```
缓存启用时（默认）:
  Kotlin IR → [LTO + runtime.bc（旧版，无 patch）] → libstdlib-cache.a → 链接
                                                        ↑
                                              缓存中无 initializeClassWithAdapters

缓存禁用后（本修复）:
  Kotlin IR → [LTO + runtime.bc（已 patch）] → 直接链接
                      ↑
              包含 initializeClassWithAdapters + 可见性已修复的 RTTI 符号
```

**代价**：每次构建需要重新进行完整 LTO，编译时间显著增加。这是在分体交付正式方案稳定前的临时措施。

### 2.6 businessKit 链接选项

**文件**: `business/build.gradle.kts`

**问题**：`cacheKind=none` + `embedRuntime=false` 组合下，businessKit 在链接阶段 runtime 符号处于 undefined 状态（这些符号在运行时由 dyld 从 foundationKit 解析）。默认情况下，Apple 链接器（ld）对 undefined 符号报错，导致 businessKit 无法链接。

**方案**：添加 `-undefined dynamic_lookup` 链接选项，告知链接器允许 undefined 符号在运行时动态解析。

```kotlin
// business/build.gradle.kts
kotlin {
    listOf(
        iosArm64(),
        iosX64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework("businessKit") {
            // 不内嵌运行时，从 foundationKit 共享
            // （需确保 foundationKit 先于 businessKit 加载）
            linkerOpts(
                "-undefined", "dynamic_lookup"
            )
        }
    }
}
```

**安全性说明**：`-undefined dynamic_lookup` 会使所有 undefined 符号推迟到运行时解析，如果 foundationKit 未能正确加载，将在运行时崩溃而非链接时报错。在生产环境中，需确保 App 的 framework 加载顺序正确（foundationKit 优先）。

---

## 3. 修复方案整体流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        KMT-2364 修复全链路                                   │
└─────────────────────────────────────────────────────────────────────────────┘

构建阶段（foundationKit）:
  runtime.bc [patched]                    ← 2.1: 新增 initializeClassWithAdapters
       │
       ▼
  K/N LTO (cacheKind=none)               ← 2.5: 禁用缓存，强制使用 patched runtime.bc
       │
       ▼
  LLVM IR post-processing
    makeRuntimeSymbolsExported()          ← 2.2/2.3: 将 RTTI 符号可见性改为 DEFAULT
    [triggered by exportRuntimeSymbols]   ← 2.4: foundationKit build.gradle.kts
       │
       ▼
  foundationKit.xcframework
    符号可见性:
      T _Kotlin_ObjCExport_initializeClassWithAdapters  (全局文本段)
      S __ZTI18ExceptionObjHolder                        (全局，已从 s 提升为 S)
      S __ZTS18ExceptionObjHolder                        (全局，已从 s 提升为 S)

构建阶段（businessKit）:
  embedRuntime=false
  linkerOpts(-undefined, dynamic_lookup)  ← 2.6: 允许运行时动态解析
       │
       ▼
  businessKit.xcframework
    runtime 符号: undefined（运行时由 foundationKit 提供）

运行时（iOS App 加载）:
  dyld 加载 foundationKit → 注册 runtime 符号到进程命名空间
  dyld 加载 businessKit  → 解析 undefined runtime 符号 → 绑定到 foundationKit
                                                           ↑
                                            __ZTI18ExceptionObjHolder 现在可见 ✓

ObjC 类注册:
  foundationKit: +[KotlinBase initialize]
    → initializeClassWithAdapters(cls, foundationKit_adapters, count)  ← 框架本地 ✓
  businessKit:  +[KotlinBase initialize]
    → initializeClassWithAdapters(cls, businessKit_adapters,  count)  ← 框架本地 ✓
  两套适配器互不干扰 ✓
```

---

## 4. 验证结果

### 4.1 符号可见性验证（foundationKit，ios-arm64_x86_64-simulator）

使用 `nm` 检查 foundationKit.xcframework 中的目标符号：

```
# 修复前（第二阶段崩溃原因）
00000000001b19c0 s __ZTI18ExceptionObjHolder    ← 小写 s = local，对外不可见
000000000017861a s __ZTS18ExceptionObjHolder    ← 小写 s = local，对外不可见

# 修复后
00000000001138b8 T _Kotlin_ObjCExport_initializeClassWithAdapters  [T = 全局文本段，可见]
00000000001b19c0 S __ZTI18ExceptionObjHolder                        [S = 全局 BSS/const，可见]
000000000017861a S __ZTS18ExceptionObjHolder                        [S = 全局 BSS/const，可见]
```

关键变化：`s`（local） → `S`（global），符号现在对 businessKit 可见。

### 4.2 构建与运行时验证结果

| 验证项 | 结果 |
|--------|------|
| foundationKit XCFramework 构建 | ✅ BUILD SUCCESSFUL |
| businessKit XCFramework 构建 | ✅ BUILD SUCCESSFUL |
| iOS App (xcodebuild) | ✅ BUILD SUCCEEDED |
| dyld 加载（`__ZTI18ExceptionObjHolder` 解析） | ✅ 无崩溃，App 正常启动 |
| GC 线程数 | ✅ **2 个**（修复前 4 个）|
| 运行时实例数 | ✅ **1 个**（全部 GC 线程来自 foundationKit）|

### 4.3 App 内置测试面板（V3 Dual Runtime Evidence）

iOS App 的 `ContentView.swift` 内置了一个"Run All Tests"测试面板，用于证明双运行时问题的存在。修复后各项指标的状态变化如下：

| 测试项 | 修复前 | 修复后（阶段一） | 说明 |
|--------|--------|-----------------|------|
| Two K/N runtimes loaded | YES | **NO** | GC 线程数从 4 → 2 已验证 |
| Cross-framework `is`-check fails | YES | **YES（仍然）** | class descriptor 不同，阶段二解决 |
| Types differ at Swift level | YES | **YES（仍然）** | `FoundationKitSharedData` ≠ `BusinessKitSharedData` |
| Object passing safe | NO | **NO（仍然）** | `as SharedData` 仍会 ClassCastException，阶段二解决 |

> **注意**：`ContentView.swift` 第 113 行 `results.append("Two K/N runtimes loaded: YES")` 是硬编码字符串，不反映修复后的真实状态，待更新为动态检测。

### 4.4 符号分析（xcframework-analyzer.py）

修复后两个产物的符号分布：

| 分类 | foundationKit defined | businessKit defined | businessKit undef |
|------|-----------------------|---------------------|-------------------|
| K/N Runtime | 750 | **22** | **34** |
| Kotlin Stdlib | 4004 | 4010 | 0 |
| C++ RTTI | 131 | 0 | **1** ← ZTI18ExceptionObjHolder |

- businessKit K/N Runtime defined 从 750 降至 22，34 个 runtime undefined 符号在运行时从 foundationKit 解析 ✓
- `__ZTI18ExceptionObjHolder`（C++ RTTI, undef in businessKit）已可从 foundationKit 解析 ✓
- **Kotlin Stdlib 仍重复**（4001 个符号在两个框架中均存在）— 属于阶段二待解决问题

### 4.3 nm 符号类型说明

| 类型 | 含义 |
|------|------|
| `T` / `t` | 文本段（代码）。大写 = 全局（外部可见），小写 = 本地 |
| `S` / `s` | 非标准段（通常为 BSS 或 const 数据）。大写 = 全局，小写 = 本地 |
| `U` | Undefined（未定义，需运行时解析） |
| `W` / `w` | Weak symbol。大写 = 外部 weak，小写 = local weak |

---

## 5. 根本原因分析

### 5.1 符号可见性问题的根源

`ExceptionObjHolder` 是 K/N runtime 内部的 C++ 异常处理辅助类。在 LLVM IR 中：

```llvm
; runtime.bc 原始声明
@_ZTI18ExceptionObjHolder = linkonce_odr hidden constant
    { ptr, ptr, ptr }
    { ptr @_ZTVN10__cxxabiv117__class_type_infoE+16,
      ptr @_ZTS18ExceptionObjHolder,
      ptr null },
    align 8
```

- `linkonce_odr`：同一翻译单元中允许多份定义，链接时取一份（用于 C++ inline/template）
- `hidden`：仅在当前 DSO（动态共享对象）内可见，不导出到动态符号表

LTO 处理后，`linkonce_odr hidden` 变为 `weak_any hidden`（Mach-O 层面为小写 `s`），进一步强化了其不可见性。

K/N 的设计初衷是每个 framework 自带完整运行时（single-framework model），因此 RTTI 符号对外不可见完全合理。但在分体交付模型（shared-runtime model）下，这一设计假设被打破。

### 5.2 缓存与补丁的时序问题

```
时间线:
  t0: K/N 2.1.20 发布，预编译 libstdlib-cache.a（不含 initializeClassWithAdapters）
  t1: 应用 runtime.bc 补丁（添加 initializeClassWithAdapters）
  t2: 构建 foundationKit
      ├── 若使用缓存（cacheKind=auto）: 从 libstdlib-cache.a 链接 → 缺少新函数 → 构建失败/行为错误
      └── 若禁用缓存（cacheKind=none）: 从 patched runtime.bc 重新 LTO → 新函数存在 ✓
```

这是修复方案中需要禁用 K/N 缓存的直接原因。

### 5.3 两阶段崩溃的关联性

两阶段崩溃并非独立问题，而是同一架构决策的两个面：

```
架构决策：businessKit 共享 foundationKit 的运行时
    │
    ├── 如果两者都 embedRuntime=true：
    │     → 两套全局适配器列表 → unrecognized selector（第一阶段）
    │
    └── 如果 businessKit embedRuntime=false：
          → businessKit 引用 foundationKit 的 runtime 符号
          → 但 RTTI 符号是 local（hidden）→ dyld 找不到 → Symbol not found（第二阶段）
          → 本修复方案解决此问题
```

---

## 6. 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `/tmp/patch_kmt2364_objc_runtime.py` | 新增 | Runtime BC 补丁脚本 |
| `kotlin-native/.../llvm/visibility.kt` | 修改 | 新增 `makeRuntimeSymbolsExported` pass |
| `kotlin-native/.../driver/phases/Bitcode.kt` | 修改 | 在 `runBitcodePostProcessing` 中调用 visibility pass |
| `foundation/build.gradle.kts` | 修改 | 为所有 iOS targets 添加 `binaryOption("exportRuntimeSymbols", "true")` |
| `local.properties` | 修改 | 添加 `kotlin.native.cacheKind.*=none` |
| `business/build.gradle.kts` | 修改 | 为所有 iOS targets 添加 `linkerOpts("-undefined", "dynamic_lookup")` |

---

## 7. 已知限制与后续工作

### 7.1 当前限制

1. **构建时间增加**：禁用 K/N 缓存（`cacheKind=none`）导致每次构建均需完整 LTO，编译时间显著增加（具体数值因机器而异，预估增加 50-200%）。

2. **`-undefined dynamic_lookup` 的运行时风险**：该选项推迟了符号解析错误的发现时机。如果 foundationKit 未正确加载，App 将在运行时而非安装时崩溃。需要在 App 层面确保加载顺序。

3. **补丁的 K/N 版本绑定**：`runtime.bc` 补丁和 `visibility.kt` 修改基于 K/N 2.1.20 内部实现，升级 K/N 版本时需重新评估兼容性。

4. **仅覆盖已知 RTTI 符号**：`makeRuntimeSymbolsExported` 当前只显式处理 `_ZTI18ExceptionObjHolder` 和 `_ZTS18ExceptionObjHolder`。如果未来 K/N 版本引入新的需要跨框架可见的 RTTI 符号，需要更新该列表。

### 7.2 后续工作建议

1. **重建 stdlib 缓存**：基于 patched runtime.bc 重新预编译 `libstdlib-cache.a`，恢复缓存以降低构建时间。这样可以移除 `local.properties` 中的 `cacheKind=none` 配置。

2. **上游方案评估**：与 JetBrains K/N 团队沟通，评估将 `makeRuntimeSymbolsExported` 机制或类似功能纳入官方 K/N 构建选项的可行性。

3. **符号扫描自动化**：开发工具自动检测 businessKit 中所有 undefined 符号是否在 foundationKit 的导出符号表中存在，作为 CI 检查。

4. **加载顺序保障**：在 App 层面添加断言或早期检查，确保 foundationKit 先于 businessKit 加载，将 `-undefined dynamic_lookup` 的潜在运行时风险前置为可控的启动时检查。

---

## 阶段二：Foundation-Only 模块下沉（新需求）

### 背景

当前符号分析显示，Kotlin Stdlib（~4000 个符号）及未来下沉到 Foundation 的 KMP 共享模块在 foundationKit 和 businessKit 中**各保存一份完整拷贝**，造成：
- **包体积浪费**：每个 business 框架重复内嵌 stdlib（~1-2 MB/框架）
- **多实例风险**：stdlib 中存在全局状态（如 collections 的内部缓存），多份拷贝可能导致行为不一致
- **架构语义违背**：V3 分体交付的设计意图是 foundation 作为唯一的基础层

### 目标

```
foundationKit: 内嵌 K/N Runtime + Kotlin Stdlib + 所有下沉 KMP 模块
businessKit:   仅包含自身业务逻辑，stdlib/共享模块符号在运行时从 foundationKit 解析
```

### 技术方案（待实现）

#### 方案 A：扩展 `embedRuntime=false` 语义至 Stdlib（K/N 编译器改动）

K/N 目前的 `embedRuntime=false` 只排除了 runtime，stdlib 仍通过 `LinkBitcodeDependenciesPhase` 合并进每个框架。需要：

1. **新增编译器选项 `embedStdlib=false`**：在 `KonanConfig` 和 `BinaryOptions` 中新增，控制 stdlib.bc / stdlib klib 是否参与 businessKit 的 IR 合并
2. **`makeStdlibSymbolsExported`**：类似 `makeRuntimeSymbolsExported`，在 foundationKit 的 post-LTO pass 中将 stdlib 符号可见性从 hidden/internal 提升为 external
3. **businessKit 链接**：stdlib 符号在 businessKit 链接时为 undefined，通过现有的 `-undefined dynamic_lookup` 在运行时从 foundationKit 解析

**难点**：stdlib 经 LTO 后大量符号被 internalize（`-internalize` pass），需要在 foundationKit 构建时保留完整导出列表（export list），防止 dead-strip 裁剪 businessKit 所需的 stdlib 符号。

#### 方案 B：KMP 共享模块 Foundation-Only 依赖约束（Gradle 构建改动）

对于 Android KMP 模块（共享业务逻辑下沉到 foundation 的部分）：

1. **依赖图重构**：businessKit Gradle 模块不直接 `implementation/api` 这些共享模块，改为通过 foundationKit 的 `export()` 间接使用
2. **K/N `kexe` 引用机制**（待调研）：businessKit 在 K/N IR 层面将这些模块的符号声明为外部引用（类似 C 的 `extern`），不参与 IR 合并
3. **构建时验证**：CI 中运行 xcframework-analyzer.py，断言 businessKit 中共享模块的 defined 符号数为 0

#### 当前已具备的基础

- `-undefined dynamic_lookup`：businessKit 已配置，支持运行时符号解析 ✓
- `makeRuntimeSymbolsExported` 框架：visibility pass 机制已验证，可复用于 stdlib ✓
- `cacheKind=none`：IR 级别链接已启用，可精确控制哪些 klib 参与合并 ✓
- xcframework-analyzer.py：可量化验证符号重复消除效果 ✓

---

## 8. 参考资料

- [v3-dual-runtime-evidence-report.md](./v3-dual-runtime-evidence-report.md) — 双运行时问题实证报告
- [v3-split-delivery-paths-report.md](./v3-split-delivery-paths-report.md) — 分体交付路径探索报告
- [v3-duplicate-symbol-analysis-2026-03-30.md](./v3-duplicate-symbol-analysis-2026-03-30.md) — 重复符号分析报告
- [kn-xcframework-symbol-analysis-2026-03-30.md](./kn-xcframework-symbol-analysis-2026-03-30.md) — XCFramework 符号分析报告
- K/N 源码参考:
  - `kotlin-native/runtime/src/main/cpp/objc/ObjCExport.mm` — ObjC 导出与 `+[KotlinBase initialize]` 实现
  - `kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/` — LLVM IR 后处理
- Apple 文档: [Two-Level Namespace](https://developer.apple.com/library/archive/documentation/DeveloperTools/Conceptual/MachOTopics/1-Articles/executing_files.html)
- LLVM 文档: [Linkage Types](https://llvm.org/docs/LangRef.html#linkage-types), [Visibility Styles](https://llvm.org/docs/LangRef.html#visibility-styles)
