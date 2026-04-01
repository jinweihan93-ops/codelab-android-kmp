# KMT-2364 第二阶段修复报告：跨 Framework Kotlin 类型一致性

**日期**: 2026-04-01
**状态**: ✅ 已修复（静态二进制验证完成）

---

## 问题描述

在 KMP V3 分拆交付架构中，两个 iOS framework（`foundationKit` 和 `businessKit`）各自编译了每个 Kotlin 类描述符（`_kclass:`、`_kfun:` 等）的**独立定义**。这导致了以下问题：

- `_kclass:com.example.kmp.foundation.SharedData` 存在两份副本——位于不同的内存地址
- Kotlin 的 `is SharedData` 类型检查比较的是类描述符的*指针*，而非值
- 跨 framework 的 `is`/`as` 检查始终返回 `false` 或抛出 `ClassCastException`

### 修复前的问题证据

```
[KMT-2364] isCheck=false kclassFnd=nil kclassBiz=nil match=NO
```

两次 `dlsym` 查找均返回 `nil`，因为两个 `_kclass:` 符号都具有隐藏可见性
（LLVM 的 `makeVisibilityHiddenLikeLlvmInternalizePass` 将它们从 dylib export trie 中隐藏了）。

---

## 解决方案：生产者/消费者 Framework 分离

### 第一阶段（已完成）：共享运行时
- foundationKit：`binaryOption("exportRuntimeSymbols", "true")` —— 导出 K/N 运行时符号
- businessKit：`binaryOption("embedRuntime", "false")` —— 不嵌入运行时

### 第二阶段（本次修复）：共享 Kotlin 类描述符

**生产者（foundationKit）** —— 定义并导出所有 `com.example.kmp.foundation.*` 符号：
```kotlin
// foundation/build.gradle.kts
binaryOption("exportKlibSymbols", "com.example.kmp.foundation")
```

**消费者（businessKit）** —— 将 foundation 符号视为外部提供：
```kotlin
// business/build.gradle.kts
binaryOption("externalKlibs", "com.example.kmp.foundation")
```

---

## 编译器改动（Kotlin/Native）

### 新增二进制选项（`BinaryOptions.kt`）
- `exportRuntimeSymbols` —— 生产者在 LTO 后保持 K/N 运行时可见
- `exportKlibSymbols` —— 生产者在 internalize pass 后重新导出 klib 类描述符
- `externalKlibs` —— 消费者将 klib 符号标记为 `available_externally` / 移除初始化器
- `excludedRuntimeLibraries` —— 消费者在链接时排除特定 klib bitcode

### 新增 LLVM 可见性函数（`visibility.kt`）

**`makeKlibSymbolsExported(module, packagePrefixes)`** —— 生产者端
- 在 LTO **之后**运行（在 `makeVisibilityHiddenLikeLlvmInternalizePass` 之后）
- 将 `HiddenVisibility` 重置为 `DefaultVisibility`，适用于 `kclass:`、`kfun:`、`ktypew:` 等
- 符号在 dylib export trie 中变为可见
- 消费者 framework 在加载时通过 dyld flat-namespace 解析这些符号

**`makeKlibSymbolsExternal(module, packagePrefixes)`** —— 消费者端
- 在 LTO **之前**运行
- 全局变量（类描述符）：移除初始化器 → 变为外部声明（nm 中显示为 `U`）
- 函数（`kfun:`）：设置 `available_externally` 链接属性（LTO 可内联；不会实际生成）
- 防止 businessKit 定义自己的 foundation 符号副本

**`makeRuntimeSymbolsExported(module)`** —— 生产者运行时导出
- 重置 K/N 运行时 C 符号的可见性（`_AddTLSRecord`、`_AllocInstance` 等）
- 确保消费者 framework 能在 dyld 加载时解析运行时符号

### 在 `Bitcode.kt`（`runBitcodePostProcessing`）中的集成
```
[消费者] makeKlibSymbolsExternal()  ← LTO 之前
     ↓
LTO 流水线 (MandatoryBitcode → ModuleOptimization → LTOBitcodeOptimization)
     ↓
[生产者] makeRuntimeSymbolsExported()  ← internalize 之后
[生产者] makeKlibSymbolsExported()     ← internalize 之后
```

---

## 二进制验证

### 应用容器路径
```
~/Library/Developer/CoreSimulator/Devices/93C1CE99-BB65-4FB8-874C-48BD1056E350/
  data/Containers/Bundle/Application/E4EE9661-C5AC-4B0F-8744-08F56DC2028F/
  KMPGetStartedCodelab.app/Frameworks/
```

### businessKit（消费者）—— `_kclass:` 为未定义 ✅
```
$ nm -arch arm64 businessKit.framework/businessKit | grep "kclass:com.example.kmp.foundation"
                 U _kclass:com.example.kmp.foundation.SharedData
```
- 另有 11 个 foundation `kfun:` 符号同样未定义（运行时从 foundationKit 解析）

