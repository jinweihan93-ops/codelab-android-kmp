# KMP V3 分体交付架构 研究记录

> 本文档记录了 KMP V3 XCFramework 分体交付架构研究项目的完整过程，包含所有关键发现、代码变更和待办事项。

---

## 项目背景

基于 [Android KMP Get Started Codelab](https://developer.android.com/codelabs/kmp-get-started) 搭建的 KMP 学习工程，主要目的是作为本地调试和验证环境，研究 **KMP V3 分体交付架构**（TikTok 模式）。

**核心研究问题**：
- Foundation 模块携带 K/N runtime + stdlib
- Business 模块"瘦身"，只包含业务符号，依赖 Foundation 模块
- 两个 XCFramework 通过 CocoaPods 分别交付
- 两套 K/N 运行时共存时是否真的互相隔离？跨框架传递 Kotlin 对象会不会崩溃？

**相关链接**：
- YouTrack Issue: https://youtrack.jetbrains.com/issue/KMT-2364
- Kotlin 论坛: https://discuss.kotlinlang.org/t/feature-request-discussion-thin-kotlin-native-apple-frameworks-shared-runtime-deps-non-self-contained-framework/31018

---

## 工程结构

```
get-started/
├── androidApp/               # Android 应用
├── foundation/               # KMP Foundation 模块（携带 runtime）
│   ├── build.gradle.kts
│   ├── foundationKit.podspec
│   └── src/commonMain/kotlin/com/example/kmp/foundation/
│       └── Platform.kt       # platform() 函数 + expect/actual
├── business/                 # KMP Business 模块（依赖 foundation）
│   ├── build.gradle.kts
│   ├── businessKit.podspec
│   └── src/commonMain/kotlin/com/example/kmp/business/
│       ├── UserService.kt
│       ├── FeedService.kt
│       └── model/
│           ├── User.kt
│           └── FeedItem.kt
├── iosApp/
│   ├── Podfile
│   └── KMPGetStartedCodelab/
│       └── ContentView.swift
├── xcframework_viz/
│   ├── xcframework-analyzer.py   # 主分析工具
│   ├── project.json
│   └── reports/
│       ├── kn-xcframework-symbol-analysis-2026-03-30.md
│       ├── business-module-design.md
│       └── v3-duplicate-symbol-analysis-2026-03-30.md
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

## 关键文件内容

### foundation/build.gradle.kts
```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
}

kotlin {
    androidLibrary {
        namespace = "com.example.kmp.foundation"
        compileSdk = 36
        minSdk = 24
    }

    val xcfName = "foundationKit"
    val xcf = XCFramework(xcfName)

    iosX64 { binaries.framework { baseName = xcfName; xcf.add(this) } }
    iosArm64 { binaries.framework { baseName = xcfName; xcf.add(this) } }
    iosSimulatorArm64 { binaries.framework { baseName = xcfName; xcf.add(this) } }

    sourceSets {
        commonMain { dependencies { implementation(libs.kotlin.stdlib) } }
        commonTest { dependencies { implementation(libs.kotlin.test) } }
    }
}

tasks.register("buildIOSDebug") {
    description = "Build iOS debug XCFramework and copy podspec for CocoaPods local integration"
    group = "kotlin multiplatform"
    dependsOn("assembleFoundationKitDebugXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val spec = file("foundationKit.podspec")
        val target = file("build/XCFrameworks/debug")
        spec.copyTo(file("${target}/foundationKit.podspec"), overwrite = true)
        println("✅ foundationKit debug built.\n   pod 'foundationKit', :path => '${target}'")
    }
}

tasks.register("buildIOSRelease") {
    description = "Build iOS release XCFramework and copy podspec for CocoaPods local integration"
    group = "kotlin multiplatform"
    dependsOn("assembleFoundationKitReleaseXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val spec = file("foundationKit.podspec")
        val target = file("build/XCFrameworks/release")
        spec.copyTo(file("${target}/foundationKit.podspec"), overwrite = true)
        println("✅ foundationKit release built.\n   pod 'foundationKit', :path => '${target}'")
    }
}
```

### business/build.gradle.kts
```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    androidLibrary {
        namespace = "com.example.kmp.business"
        compileSdk = 36
        minSdk = 24
    }

    val xcfName = "businessKit"
    val xcf = XCFramework(xcfName)

    iosX64 { binaries.framework { baseName = xcfName; xcf.add(this) } }
    iosArm64 { binaries.framework { baseName = xcfName; xcf.add(this) } }
    iosSimulatorArm64 { binaries.framework { baseName = xcfName; xcf.add(this) } }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":foundation"))
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}

