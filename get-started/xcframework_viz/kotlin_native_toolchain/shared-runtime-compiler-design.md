# K/N 分体 XCFramework 共享运行时：编译器改造方案

> 本文档是 [v3-split-delivery-paths-report.md](../reports/v3-split-delivery-paths-report.md) 的续篇。
> 在确认所有构建后路径（Path A–E）均不可行后，本文档转向**编译器层面**，
> 设计一套 ROI 最高的 K/N 编译器改造方案。
>
> 目标：两个独立交付的 XCFramework（如 foundationKit + businessKit）共享同一套
> K/N 运行时，彻底消除双 GC 线程和跨框架对象不兼容问题。

---

## 1. 问题根因（编译器视角）

### 1.1 编译流水线回顾

K/N 生成 Apple Framework 的完整流水线：

```
Kotlin IR (businessKit)
    ↓ K/N backend (IrToBitcode.kt)
LLVM IR — runtime 函数为 declare（undefined reference）
    ↓ collectLlvmModules()          ← 关键分支点
    + runtime.bc                    ← runtime, mm, gc, allocator 等
    + bitcodePartOfStdlib           ← kotlin stdlib bitcode
    + bitcodeLibraries              ← kotlinx 等 klib 的 bitcode
    ↓ linkRuntimeModules() — LTO 合并优化
    ↓ llvmLinkModules2() — 合并进主模块
    ↓ LTO internalization pass      ← 非导出符号全部变为 LLVMInternalLinkage
Mach-O binary                       ← runtime 符号为 `t`（local），无法跨 dylib 引用
                                       runtime 函数调用为 PC-relative `bl` 直接分支
```

### 1.2 关键门控代码（已定位）

**`CompilerOutput.kt:54-55`** — runtime 链接的门控：

```kotlin
internal val NativeGenerationState.shouldLinkRuntimeNativeLibraries: Boolean
    get() = producedLlvmModuleContainsStdlib && cacheDeserializationStrategy.containsRuntime
```

**`CompilerOutput.kt:86-130`** — 两个独立的 bitcode bucket：

```kotlin
private fun collectLlvmModules(...): LlvmModules {
    // Bucket 1: runtime 相关 .bc 文件
    val runtimeModules = parseBitcodeFiles(
        (runtimeNativeLibraries + bitcodePartOfStdlib)      // runtime.bc, mm.bc, gc.bc...
            .takeIf { generationState.shouldLinkRuntimeNativeLibraries }.orEmpty()
    )
    // Bucket 2: klib 依赖的 bitcode
    val additionalModules = parseBitcodeFiles(
        bitcodeLibraries    // kotlinx-coroutines-core.bc 等
    )
    return LlvmModules(runtimeModules, additionalModules)
}
```

**`KonanConfig.kt:383-446`** — runtimeNativeLibraries 硬编码列表：

```kotlin
internal val runtimeNativeLibraries: List<String> = mutableListOf<String>().apply {
    if (debug) add("debug.bc")
    add("runtime.bc")
    add("mm.bc")
    add("common_alloc.bc")
    add("common_gc.bc")
    add("common_gcScheduler.bc")
    // ... GC 实现、allocator 实现等
}
```

### 1.3 为什么符号变成 `t`

LTO 完成后，internalization pass 会将所有不在"导出列表"里的符号改为 `LLVMInternalLinkage`。
对于 runtime 函数（如 `_IsInstance`、`_CallInitGlobalPossiblyLock`），它们不在 ObjC 导出层，
因此全部变为 `t`（local）。两个框架各自有一份 `t` 符号，互不可见。

---

## 2. 解决方案设计

### 2.1 核心思路

引入**两侧对称配置**：

- **Producer 侧**（如 foundationKit）：正常嵌入 runtime，但阻止 runtime 符号被 internalize，
  使其在 Mach-O 中保持为 `T`（global exported）
- **Consumer 侧**（如 businessKit）：跳过 runtime.bc 的 LTO 合并，LLVM IR 中的 runtime
  函数保持为 `declare`，CodeGen 生成 PLT/stub 跳转，dyld 在加载时将其解析到 producer 框架

```
Producer (foundationKit)           Consumer (businessKit)
┌──────────────────────────┐       ┌──────────────────────────────┐
│ embedRuntime = true        │       │ embedRuntime = false           │
│ exportRuntimeSymbols = true│       │ linkerOpts("-framework        │
│                            │       │   FoundationSDK")             │
│ runtime.bc → LTO           │       │ runtime.bc → 跳过             │
│ runtime 符号保持 T          │       │ runtime 函数 = declare        │
│ _IsInstance: T (exported)  │       │ _IsInstance → PLT stub        │
└──────────────────────────┘       └──────────────────────────────┘
                  │                              │
                  └──────────── dyld ────────────┘
                         businessKit 的 runtime 调用
                         在加载时解析到 foundationKit
```

### 2.2 目标 Gradle DSL

