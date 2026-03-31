# KMP Get Started — 项目现状

## 项目背景

基于 [Android KMP Get Started Codelab](https://developer.android.com/codelabs/kmp-get-started) 搭建的 KMP 学习工程。
主要目的是作为本地调试和验证环境，支持 KMP V3 分体交付架构的 prototype 研究。

## 工程结构

```
get-started/
├── androidApp/          # Android 应用，依赖 :foundation 模块
├── foundation/          # KMP 共享模块（当前唯一 KMP 模块）
│   └── src/
│       ├── commonMain/  # 跨平台公共代码
│       ├── androidMain/ # Android 实现
│       └── iosMain/     # iOS 实现
├── iosApp/              # iOS Xcode 工程 (KMPGetStartedCodelab.xcodeproj)
├── xcframework_viz/     # XCFramework 分析工具（自研）
│   ├── xcframework-analyzer.py
│   └── project.json
└── CLAUDE.md            # 本文件
```

## 技术栈版本

| 组件 | 版本 |
|------|------|
| Kotlin | 2.2.0 |
| AGP | 8.11.1 |
| Compose BOM | 2025.07.00 |
| minSdk | 26 |
| compileSdk | 35 |

## foundation 模块

当前实现极简，只有一个 `expect/actual` 示例：

```kotlin
// commonMain
expect fun platform(): String

// androidMain
actual fun platform() = "Android"

// iosMain
actual fun platform() = "iOS"
```

iOS targets: `iosX64`, `iosArm64`, `iosSimulatorArm64`

## iOS 构建配置（已修改）

**原始 codelab 配置**（生成单架构 `.framework`）：
```kotlin
iosX64 { binaries.framework { baseName = xcfName } }
// ...
```

**已改为 XCFramework 配置**（生成多架构 `.xcframework`）：
```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

val xcf = XCFramework(xcfName)
iosX64 {
    binaries.framework {
        baseName = xcfName
        xcf.add(this)
    }
}
// iosArm64, iosSimulatorArm64 同理
```

### 构建命令

```bash
# 构建 Release XCFramework
./gradlew :foundation:assembleFoundationKitReleaseXCFramework

# 构建 Debug XCFramework
./gradlew :foundation:assembleFoundationKitDebugXCFramework

# 产物路径
# foundation/build/XCFrameworks/release/foundationKit.xcframework
# foundation/build/XCFrameworks/debug/foundationKit.xcframework
```

## XCFramework 分析工具

路径：`xcframework_viz/xcframework-analyzer.py`

```bash
# 分析单个 XCFramework
python3 xcframework_viz/xcframework-analyzer.py \
  foundation/build/XCFrameworks/release/foundationKit.xcframework

# 查看完整符号列表
python3 xcframework_viz/xcframework-analyzer.py \
  foundation/build/XCFrameworks/release/foundationKit.xcframework \
  --symbols

# 过滤特定符号
python3 xcframework_viz/xcframework-analyzer.py \
  foundation/build/XCFrameworks/release/foundationKit.xcframework \
  --filter "kotlinx"

# 查看 ObjC headers
python3 xcframework_viz/xcframework-analyzer.py \
  foundation/build/XCFrameworks/release/foundationKit.xcframework \
  --headers

# 对比两个 XCFramework（检测重复符号）
python3 xcframework_viz/xcframework-analyzer.py A.xcframework --compare B.xcframework

# Project 模式（分析多个 XCFramework + 跨框架重复符号分析）
python3 xcframework_viz/xcframework-analyzer.py --project ./build/XCFrameworks/release/

# 输出 JSON
python3 xcframework_viz/xcframework-analyzer.py \
  foundation/build/XCFrameworks/release/foundationKit.xcframework \
  --json
```

分析工具的符号分类：

| 类别 | 含义 |
|------|------|
| `kotlin_runtime` | Kotlin/Native 运行时（有则说明 runtime 被 embed） |
| `kotlin_stdlib` | Kotlin 标准库 |
| `kotlinx` | kotlinx 系列库（coroutines 等） |
| `objc_export` | ObjC 导出的类/元类 |
| `kotlin_user_api` | 用户业务代码导出的 kfun/kclass |
| `cinterop` | cinterop bridge 符号 |

## 上下文：KMP V3 架构研究

本工程同时用于 KMP V3 分体交付架构的技术验证。

**V3 核心目标：** 将单体 KMPTikTokHost 拆分为 `KMPFoundation` + `KMP[VC]Shell` 多产物交付。

**当前状态：**
- RFC 设计文档已完成，架构定义清晰
- JetBrains 官方确认 1-2 年内不会原生支持此模型（KMT-2364）
- 实现路径需要在构建 pipeline 中做定制化，技术选型待 Q2 评估

**Q2 待验证的技术路径：**
1. 单体 xcframework 构建完成后，脚本后置拆分
2. link 阶段介入，控制符号打包范围
3. 其他构建阶段的介入点

**相关链接：**
- YouTrack Issue: https://youtrack.jetbrains.com/issue/KMT-2364
- Kotlin 论坛讨论: https://discuss.kotlinlang.org/t/feature-request-discussion-thin-kotlin-native-apple-frameworks-shared-runtime-deps-non-self-contained-framework/31018
