# KMP V3 分体交付架构 研究记录

> 本文档记录了 KMP V3 XCFramework 分体交付架构研究项目的完整过程，包含所有关键发现、代码变更和验证结果。

---

## 项目背景

基于 [Android KMP Get Started Codelab](https://developer.android.com/codelabs/kmp-get-started) 搭建的 KMP 学习工程，主要目的是作为本地调试和验证环境，研究 **KMP V3 分体交付架构**（TikTok 模式）。

**核心研究问题**：
- Foundation 模块携带 K/N runtime + stdlib
- Business 模块"瘦身"，只包含业务符号，依赖 Foundation 模块
- 两个 XCFramework 通过 CocoaPods 分别交付
- 两套 K/N 运行时共存时是否真的互相隔离？跨框架传递 Kotlin 对象会不会崩溃？

**核心结论**：双运行时问题已被 4 个维度的实证坐实。详见 `xcframework_viz/reports/v3-dual-runtime-evidence-report.md`。

**相关链接**：
- YouTrack Issue: https://youtrack.jetbrains.com/issue/KMT-2364
- Kotlin 论坛: https://discuss.kotlinlang.org/t/feature-request-discussion-thin-kotlin-native-apple-frameworks-shared-runtime-deps-non-self-contained-framework/31018
- 远程仓库: https://github.com/jinweihan93-ops/codelab-android-kmp

---

## 工程结构

```
get-started/
├── androidApp/               # Android 应用
├── foundation/               # KMP Foundation 模块（携带 runtime）
│   ├── build.gradle.kts
│   ├── foundationKit.podspec
│   └── src/commonMain/kotlin/com/example/kmp/foundation/
│       ├── Platform.kt       # platform() 函数 + expect/actual
│       └── SharedData.kt     # 跨框架测试用 data class
├── business/                 # KMP Business 模块（依赖 foundation）
│   ├── build.gradle.kts      # api + export(project(":foundation"))
│   ├── businessKit.podspec
│   └── src/commonMain/kotlin/com/example/kmp/business/
│       ├── UserService.kt
│       ├── FeedService.kt
│       ├── SharedDataProcessor.kt  # 跨框架类型检查/强转测试
│       └── model/
│           ├── User.kt
│           └── FeedItem.kt
├── iosApp/
│   ├── Podfile
│   └── KMPGetStartedCodelab/
│       ├── ContentView.swift             # 集成所有测试的 UI
│       ├── RuntimeDuplicateTest.swift    # ObjC runtime 双运行时检测
│       └── KMPGetStartedCodelabApp.swift
├── xcframework_viz/
│   ├── xcframework-analyzer.py   # XCFramework 符号分析工具
│   ├── app-binary-analyzer.py    # App 包内嵌 framework 符号分析
│   ├── project.json
│   └── reports/
│       ├── v3-dual-runtime-evidence-report.md    # 综合实证报告
│       ├── v3-duplicate-symbol-analysis-2026-03-30.md
│       ├── kn-xcframework-symbol-analysis-2026-03-30.md
│       └── business-module-design.md
└── settings.gradle.kts       # 包含 :foundation 和 :business
```

---

## 技术栈版本

| 组件 | 版本 |
|------|------|
| Kotlin | 2.2.0 |
| AGP | 8.11.1 |
| Compose BOM | 2025.07.00 |
| minSdk | 24 |
| compileSdk | 36 |

---

## 关键发现（已验证）

### 1. 符号重复（XCFramework 级别）

| 类别 | foundationKit | businessKit | 重复数 | 重复率 |
|------|-------------|------------|--------|--------|
| K/N Runtime | 127 | 127 | **127** | **100%** |
| Kotlin Stdlib | 651 | 769 | **651** | **100%** |
| kotlinx | 4 | 4 | **4** | **100%** |
| **Total** | **2368** | **2714** | **2130** | **90%** |

### 2. 符号重复（App 包级别）

使用 `app-binary-analyzer.py` 分析 `.app/Frameworks/` 内的嵌入 framework：

| 指标 | 值 |
|------|-----|
| foundationKit defined symbols | 7961 |
| businessKit defined symbols | 8351 |
| **重复符号** | **7821 (98.2%)** |
| K/N Runtime 重复 | 368 |
| Kotlin Stdlib 重复 | 2048 |

### 3. 两套 GC 线程并行运行

`sample` 采样 App 进程，发现 **4 个 K/N GC 线程**（2 组 x 2）：

```
Thread_90456540: GC Timer thread    ← (in foundationKit)
Thread_90456541: Main GC Thread     ← (in foundationKit)
Thread_90456542: GC Timer thread    ← (in businessKit)
Thread_90456543: Main GC Thread     ← (in businessKit)
```

每个运行时各自创建 `kotlin::RepeatedTimer`（GC 调度）和 `kotlin::gc::internal::MainGCThread`（GC 执行）。

### 4. 两套独立 ObjC 类层次

- `FoundationKitBase` (26 classes) ≠ `BusinessKitBase` (31 classes)
- `dladdr` 证明来自不同 dylib image
- `objc_copyClassList` 枚举出两套完整的 K/N 基础类（Number, Boolean, KListAsNSArray 等）

### 5. 跨框架 Kotlin 对象不兼容

- `foundationKit.SharedData` 和 `businessKit.SharedData` 在 Swift 层面是不同类型
- `processor.validateAsSharedData(foundationObj)` → **false**（Kotlin `is` 检查失败）
- `processor.forceProcessAny(foundationObj)` → **ClassCastException** 崩溃
- 根因：两个运行时为同一个 Kotlin class 生成了不同的 `typeInfo` 指针

### 6. iOS Two-Level Namespace 为什么不崩溃

iOS 动态链接器使用 **two-level namespace**：每个 dylib/framework 有独立的符号命名空间，重复符号不会冲突。这使得两套运行时可以并行存在，但也意味着无法真正"共享"基础 runtime。

---

## 关键文件说明

### business/build.gradle.kts 关键配置

```kotlin
// framework 中 export foundation 类型到 ObjC header
iosX64 {
    binaries.framework {
        baseName = xcfName
        xcf.add(this)
        export(project(":foundation"))  // re-export 到 ObjC header
    }
}

sourceSets {
    commonMain {
        dependencies {
            api(project(":foundation"))  // api 而非 implementation
            implementation(libs.kotlin.stdlib)
        }
    }
}
```

### foundation/SharedData.kt — 跨框架测试用

```kotlin
data class SharedData(val id: Int, val message: String)

fun createSharedData(id: Int, message: String): SharedData = SharedData(id, message)
fun describeSharedData(data: SharedData): String = "SharedData(id=${data.id}, message='${data.message}')"
```

### business/SharedDataProcessor.kt — 类型检查/强转测试

```kotlin
class SharedDataProcessor {
    fun processSharedData(data: SharedData): String = ...
    fun validateAsSharedData(data: Any): Boolean = data is SharedData  // 跨框架返回 false
    fun forceProcessAny(data: Any): String { val sd = data as SharedData; ... }  // 跨框架 ClassCastException
    fun createLocalSharedData(id: Int, message: String): SharedData = SharedData(id, message)
}
```

### iosApp/RuntimeDuplicateTest.swift — 运行时双份检测

3 个子测试：
- `testSeparateClassHierarchies()` — `NSClassFromString` 对比 FoundationKitBase vs BusinessKitBase
- `testDifferentDylibImages()` — `dladdr` 检查类所在的 dylib image
- `testDuplicateObjCClasses()` — `objc_copyClassList` 枚举所有 K/N 前缀类

---

## 分析工具

### xcframework-analyzer.py

```bash
# Project 模式（分析 project.json 中定义的多个 XCFramework）
python3 xcframework-analyzer.py --project-config project.json

# 查看 Headers / JSON / 符号列表
python3 xcframework-analyzer.py --project-config project.json --headers
python3 xcframework-analyzer.py --project-config project.json --json
python3 xcframework-analyzer.py --project-config project.json --symbols
```

### app-binary-analyzer.py

```bash
# 分析构建后的 .app 包内嵌入的 framework 符号
python3 app-binary-analyzer.py path/to/KMPGetStartedCodelab.app
python3 app-binary-analyzer.py path/to/KMPGetStartedCodelab.app --json
python3 app-binary-analyzer.py path/to/KMPGetStartedCodelab.app --symbols
```

### project.json

```json
{
  "name": "V3-prototype",
  "frameworks": [
    { "path": "../foundation/build/XCFrameworks/release/foundationKit.xcframework", "role": "foundation" },
    { "path": "../business/build/XCFrameworks/release/businessKit.xcframework", "role": "business" }
  ]
}
```

---

## 构建命令

### Kotlin 模块构建

```bash
cd get-started

# Debug XCFramework（开发用）
./gradlew :foundation:buildIOSDebug :business:buildIOSDebug

# Release XCFramework（符号分析用）
./gradlew :foundation:buildIOSRelease :business:buildIOSRelease
```

### iOS App 集成与构建

```bash
cd iosApp
LANG=en_US.UTF-8 pod install

# 命令行构建
xcodebuild build \
  -workspace KMPGetStartedCodelab.xcworkspace \
  -scheme KMPGetStartedCodelab \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath ./build-output
```

### 运行到模拟器

```bash
# 安装（注意 bundle ID 中 exampe 是原 codelab 的 typo）
xcrun simctl install booted path/to/KMPGetStartedCodelab.app
xcrun simctl launch booted com.exampe.kmp.getstarted.KMPGetStartedCodelab
```

### GC 线程采样

```bash
# 获取 PID 后
sample <PID> 1 -file /tmp/sample.txt
grep -E "GC|kotlin" /tmp/sample.txt
```

---

## 常见错误与解决

| 错误 | 原因 | 解决 |
|------|------|------|
| `pod install` 报 encoding 错误 | 终端 locale 不是 UTF-8 | 加前缀 `LANG=en_US.UTF-8` |
| `import businessKit` 找不到 | typo 写成 `bussinessKit` | 检查拼写 |
| Swift 类型歧义 `ambiguous use of 'init'` | 两个模块 export 同名类型 | 用模块限定名 `foundationKit.XXX` / `businessKit.XXX` |
| `assembleXXXXDebugXCFramework` task 找不到 | task 名依赖 xcfName 拼接 | 用 `./gradlew tasks` 查看实际 task 名 |
| Xcode GUI Run 按钮灰色 | 没有选择真机/模拟器 | 改用命令行 xcodebuild |
| `nm -defined-only` 在 macOS 上不工作 | macOS nm 不支持 GNU 长参数 | 用 `nm -U`（exclude undefined） |
| Gradle configuration cache 报错 | lambda 捕获了 Gradle project 引用 | 在 task 内直接使用 `file()` + `notCompatibleWithConfigurationCache()` |
| `simctl launch` 失败 | Bundle ID 不正确 | 用 `plutil -p Info.plist` 查看真实 bundle ID |

---

## 研究进展

- [x] 搭建 Foundation + Business 两模块 KMP 原型
- [x] 实现 TikTok 风格 CocoaPods 交付（podspec + buildIOSDebug task）
- [x] iOS App 通过 CocoaPods 集成两个 pod（`spec.dependency` 正确声明依赖）
- [x] 移除旧的 Xcode "Compile Kotlin Framework" Build Phase
- [x] App 成功运行，同时调用 foundation 和 business 模块
- [x] 符号分析：XCFramework 级别确认 2130/2714 (78.5%) 符号重复
- [x] 符号分析：App 包级别确认 7821/8351 (98.2%) 符号重复
- [x] GC 线程采样：确认 4 个 GC 线程分属两个框架
- [x] ObjC 运行时验证：两套独立类层次 + dladdr 不同 image
- [x] 跨框架 Kotlin 对象传递：`is` 检查失败 + `as` 强转 ClassCastException
- [x] 写综合研究报告 (`v3-dual-runtime-evidence-report.md`)

### 下一步（Q2 待评估）

- [ ] Path A: 构建后脚本剥离重复符号
- [ ] Path B: weak framework linking (`-weak_framework foundationKit`)
- [ ] Path C: K/N 编译器自定义 flags (`-Xstatic-framework`, `-Xpartial-linkage`)

---

*最后更新：2026-03-31*
*项目路径：`/Users/bytedance/codelab-android-kmp/get-started`*