```kotlin
// foundationKit/build.gradle.kts — Producer 侧
kotlin {
    iosX64 {
        binaries.framework {
            baseName = "foundationKit"
            embedRuntime = true               // 默认值，显式写出
            exportRuntimeSymbols = true       // 新增：阻止 runtime 符号 internalization
        }
    }
}

// businessKit/build.gradle.kts — Consumer 侧
kotlin {
    iosX64 {
        binaries.framework {
            baseName = "businessKit"
            embedRuntime = false                                        // 新增：跳过 runtime.bc
            excludeDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core")  // 新增：跳过指定 klib bitcode
            linkerOpts("-framework FoundationSDK")                      // 已有机制，声明运行时来源
        }
    }
}
```

### 2.3 两个 DSL 属性控制的 bucket 不同

这是关键设计：`embedRuntime` 和 `excludeDependency` 操控的是 `collectLlvmModules()` 里
**两个独立的 bitcode bucket**，互不干扰：

| DSL 属性 | 控制的 bucket | 对应源码 |
|---------|-------------|---------|
| `embedRuntime = false` | Bucket 1：runtime.bc, mm.bc, gc.bc 等 | `shouldLinkRuntimeNativeLibraries` 门控 |
| `excludeDependency(...)` | Bucket 2：klib 的 bitcode（kotlinx 等） | `bitcodeLibraries` 过滤 |

两个属性正交，可以独立使用。

---

## 3. 编译器改造详细设计

### 3.1 需要修改的文件

| 文件 | 改动类型 | 估计行数 |
|------|---------|---------|
| `BinaryOptions.kt` | 新增 `embedRuntime` / `exportRuntimeSymbols` 两个 BinaryOption | ~20 行 |
| `CompilerOutput.kt` | `shouldLinkRuntimeNativeLibraries` 增加 `embedRuntime` 门控；`collectLlvmModules` 增加 `excludeDependency` 过滤 | ~30 行 |
| `OptimizationPipeline.kt` | `exportRuntimeSymbols = true` 时，把 runtimeNativeLibraries 的符号加入 internalization 豁免列表 | ~40 行 |
| `KonanConfig.kt` | 读取新的 BinaryOption 值 | ~10 行 |
| `embedAppleLinkerOptionsToBitcode()` | consumer 模式自动注入 `-framework` 依赖（可选，也可让用户手写 linkerOpts） | ~20 行 |
| Gradle Plugin 侧 | 把新 BinaryOption 暴露为 Gradle DSL 属性 | ~50 行 |

**总改动量估计：200–400 行，集中在 4–5 个文件。**

### 3.2 Producer 侧：阻止 runtime 符号 internalization

LTO internalization pass 通过一个"保留符号集"决定哪些符号不能变为 internal。
当 `exportRuntimeSymbols = true` 时，需要：

1. 收集 `runtimeNativeLibraries` 中所有 `external` 函数的符号名
2. 将这些名字加入 LLVM PassManagerBuilder 的 ExportedSymbols / preserved set

```
// OptimizationPipeline.kt 改造方向（伪代码）
if (config.exportRuntimeSymbols) {
    val runtimeSymbols = collectExternalSymbolsFromModules(runtimeModules)
    passManagerBuilder.setExportedSymbols(runtimeSymbols)
}
```

这样 `_IsInstance` 等函数在 LTO 后保持 `LLVMExternalLinkage`，出现在最终 Mach-O 的导出表中（`T`）。

### 3.3 Consumer 侧：跳过 runtime.bc

`CompilerOutput.kt` 改造只需一处条件判断：

```kotlin
// 现有代码
internal val NativeGenerationState.shouldLinkRuntimeNativeLibraries: Boolean
    get() = producedLlvmModuleContainsStdlib && cacheDeserializationStrategy.containsRuntime

// 改造后
internal val NativeGenerationState.shouldLinkRuntimeNativeLibraries: Boolean
    get() = !config.embedRuntimeDisabled &&   // ← 新增门控
            producedLlvmModuleContainsStdlib &&
            cacheDeserializationStrategy.containsRuntime
```

跳过后，LLVM IR 中所有 runtime 函数调用保持为 `declare`（undefined reference）。
LLVM CodeGen 为它们生成 PLT stub，dyld 在框架加载时将 stub 解析到 `-framework FoundationSDK`
中对应的 `T` 符号。

### 3.4 Consumer 侧：excludeDependency

`collectLlvmModules()` 中对 `bitcodeLibraries` 增加过滤：

```kotlin
val bitcodeLibraries = generationState.dependenciesTracker.bitcodeToLink
    .filter { lib ->
        val gavCoordinate = lib.toMavenCoordinate()   // org.jetbrains.kotlinx:kotlinx-coroutines-core
        gavCoordinate !in config.excludedDependencies  // ← 根据 excludeDependency() 列表过滤
    }
    .flatMap { it.bitcodePaths }
    .filter { it.isBitcode }
```

---

## 4. 技术可行性分析

### 4.1 dyld 解析路径验证

iOS two-level namespace **不是**障碍，而是使此方案成立的机制：

- businessKit 在链接时加入 `-framework FoundationSDK`
- businessKit 的 `__DATA,__la_symbol_ptr` 中每条 stub 条目都会记录"查找 FoundationSDK 的 `_IsInstance`"
- dyld 加载 businessKit 时，按照 two-level binding 直接定向到 FoundationSDK.framework
- 无需 `-flat_namespace`，完全符合 App Store 要求

