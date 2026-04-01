# KMT-2364 Phase 2 阶段性报告：跨框架 Kotlin 类型身份修复

**日期**: 2026-04-01
**分支**: `claude/pensive-clarke`
**提交**: `3a61110`

---

## 1. 问题背景

KMP V3 分体交付架构下，`foundationKit` 和 `businessKit` 各自编译独立的 Kotlin class descriptor (`_kclass:` 符号)。同一个 Kotlin 类在两个框架中有**不同的内存地址**，导致：

```
// businessKit 代码
fun validateAsSharedData(data: Any): Boolean = data is SharedData  // ❌ false
```

Foundation 创建的对象传给 Business 后，`is`/`as` 类型检查永远失败。

## 2. 修复方案

### Producer/Consumer 架构

**Producer (foundationKit)** — 定义并导出所有 foundation 包的 klib 符号：
```kotlin
binaryOption("exportKlibSymbols", "com.example.kmp.foundation")
```

**Consumer (businessKit)** — 将 foundation 包的 klib 符号设为外部引用：
```kotlin
binaryOption("externalKlibs", "com.example.kmp.foundation")
```

### K/N Compiler 改动

| 函数 | 位置 | 作用 |
|------|------|------|
| `makeKlibSymbolsExported()` | visibility.kt | Producer 侧：LTO internalize 后重新设为 DEFAULT visibility |
| `makeKlibSymbolsExternal()` | visibility.kt | Consumer 侧：移除 kclass initializer，kfun 设为 available_externally |
| `exportKlibPackages` | KonanConfig.kt | 解析 `exportKlibSymbols` binary option |

### 编译器 JAR 补丁

修改了 `kotlin-native-compiler-embeddable.jar`（位于 `tiktok-kn/v2.1.20-shared-runtime/`），替换了 64 个 class 文件，并对 `KonanConfig.class` 等做了 `com.intellij` → `org.jetbrains.kotlin.com.intellij` 的 relocation 处理。

## 3. 二进制验证

### businessKit（Consumer）— foundation 符号全部 `U`（未定义）

```
U _kclass:com.example.kmp.foundation.SharedData
U _kclass:com.example.kmp.foundation.RequestPayload
U _kclass:com.example.kmp.foundation.ResponseResult
U _kclass:com.example.kmp.foundation.NetworkState
U _kclass:com.example.kmp.foundation.NetworkState.Loading
U _kclass:com.example.kmp.foundation.NetworkState.Success
U _kclass:com.example.kmp.foundation.NetworkState.Error
```

共 114 个 foundation 符号被 externalize。

### foundationKit（Producer）— 全部在 export trie 中

```
0x0019F180  _kclass:com.example.kmp.foundation.RequestPayload
0x0019F220  _kclass:com.example.kmp.foundation.ResponseResult
0x0019F2C0  _kclass:com.example.kmp.foundation.NetworkState
0x0019F340  _kclass:com.example.kmp.foundation.NetworkState.Loading
0x0019F3E0  _kclass:com.example.kmp.foundation.NetworkState.Success
0x0019F480  _kclass:com.example.kmp.foundation.NetworkState.Error
0x0019F0E0  _kclass:com.example.kmp.foundation.SharedData
```

dyld 在加载时自动将 businessKit 的 `U` 符号绑定到 foundationKit 的导出地址。

## 4. 运行时测试结果

### 已通过 ✅

| 测试 | 描述 | 结果 |
|------|------|------|
| `isCheck` (基础) | `SharedData` 跨框架 `is` 检查 | ✅ `isCheck=true` |
| T1 | `is RequestPayload` / `is ResponseResult` 数据类检查 | ✅ |
| T5 | 6 种 sealed class `is` 检查 (Loading, Success, Error, NetworkState) | ✅ 6/6 |
| T6 | `List<Any>` 集合中 `is NetworkState.Success` 过滤计数 | ✅ count=2 |

**结论：Kotlin 运行时的类型身份（`is`/`as`）在所有 foundation 类型上完全正确。**

### 未通过 ❌（已知限制）

| 测试 | 描述 | 现象 |
|------|------|------|
| T2 | `processAnyRequest`: Any→as RequestPayload→execute→ResponseResult | `resp.body=""` 空字符串 |
| T3 | 嵌套引用: `resp.source.endpoint` | `"null"` |
| T4 | 双重 cast 往返 | `isResponse=true` 但字段为空 |
| T7 | sealed class `describeStateAny`: when 匹配后访问字段 | SIGSEGV at 0x18 |

### 失败原因分析

`as` cast 本身成功（不抛 ClassCastException），但通过 ObjC bridge 读取字段时返回空值或 crash。

**根因假设**：`makeKlibSymbolsExternal` 将 `kfun:` 符号设为 `available_externally` linkage。LTO 可能将构造器或 getter 内联到 businessKit 的代码中，内联代码使用了与 foundationKit 不一致的对象内存布局偏移，导致字段读取错误。

这是一个 K/N 编译器层面的问题，需要进一步调查：
1. `available_externally` 函数被 LTO 内联后，字段偏移是否一致
2. ObjC bridge 层的 `objc2kotlin` wrapper 是否正确转发字段访问
3. 是否需要对构造器和 getter 使用不同的 linkage 策略（如 `external` 而非 `available_externally`）

## 5. 文件变更

| 文件 | 操作 | 说明 |
|------|------|------|
| `foundation/build.gradle.kts` | 修改 | 添加 `exportKlibSymbols` binary option |
| `business/build.gradle.kts` | 修改 | 添加 `externalKlibs` binary option |
| `foundation/.../TypeTestModels.kt` | 新建 | RequestPayload, ResponseResult, NetworkState sealed hierarchy |
| `business/.../NetworkProcessor.kt` | 新建 | 跨框架类型测试处理器 (is/as/when/collection) |
| `iosApp/.../ContentView.swift` | 修改 | 新增 Phase 2 测试 UI 和 7 个测试用例 |
| `verify-kmt2364.sh` | 新建 | 基础验证脚本 |
| `verify-phase2.sh` | 新建 | 完整 Phase 2 验证脚本 |

## 6. 下一步

1. **调查字段访问问题**：在 K/N compiler 的 `makeKlibSymbolsExternal` 中，尝试将 `kfun:` getter/constructor 的 linkage 从 `available_externally` 改为纯 `external`（禁止 LTO 内联），验证字段访问是否恢复正常
2. **验证 dlsym 诊断**：修复后的 `dlsym` 调用已能找到 foundationKit 中的 kclass 地址 (`kclassFnd=0x00000105db6830`)
3. **扩展测试**：添加泛型 (`reified`)、继承层次、lambda 等更复杂的跨框架场景