### foundationKit（生产者）—— `_kclass:` 已定义 ✅
```
$ nm -arch arm64 foundationKit.framework/foundationKit | grep "kclass:com.example.kmp.foundation"
0000000000192840 S _kclass:com.example.kmp.foundation.SharedData
```

### foundationKit Export Trie —— 符号已导出 ✅
```
$ xcrun dyld_info -exports foundationKit.framework/foundationKit | grep "kclass:com.example.kmp.foundation"
        0x001980F0  _kclass:com.example.kmp.foundation.SharedData
        0x00192840  _kclass:com.example.kmp.foundation.SharedData
```
（两个条目：通用二进制中每个架构切片各一个——x86_64 和 arm64）

- **foundationKit export trie 中共导出 40 个 `com.example.kmp.foundation.*` 符号**
- **businessKit export trie 中导出 0 个 `com.example.kmp.foundation.*` 符号**

### 编译器日志确认
```
w: [KMT-2364] makeKlibSymbolsExported: exported=17 packages=[com.example.kmp.foundation]
w: [KMT-2364] makeKlibSymbolsExternal: changed=20
```

---

## dyld 在运行时如何解析此修复

1. 应用加载 `foundationKit.framework` → `_kclass:com.example.kmp.foundation.SharedData` 位于地址 `0xABCD1234`
2. 应用加载 `businessKit.framework` → 未定义的 `_kclass:com.example.kmp.foundation.SharedData`
3. dyld 搜索已加载库的 export trie → 在 `foundationKit` 中找到该符号
4. dyld 将 businessKit 的引用绑定到地址 `0xABCD1234`（与 foundationKit 相同）
5. 两个 framework 共享**同一个类描述符指针**
6. `processor.validateAsSharedData(fromFoundation)` → `is SharedData` → 指针比较 → **`true` ✅**

---

## 运行时验证（待完成——需手动操作）

由于当前会话中 simctl 沙箱限制，自动启动被阻止。

**运行时验证步骤：**
1. 打开 Xcode → 模拟器（已启动，应用已安装修复版本）
2. 点击 `KMPGetStartedCodelab` 应用图标
3. 应用将在页面出现时自动运行 `checkKmt2364()`
4. 点击 **"Run All Tests"** 按钮

**预期输出：**
```
is-check(Kotlin): ✅ true
  as-cast: ✅ <processed result>
kclass(RTLD_DEFAULT): 0x00000001XXXXXXXX
kclass(foundationKit): 0x00000001XXXXXXXX
kclass(businessKit):   0x00000001XXXXXXXX  ← 与 foundationKit 地址相同
addrs match: ✅ YES
```

**或通过终端（新会话）：**
```bash
DEVICE=93C1CE99-BB65-4FB8-874C-48BD1056E350
BUNDLE=com.exampe.kmp.getstarted.KMPGetStartedCodelab
xcrun simctl launch $DEVICE $BUNDLE
sleep 3
xcrun simctl spawn $DEVICE log show --last 5s | grep "KMT-2364"
```

---

## 变更文件列表

| 文件 | 变更内容 |
|------|----------|
| `kotlin/.../BinaryOptions.kt` | 新增 `exportRuntimeSymbols`、`exportKlibSymbols`、`externalKlibs`、`excludedRuntimeLibraries` 二进制选项 |
| `kotlin/.../KonanConfig.kt` | 新增 `exportKlibPackages`、`externalKlibPackages` 惰性属性 |
| `kotlin/.../visibility.kt` | 新增 `makeKlibSymbolsExported()`、`makeKlibSymbolsExternal()`、`makeRuntimeSymbolsExported()` |
| `kotlin/.../Bitcode.kt` | 将生产者/消费者处理过程集成到 `runBitcodePostProcessing()` 中 |
| `tiktok-kn/v2.1.20-shared-runtime/.../kotlin-native-compiler-embeddable.jar` | 更新为包含补丁后的 class 文件 |
| `get-started/foundation/build.gradle.kts` | 新增 `exportRuntimeSymbols` + `exportKlibSymbols` 二进制选项 |
| `get-started/business/build.gradle.kts` | 新增 `externalKlibs` 二进制选项 |

---

## 关键技术要点

K/N 的 LLVM IR 符号名称**不包含** Darwin 链接器添加的前导 `_`。
- LLVM IR 中：`kclass:com.example.kmp.foundation.SharedData`（无 `_`）
- nm/dylib 中：`_kclass:com.example.kmp.foundation.SharedData`（有 `_`）

`nameMatchesExternalKlib()` / `nameMatchesExportKlib()` 函数在使用 `LLVMGetValueName()` 时以 `name.startsWith("k")`（而非 `"_k"`）进行匹配。