### 4.2 typeInfo 兼容性

`SharedData` 的 `typeInfo` struct 由定义它的模块（foundationKit）生成。
consumer 模式下 businessKit 不会重新生成自己的 `typeInfo` 副本——因为 `typeInfo` 的
bitcode 来自 klib，而不是 `runtime.bc`。

结果：`is SharedData` 检查在 businessKit 中会使用 foundationKit 的 typeInfo 指针，
跨框架对象类型检查恢复正常。

### 4.3 GC 管理

GC 线程启动代码在 `runtime.bc` 中（`kotlin::gc::internal::MainGCThread`）。
consumer 模式跳过 `runtime.bc` 后，businessKit 不会启动自己的 GC 线程。

businessKit 中 Kotlin 对象的内存分配调用（`_Konan_mm_tryAllocateDataBatch` 等）会通过
PLT stub 路由到 foundationKit 的分配器，由 foundationKit 的 GC 统一管理。

预期结果：进程中只有 **2 个 GC 线程**（来自 foundationKit），而不是现在的 4 个。

### 4.4 主要风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| LTO 优化行为差异：无 runtime 时优化器可能做出不同决策 | 潜在运行时崩溃 | 充分的集成测试 |
| runtime 符号集不稳定：K/N 版本升级可能增减 runtime 符号 | producer/consumer 版本需严格对齐 | 要求两者使用同一 K/N 版本编译 |
| `-dead_strip` 删除未被直接调用的 runtime 符号 | consumer 链接失败 | producer 侧加 `-exported_symbols_list` 明确保留 |
| `excludeDependency` 版本冲突：producer/consumer 依赖版本不一致 | 运行时行为不确定 | 构建时检查版本一致性 |

---

## 5. POC 实施路径（分步验证）

### Step 1：验证 producer 侧 runtime 符号导出（1–2 天）

在本地 Kotlin 仓库编译 K/N，修改 `OptimizationPipeline.kt` 为 foundationKit 的 runtime
符号豁免 internalization。

验证目标：
```bash
nm -m foundationKit.framework/foundationKit | grep "_IsInstance"
# 期望：T _IsInstance（而不是 t _IsInstance）
```

### Step 2：验证 consumer 侧跳过 runtime（1–2 天）

修改 `CompilerOutput.kt`，让 businessKit 在 `embedRuntime = false` 时跳过 runtime.bc。

验证目标：
```bash
nm -U businessKit.framework/businessKit | grep "_IsInstance"
# 期望：undefined _IsInstance（而不是 t _IsInstance）
```

### Step 3：链接验证（1 天）

给 businessKit 加上 `linkerOpts("-framework foundationKit")`，构建完整 App。

验证目标：
```bash
otool -L businessKit.framework/businessKit | grep foundationKit
# 期望：@rpath/foundationKit.framework/foundationKit

nm -m businessKit.framework/businessKit | grep "_IsInstance"
# 期望：(foundationKit) _IsInstance（通过 two-level binding 解析到 foundationKit）
```

### Step 4：运行时验证（1–2 天）

运行原型 App，执行全部测试用例。

验证目标：
```
GC 线程数量：2（期望，来自 foundationKit）
processor.validateAsSharedData(fromFoundation)：true（期望，typeInfo 兼容）
processor.forceProcessAny(fromFoundation)：不崩溃（期望）
```

---

## 6. 与 KMT-2364 的关系

**JetBrains YouTrack**: [KMT-2364 - Thin Kotlin/Native Apple frameworks](https://youtrack.jetbrains.com/issue/KMT-2364)

本方案是 KMT-2364 请求功能的一种具体实现路径，与 JetBrains 内部称的
"non-self-contained framework"支持方向一致。

主要差异：
- KMT-2364 可能采用不同的 DSL 设计（JetBrains 尚未公布设计文档）
- 本方案是面向 TikTok 场景的工程优先实现，不一定完全对齐官方最终 API
- 可作为 upstream PR 或社区参考实现

---

## 7. 总结

| 维度 | 现状（K/N 2.2.0） | 改造后 |
|------|-----------------|-------|
| GC 线程数 | 4（双份） | 2（foundationKit 统一管理） |
| 跨框架 Kotlin 对象 `is` 检查 | false（崩溃风险） | true（typeInfo 统一） |
| businessKit 包体 | ~720 KB（含完整 runtime） | ~60 KB（仅业务符号） |
| 编译器改动量 | — | ~300 行，4–5 个文件 |
| 是否需要修改 K/N runtime 本身 | — | 否，只改编译流水线 |

**结论**：在 K/N 编译器中，runtime 嵌入的门控代码已定位，修改路径清晰，
改动量小，ROI 高。此方案是当前 K/N 版本下实现分体 XCFramework 共享运行时的最优路径。

---

*分析日期：2026-03-31*
*K/N 版本：2.2.0*
*参考源码路径：`kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/`*
*关键文件：`CompilerOutput.kt`, `RuntimeLinkage.kt`, `KonanConfig.kt`, `OptimizationPipeline.kt`*
