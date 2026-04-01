# TikTok Kotlin/Native 分支 — 共享运行时 POC

> **基础版本**: Kotlin `v2.1.20`
> **发布分支**: `tiktok/v2.1.20-shared-runtime`
> **分支位置**: `/Users/bytedance/kotlin/.claire/worktrees/cool-mcnulty/`
> **工具链（稳定符号链接）**: `~/tiktok-kn/v2.1.20-shared-runtime/`
> **编译器版本字符串**: `2.1.255-SNAPSHOT` ← 用于确认工具链已生效

---

## 快速开始（Demo 工程接入）

在 demo 工程根目录的 `local.properties` 中添加：

```properties
kotlin.native.home=/Users/bytedance/tiktok-kn/v2.1.20-shared-runtime
```

编译时 Gradle console 会出现以下水印，确认使用的是 TikTok 定制版编译器：

```
w: [TikTok KN] base=2.1.20  branch=claude/cool-mcnulty  features=shared-runtime-poc
```

---

## 背景

TikTok iOS 采用 KMP 多 XCFramework 交付方案：Foundation SDK（基础层）与 BusinessKit（业务层）各自独立发版。
标准 K/N 编译器为每个 framework 都嵌入完整的运行时（GC、MM、stdlib 等），导致：

| 问题 | 表现 |
|------|------|
| 运行时重复 | 两套 GC 线程、两套内存管理器同时运行 |
| 符号冲突 | `typeInfo`、ObjC 类层级重复注册 |
| 跨框架对象不兼容 | `is`/`as` 检查跨框架 crash（不同的 `typeInfo` 副本） |
| 包体积浪费 | runtime bitcode (~2MB) 在每个 framework 里各占一份 |

本 POC 在编译器层面新增两个 `BinaryOption`，允许将一个 framework 标记为
**Producer**（嵌入并导出运行时）、另一个标记为 **Consumer**（不嵌入运行时，
运行时符号由 Producer framework 在 dyld 加载时提供）。

---

## 新增编译器功能

### 1. `embedRuntime` — 控制运行时嵌入（Bucket 1）

| 值 | 行为 |
|----|------|
| `true`（默认） | 标准行为：将 `runtime.bc`、`mm.bc`、`gc.bc` 等链接进 binary |
| `false` | Consumer 模式：跳过 Bucket 1，binary 中不含运行时符号 |

**Gradle DSL：**
```kotlin
binaries.framework("BusinessKit") {
    binaryOption("embedRuntime", "false")
}
```

**原理**：修改 `CompilerOutput.kt` 中的 `shouldLinkRuntimeNativeLibraries` gate：
```kotlin
// 改动前
get() = producedLlvmModuleContainsStdlib && cacheDeserializationStrategy.containsRuntime

// 改动后（+1 行）
get() = producedLlvmModuleContainsStdlib && cacheDeserializationStrategy.containsRuntime
        && config.embedRuntime  // KMT-2364: false → consumer framework, skip Bucket 1
```

---

### 2. `excludedRuntimeLibraries` — 排除指定 klib bitcode（Bucket 2）

逗号分隔的 klib `unique_name` 列表。列表中的 klib 不会将其 bitcode 链接进 consumer binary。

**典型用途**：`kotlinx-coroutines-core` 已由 Producer framework 提供，Consumer 排除它避免符号重复。

**Gradle DSL：**
```kotlin
binaryOption("excludedRuntimeLibraries", "org.jetbrains.kotlinx:kotlinx-coroutines-core")
// 多个用逗号分隔：
// binaryOption("excludedRuntimeLibraries", "org.jetbrains.kotlinx:kotlinx-coroutines-core,org.jetbrains.kotlinx:atomicfu")
```

**原理**：在 `collectLlvmModules()` 中，partition 后 flatMap 前按 `uniqueName` 过滤：
```kotlin
val filteredNonStdlibLibraries = if (excludedLibs.isEmpty()) nonStdlibLibraries
else nonStdlibLibraries.filterNot { lib -> lib.uniqueName in excludedLibs }
```

---

### 3. Xcode 26 兼容修复（`ClangArgs.kt`）

Xcode 26 将 `UIUtilities.framework` 等部分 UIKit 子框架移至
`System/Library/SubFrameworks/`，K/N 2.1.20 内置的 LLVM 16 clang 默认不搜索该路径。

**修复**：为 Apple 目标添加 `-iframework .../System/Library/SubFrameworks`：
```kotlin
is AppleConfigurables -> arrayOf(
    "-stdlib=libc++",
    "-iframework", "$absoluteTargetSysRoot/System/Library/SubFrameworks"
)
```

---

## 修改文件清单

| 文件 | 改动 |
|------|------|
| `kotlin-native/backend.native/.../BinaryOptions.kt` | 新增 `embedRuntime`、`excludedRuntimeLibraries` |
| `kotlin-native/backend.native/.../KonanConfig.kt` | 读取并暴露两个新属性 |
| `kotlin-native/backend.native/.../CompilerOutput.kt` | gate + Bucket 2 过滤 |
| `kotlin-native/backend.native/.../KonanDriver.kt` | 编译器水印 |
| `native/utils/.../ClangArgs.kt` | Xcode 26 SubFrameworks 搜索路径 |