tasks.register("buildIOSDebug") {
    description = "Build iOS debug XCFramework and copy podspec for CocoaPods local integration"
    group = "kotlin multiplatform"
    dependsOn("assembleBusinessKitDebugXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val spec = file("businessKit.podspec")
        val target = file("build/XCFrameworks/debug")
        spec.copyTo(file("${target}/businessKit.podspec"), overwrite = true)
        println("✅ businessKit debug built.\n   pod 'businessKit', :path => '${target}'")
    }
}

tasks.register("buildIOSRelease") {
    description = "Build iOS release XCFramework and copy podspec for CocoaPods local integration"
    group = "kotlin multiplatform"
    dependsOn("assembleBusinessKitReleaseXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val spec = file("businessKit.podspec")
        val target = file("build/XCFrameworks/release")
        spec.copyTo(file("${target}/businessKit.podspec"), overwrite = true)
        println("✅ businessKit release built.\n   pod 'businessKit', :path => '${target}'")
    }
}
```

### foundation/foundationKit.podspec
```ruby
Pod::Spec.new do |spec|
  spec.name                  = 'foundationKit'
  spec.version               = '0.1.0'
  spec.summary               = 'KMP Foundation XCFramework'
  spec.description           = 'Kotlin Multiplatform Foundation module. Provides platform(), carries K/N runtime + stdlib.'
  spec.homepage              = 'https://github.com/example/kmp-get-started'
  spec.license               = { :type => 'Apache-2.0' }
  spec.author                = { 'KMP Team' => 'kmp@example.com' }
  spec.source                = { :path => '.' }
  spec.ios.deployment_target = '14.0'
  spec.vendored_frameworks   = 'foundationKit.xcframework'
end
```

### business/businessKit.podspec
```ruby
Pod::Spec.new do |spec|
  spec.name                  = 'businessKit'
  spec.version               = '0.1.0'
  spec.summary               = 'KMP Business XCFramework — UserService, FeedService'
  spec.description           = 'Kotlin Multiplatform Business module. Provides UserService and FeedService. Depends on foundationKit.'
  spec.homepage              = 'https://github.com/example/kmp-get-started'
  spec.license               = { :type => 'Apache-2.0' }
  spec.author                = { 'KMP Team' => 'kmp@example.com' }
  spec.source                = { :path => '.' }
  spec.ios.deployment_target = '14.0'
  spec.vendored_frameworks   = 'businessKit.xcframework'
  spec.dependency 'foundationKit'
end
```

### iosApp/Podfile
```ruby
platform :ios, '14.0'
use_frameworks!
target 'KMPGetStartedCodelab' do
  pod 'foundationKit', :path => '../foundation/build/XCFrameworks/debug'
  pod 'businessKit',   :path => '../business/build/XCFrameworks/debug'
end
```

### iosApp/KMPGetStartedCodelab/ContentView.swift
```swift
import SwiftUI
import foundationKit
import businessKit

struct ContentView: View {
    var body: some View {
        VStack {
            Image(systemName: "globe")
                .imageScale(.large)
                .foregroundStyle(.tint)
            Text("Hello, \(Platform_iosKt.platform())!")

            let userService = UserService()
            let tag = userService.formatUserTag(user: userService.currentUser())
            Text("User: \(tag)")
        }
        .padding()
    }
}
```

---

## Business 模块代码

### model/User.kt
```kotlin
package com.example.kmp.business.model

data class User(val id: String, val name: String, val platform: String)
```

### model/FeedItem.kt
```kotlin
package com.example.kmp.business.model

data class FeedItem(val id: String, val title: String, val author: User)
```

### UserService.kt
```kotlin
package com.example.kmp.business

import com.example.kmp.business.model.User
import com.example.kmp.foundation.platform

class UserService {
    fun currentUser(): User = User(
        id = "u001",
        name = "KMP User",
        platform = platform()   // 调用 foundation 的 platform()
    )

    fun formatUserTag(user: User): String = "@${user.name}[${user.platform}]"
}
```

### FeedService.kt
```kotlin
package com.example.kmp.business

import com.example.kmp.business.model.FeedItem
import com.example.kmp.business.model.User