Git 提交记录（在 `v2.1.20` tag 之上）：
```
8c5c90e  Add TikTok compiler watermark to Gradle build console
e33bf21  POC: shared-runtime XCFramework compiler support (KMT-2364)
```

---

## Demo 工程接入步骤

### 第 1 步 — 指向 TikTok 工具链

```properties
# local.properties
kotlin.native.home=/Users/bytedance/tiktok-kn/v2.1.20-shared-runtime
```

编译任意目标，确认 console 出现水印：
```
w: [TikTok KN] base=2.1.20  branch=claude/cool-mcnulty  features=shared-runtime-poc
```

### 第 2 步 — 基线验证（embedRuntime=true，默认行为不变）

先不加任何 `binaryOption`，确认工具链可以正常编译出 framework，行为与官方 2.1.20 完全一致。

```kotlin
// foundation/build.gradle.kts — Producer，默认 embedRuntime=true
kotlin {
    iosArm64 { binaries.framework("FoundationSDK") { } }
    iosSimulatorArm64 { binaries.framework("FoundationSDK") { } }
}
```

```bash
./gradlew :foundation:assembleFoundationSDKReleaseXCFramework

# 验证：runtime 符号在 binary 里
nm FoundationSDK.framework/FoundationSDK | grep " T " | wc -l   # 期望：数千个
```

### 第 3 步 — Consumer 编译验证（embedRuntime=false）

```kotlin
// business/build.gradle.kts — Consumer，不嵌入 runtime
kotlin {
    iosArm64 {
        binaries.framework("BusinessKit") {
            binaryOption("embedRuntime", "false")
        }
    }
    iosSimulatorArm64 {
        binaries.framework("BusinessKit") {
            binaryOption("embedRuntime", "false")
        }
    }
}
```

```bash
./gradlew :business:assembleBusinessKitDebugXCFramework

# 验证：runtime 符号不在 binary 里（期望：无输出）
nm BusinessKit.framework/BusinessKit | grep "_AddTLSRecord\|_Kotlin_ObjCExport_refToObjC"
```

> ⚠️ 当前 POC 阶段，consumer framework 单独链接会报 `undefined symbols` 错误——
> 这是**预期行为**，说明 runtime 确实没被嵌入。完整测试需要第 4 步。

### 第 4 步 — 端到端测试（两个 framework 同时加载）

Consumer framework 链接时需要 FoundationSDK 的 runtime 符号：

```kotlin
// business/build.gradle.kts
binaries.framework("BusinessKit") {
    binaryOption("embedRuntime", "false")
    linkerOpts("-framework", "FoundationSDK")
    linkerOpts("-F", "\$SRCROOT/../FoundationSDK.xcframework/ios-arm64")
}
```

在 Xcode 的 iOS App target 中同时 embed 两个 framework，运行 App，验证：
- `[ ] 无 crash`
- `[ ] Instruments 中只有 1 个 GC 线程（而非 2 个）`
- `[ ] 跨框架对象的 is/as 转换正常`

---

## 已知限制（POC 阶段）

| 限制 | 当前状态 | 说明 |
|------|---------|------|
| Producer 符号导出 | ❌ 未解决 | LTO internalization 将 runtime 符号变为 `t`（local），Consumer dyld 无法解析。需要 version script 或 `-Xexport-runtime-symbols` |
| Consumer 独立链接 | ⚠️ 预期失败 | 无 FoundationSDK 时链接报 undefined symbols，正常 |
| iOS 26 platform libs | ⚠️ 用 prebuilt | dist 的 platform libs 来自官方 prebuilt 2.1.20，因 `DarwinFoundation1.modulemap` 与 LLVM 16 不兼容无法本地重建 |
| ObjC 类注册 | ❓ 待验证 | runtime 共享后 ObjC class hierarchy 重复注册问题需端到端验证 |

---

## 工具链信息

| 项目 | 值 |
|------|---|
| 基础 tag | `v2.1.20` |
| 发布分支 | `tiktok/v2.1.20-shared-runtime` |
| 编译器版本字符串 | `2.1.255-SNAPSHOT` |
| 工具链符号链接 | `~/tiktok-kn/v2.1.20-shared-runtime` |
| 工具链实体路径 | `/Users/bytedance/kotlin/.claude/worktrees/cool-mcnulty/kotlin-native/dist/` |
| 构建时间 | 2026-03-31 |

---

## 参考

- [KMT-2364](https://youtrack.jetbrains.com/issue/KMT-2364) — JetBrains 官方 issue：非自包含 framework 支持
- 设计文档：`../xcframework_viz/kotlin_native_toolchain/shared-runtime-compiler-design.md`
- 调查报告：`../xcframework_viz/reports/v3-split-delivery-paths-report.md`