class FeedService {
    fun generateFeed(count: Int): List<FeedItem> = (1..count).map { i ->
        FeedItem(
            id = "feed_$i",
            title = "Feed Item $i",
            author = User("u$i", "Author $i", "iOS")
        )
    }
}
```

---

## 关键发现

### 1. K/N 自包含 XCFramework 特性

每个 K/N 编译的 XCFramework 默认会将完整的 K/N runtime + Kotlin stdlib 打包进去：
- foundationKit.xcframework：foundation 逻辑 + **runtime + stdlib**
- businessKit.xcframework：business 逻辑 + **完整一份** runtime + stdlib（重复！）

### 2. 符号重复分析结果（xcframework-analyzer.py）

| 类别 | 符号数 |
|------|--------|
| 总重复符号 | **2092 / 2626 (80%)** |
| K/N Runtime 符号（`_ZN12_GLOBAL__N_1`） | 127 |
| Kotlin Stdlib 符号（`_ZN6kotlin`） | 651 |
| 其他重复符号 | 1314 |

### 3. 为什么 App 不崩溃？（iOS Two-Level Namespace）

iOS 动态链接器使用 **two-level namespace**：
- 每个 dylib/framework 有独立的符号命名空间
- 重复符号不会冲突，各自保留一份
- `foundationKit.framework` 有自己的 K/N runtime 实例
- `businessKit.framework` 有自己的 K/N runtime 实例
- **两套运行时并行存在，互不干扰**

这就是 V3 分体架构的核心问题：无法真正"共享"基础 runtime，每个 XCFramework 都是完全独立的世界。

### 4. ObjC 类层次隔离（关键证据）

两个框架各自独立的 runtime 会创建各自的 ObjC root class：
- `foundationKit.framework` → `FoundationKitBase`（K/N runtime A 的根类）
- `businessKit.framework` → `BusinessKitBase`（K/N runtime B 的根类）

这是**同一个** Kotlin `KotlinBase` 类，但在两个完全独立的 ObjC 类层次中！

```swift
// 两套运行时的直接证据
let fbClass = NSClassFromString("FoundationKitBase")
let bbClass = NSClassFromString("BusinessKitBase")
// fbClass != bbClass → 两套独立运行时的直接证据
```

### 5. C++ Symbol Mangling 规则

K/N runtime 符号的识别模式：
- `_ZN12_GLOBAL__N_1` = C++ anonymous namespace（K/N runtime 内部）
- `_ZN6kotlin` = `kotlin::` namespace（K/N stdlib）
- `_ZN5konan` = `konan::` namespace（K/N platform）

---

## 待验证（三步骤）

### ✅ 已完成：App 跑起来了
App 成功运行，同时调用了 foundationKit（`platform()`）和 businessKit（`UserService`），无崩溃。

### Part 1：App Bundle 符号分析（待完成）

App Bundle 路径（模拟器）：
```
/Users/bytedance/Library/Developer/CoreSimulator/Devices/
0C5C5629-EEC0-48DD-89BB-8B5138B9E2FC/data/Containers/Bundle/Application/
F41182DD-10C6-415C-B8A0-DECBB578DE7A/KMPGetStartedCodelab.app
```

计划：在 `xcframework-analyzer.py` 中添加 `--app PATH` 参数，扫描 `.app/Frameworks/*.framework` 目录，运行 nm 分析重复符号，确认 app 内两套运行时都存在。

### Part 2：运行时确认（待完成）

在 Swift 中添加运行时检查：
```swift
let foundationClass = NSClassFromString("FoundationKitBase")
let businessClass = NSClassFromString("BusinessKitBase")
print("Same class? \(foundationClass === businessClass)")  // 应该输出 false
```

### Part 3：跨框架 Kotlin 对象崩溃验证（待完成）

**设计方案**：

1. 在 foundation 中添加 `SharedPoint(val x: Int, val y: Int)`

2. 在 business 中添加 `CrossRuntimeTest`：
   - `createPoint(x, y)` → 用 **business** runtime 创建 SharedPoint
   - `processPoint(p: SharedPoint)` → 接受 SharedPoint 参数

3. 在 iOS build config 中对 business 模块加 `export(project(":foundation"))`（re-export 类型到 ObjC header）

4. Swift 测试代码：
```swift
let test = CrossRuntimeTest()

// ✅ business 自己创建的对象 → 正常工作
let businessPoint = test.createPoint(x: 1, y: 2)
let result = test.processPoint(p: businessPoint)

// ❌ foundation 创建的对象 → 强转崩溃（EXC_BAD_ACCESS）
// FoundationKitSharedPoint 和 BusinessKitSharedPoint 是不同 ObjC 类
let foundationPoint = SharedPoint(x: 3, y: 4)  // foundation runtime
let crash = test.processPoint(p: foundationPoint as! BusinessKitSharedPoint)
```

---

## XCFramework 分析工具

### xcframework-analyzer.py 主要命令

```bash
# Project 模式（分析 project.json 中定义的多个 XCFramework）
python3 xcframework-analyzer.py --project project.json

# JSON 格式输出
python3 xcframework-analyzer.py --project project.json --json

# 查看 Headers（ObjC API）
python3 xcframework-analyzer.py --project project.json --headers

# 查看重复符号详情
python3 xcframework-analyzer.py --project project.json --duplicates
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

### 分析工具关键修复记录

1. **JSON 输出污染**：`print()` 信息混入 JSON stdout
   - 修复：添加 `info()` helper，`--json` 时重定向到 stderr

2. **Headers 路径错误**：`<slice>/Headers/` → 实际是 `<slice>/<libname>.framework/Headers/`
   - 修复：从 plist 的 `LibraryPath` 字段读取正确路径

3. **Runtime 符号漏检**：K/N runtime 使用 C++ anonymous namespace，nm 看到的是 mangled 名
   - 修复：添加 C++ mangling 模式：
     ```python
     r'|_ZN12_GLOBAL__N_1|__ZN12_GLOBAL__N_1'   # anonymous namespace
     r'|_ZN6kotlin|__ZN6kotlin'                  # kotlin:: namespace
     r'|_ZN5konan|__ZN5konan'                    # konan:: namespace
     ```

4. **Gradle configuration cache 报错**：helper 函数捕获了 Gradle script 对象引用
   - 修复：改为直接内联注册 task，调用 `notCompatibleWithConfigurationCache()`

---

## 构建命令

### Kotlin 模块构建
```bash
cd /Users/bytedance/codelab-android-kmp/get-started

# Foundation Debug XCFramework
./gradlew :foundation:buildIOSDebug

# Business Debug XCFramework
./gradlew :business:buildIOSDebug

# Release 版本
./gradlew :foundation:buildIOSRelease
./gradlew :business:buildIOSRelease
```

### iOS App 集成
```bash
cd iosApp
LANG=en_US.UTF-8 pod install   # 注意：必须加 LANG，否则 CocoaPods 报 encoding 错误
```

### iOS App 构建（命令行）
```bash
cd iosApp
xcodebuild \
  -workspace KMPGetStartedCodelab.xcworkspace \
  -scheme KMPGetStartedCodelab \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  build
```

### 运行到模拟器
```bash
# 安装
xcrun simctl install booted \
  $(find ~/Library/Developer/Xcode/DerivedData -name "KMPGetStartedCodelab.app" -path "*/Debug-iphonesimulator/*" | head -1)

# 启动
xcrun simctl launch booted com.example.KMPGetStartedCodelab
```

---

## 常见错误与解决

| 错误 | 原因 | 解决 |
|------|------|------|
| `pod install` 报 encoding 错误 | 终端 locale 不是 UTF-8 | 加前缀 `LANG=en_US.UTF-8` |
| `import businessKit` 找不到 | typo 写成 `bussinessKit` | 检查拼写（双 s） |
| `assembleXXXXDebugXCFramework` task 找不到 | task 名依赖 xcfName 拼接 | 用 `./gradlew tasks` 查看实际 task 名 |
| Xcode GUI Run 按钮灰色 | 没有选择真机/模拟器 | 改用命令行 xcodebuild |
| 移除旧 Build Phase 导致失败 | pbxproj 中 reference 和 definition 都要删 | 手动编辑 project.pbxproj，删除两处引用 |
| Gradle configuration cache 报错 | lambda 捕获了 Gradle project 引用 | 在 task 内直接使用 `file()` 而非外部引用 |

---

## 研究进展

- [x] 搭建 Foundation + Business 两模块 KMP 原型
- [x] 实现 TikTok 风格 CocoaPods 交付（podspec + buildIOSDebug task）
- [x] iOS App 通过 CocoaPods 集成两个 pod（`spec.dependency` 正确声明依赖）
- [x] 移除旧的 Xcode "Compile Kotlin Framework" Build Phase
- [x] App 成功运行，同时调用 foundation 和 business 模块
- [x] 符号分析：确认 2092/2626 (80%) 符号重复
- [x] 理论分析：iOS two-level namespace 解释为何运行时不崩溃
- [x] 确认 C++ mangling 是 runtime 符号检测的关键
- [ ] **Part 1**: 分析 app bundle 内 framework 符号（添加 `--app` 模式）
- [ ] **Part 2**: Swift 代码验证两套 ObjC 运行时（`NSClassFromString` 对比）
- [ ] **Part 3**: 跨框架 Kotlin 对象传递崩溃实验
- [ ] 写综合研究报告

---

*记录时间：2026-03-30*
*项目路径：`/Users/bytedance/codelab-android-kmp/get-started`*
