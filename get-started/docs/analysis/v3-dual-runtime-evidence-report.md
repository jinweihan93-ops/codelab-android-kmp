# KMP V3 双运行时问题实证报告

> 日期: 2026-03-30
> 分支: jinwei/kmp_v3_demo
> 工程: get-started/ (foundationKit + businessKit)

---

## 结论

**KMP V3 分体交付架构中存在严重的双运行时问题。** 当两个独立编译的 K/N XCFramework 同时被 iOS App 加载时：

1. **符号层面**：两个框架各自携带完整的 K/N runtime + stdlib，在 App 包中产生 **7821 个重复符号（98.2%）**
2. **GC 线程层面**：App 进程中同时运行 **4 个 K/N GC 线程**（2 组 x 2），分别属于 foundationKit 和 businessKit，直接证明两套独立 GC 在并行运行
3. **运行时层面**：iOS 进程中同时存在两套独立的 K/N 运行时实例，各自拥有独立的 GC、类型系统和 ObjC 类层次
4. **对象传递层面**：跨框架传递 Kotlin 对象导致类型不兼容 — `is` 检查失败，强制类型转换会触发 `ClassCastException` 崩溃

---

## Evidence 1: 符号重复分析

### 1a. XCFramework 级别（Release 构建）

使用 `xcframework-analyzer.py --project-config project.json` 分析 Release XCFrameworks：

| 类别 | foundationKit | businessKit | 重复数 | 重复率 |
|------|-------------|------------|--------|--------|
| K/N Runtime | 127 | 127 | **127** | **100%** |
| Kotlin Stdlib | 651 | 769 | **651** | **100%** |
| kotlinx | 4 | 4 | **4** | **100%** |
| C++ RTTI | 43 | 43 | **43** | **100%** |
| C++ symbols | 101 | 101 | **101** | **100%** |
| Kotlin User API | 15 | 58 | 15 | 100% |
| ObjC Export | 73 | 83 | - | - |
| Other | 1354 | 1529 | 1189 | 88% |
| **Total** | **2368** | **2714** | **2130** | **90%** |

**关键发现**：businessKit 的 2714 个符号中，2130 个（78.5%）与 foundationKit 完全重复。businessKit 的净增量仅 584 个符号，但却携带了完整的 K/N 运行时。

### 1b. 最终 iOS App 包级别（Debug 构建）

使用 `app-binary-analyzer.py` 分析构建后的 `.app` 包内嵌入的 framework binaries：

```
App: KMPGetStartedCodelab.app
App 路径: iosApp/build-output/Build/Products/Debug-iphonesimulator/

嵌入的 Frameworks:
  businessKit.framework   — 1983 KB, 8351 defined symbols
  foundationKit.framework — 1860 KB, 7961 defined symbols
```

| 类别 | foundationKit | businessKit | 重复数 |
|------|-------------|------------|--------|
| K/N Runtime | 368 | 368 | **368** |
| Kotlin Stdlib | 2048 | 2179 | **2048** |
| kotlinx | 32 | 32 | **32** |
| C++ RTTI | 74 | 74 | **74** |
| C++ symbols | 213 | 213 | **213** |
| Other | 5144 | 5353 | 5062 |
| **Total** | **7961** | **8351** | **7821 (98.2%)** |

**结论**：最终 App 产物中，两个动态框架确实同时携带了两套完整的 K/N 运行时。iOS 动态链接器的 two-level namespace 机制保证了它们不会冲突（各自保持在自己的命名空间内），但这也意味着**内存中同时运行着两套完全独立的 K/N runtime**。

---

## Evidence 2: 两套 GC 线程并行运行

使用 `sample` 命令采样 App 进程（PID 18720）的线程列表：

```
$ sample 18720 1
$ grep "Thread_" sample_output.txt

Thread_90456486   com.apple.main-thread
Thread_90456510
Thread_90456511
Thread_90456513   com.apple.uikit.eventfetch-thread
Thread_90456523
Thread_90456540   GC Timer thread        ← foundationKit runtime
Thread_90456541   Main GC Thread         ← foundationKit runtime
Thread_90456542   GC Timer thread        ← businessKit runtime
Thread_90456543   Main GC Thread         ← businessKit runtime
```

**4 个 GC 线程，2 组，分属两个框架**：

| 线程名 | 所属 dylib | K/N 函数 |
|--------|-----------|---------|
| `GC Timer thread` (#90456540) | `foundationKit` | `kotlin::RepeatedTimer<kotlin::steady_clock>::Run<...GCSchedulerDataAdaptive...>` |
| `Main GC Thread` (#90456541) | `foundationKit` | `kotlin::gc::internal::MainGCThread::body()` |
| `GC Timer thread` (#90456542) | `businessKit` | `kotlin::RepeatedTimer<kotlin::steady_clock>::Run<...GCSchedulerDataAdaptive...>` |
| `Main GC Thread` (#90456543) | `businessKit` | `kotlin::gc::internal::MainGCThread::body()` |

**关键证据**：
- 每个 K/N 运行时启动时都会创建自己的 GC 线程（一个 Timer 调度线程 + 一个主 GC 执行线程）
- `sample` 输出中明确标注了每个线程的栈帧来自 `(in foundationKit)` 还是 `(in businessKit)`
- 两套 GC 完全独立运行，各自管理各自框架创建的 Kotlin 对象的内存
- 这意味着 Foundation 创建的对象对 Business 的 GC 是"不可见"的，反之亦然

---

## Evidence 3: 两套独立运行时的 ObjC 层证据

### 3a. 独立的 ObjC 类层次

每个 K/N 框架都会生成自己的 ObjC root class：

- `foundationKit` → `FoundationKitBase`（K/N Runtime A 的根类）
- `businessKit` → `BusinessKitBase`（K/N Runtime B 的根类）

两者是 **完全不同的 ObjC 类**，各自继承自 `NSObject`：

```swift
let foundationBase = NSClassFromString("FoundationKitBase")  // ≠ nil
let businessBase = NSClassFromString("BusinessKitBase")      // ≠ nil
foundationBase === businessBase  // → false
```

### 3b. 不同的动态库镜像

使用 `dladdr` 检查类所在的 dylib image：

```
Foundation class image: foundationKit
Business class image:   businessKit
Different images: true
```

两个类分别来自不同的动态库文件，证明它们运行在独立的运行时上下文中。

### 3c. 重复的 ObjC 类注册

使用 `objc_copyClassList` 枚举所有注册的 ObjC 类：

```
FoundationKit* classes (26):
  FoundationKitBase, FoundationKitBoolean, FoundationKitByte,
  FoundationKitDouble, FoundationKitFloat, FoundationKitInt,
  FoundationKitLong, FoundationKitNumber, FoundationKitShort,
  FoundationKitKListAsNSArray, FoundationKitKMapAsNSDictionary,
  FoundationKitKSetAsNSSet, FoundationKitMutableSet,
  FoundationKitSharedData, FoundationKitPlatform_iosKt, ...

BusinessKit* classes (31):
  BusinessKitBase, BusinessKitBoolean, BusinessKitByte,
  BusinessKitDouble, BusinessKitFloat, BusinessKitInt,
  BusinessKitLong, BusinessKitNumber, BusinessKitShort,
  BusinessKitKListAsNSArray, BusinessKitKMapAsNSDictionary,
  BusinessKitKSetAsNSSet, BusinessKitMutableSet,
  BusinessKitSharedData, BusinessKitSharedDataProcessor,
  BusinessKitUserService, BusinessKitUser, ...
```

**核心证据**：`FoundationKitBase` / `FoundationKitNumber` / `FoundationKitKListAsNSArray` 等都是 K/N runtime 的基础 ObjC 类。BusinessKit 注册了一套完全一样的类但带 `BusinessKit` 前缀。这直接证明了**两套独立的 K/N 运行时各自向 ObjC runtime 注册了自己的类层次**。

---

## Evidence 4: 跨框架 Kotlin 对象传递不兼容

### 实验设计

1. **Foundation 模块**定义 `data class SharedData(val id: Int, val message: String)` 和工厂函数 `createSharedData()`
2. **Business 模块**定义 `SharedDataProcessor`：
   - `createLocalSharedData()` — 用 Business 运行时创建 SharedData
   - `validateAsSharedData(data: Any): Boolean` — Kotlin `is SharedData` 检查
   - `forceProcessAny(data: Any)` — Kotlin `as SharedData` 强制转换
3. Business 通过 `export(project(":foundation"))` + `api` 依赖，在 ObjC header 中导出 Foundation 的类型

### 实验结果

**Swift 类型系统层面**：

```swift
let fromFoundation: Any = foundationKit.SharedDataKt.createSharedData(id: 1, message: "test")
let fromBusiness: Any = processor.createLocalSharedData(id: 2, message: "test")

type(of: fromFoundation)  // → FoundationKitSharedData
type(of: fromBusiness)    // → BusinessKitSharedData
// 两者是完全不同的 Swift/ObjC 类型！
```

**Kotlin `is` 检查**：

```swift
processor.validateAsSharedData(data: fromBusiness)    // → true  ✅
processor.validateAsSharedData(data: fromFoundation)  // → false ❌
```

同样的 Kotlin `data class SharedData`，在两个框架的运行时中被视为**不同的类型**。Business 运行时的 `is SharedData` 检查使用 Business 运行时的类型元数据指针，与 Foundation 运行时的类型元数据指针不同。

**Kotlin `as` 强制转换**：

```swift
processor.forceProcessAny(data: fromBusiness)    // → "Force-processed: id=2, msg='test'" ✅
processor.forceProcessAny(data: fromFoundation)  // → ClassCastException → crash ❌
```

将 Foundation 运行时创建的 SharedData 对象强制转换为 Business 运行时的 SharedData 类型，触发 `ClassCastException`。在 K/N 中这是一个不可捕获的 `NSException`，导致 App 崩溃。

### 根因分析

```
Foundation Runtime                    Business Runtime
┌──────────────────┐                 ┌──────────────────┐
│ SharedData class  │                 │ SharedData class  │
│ typeInfo: 0xA100  │                 │ typeInfo: 0xB200  │
│ GC: instance A    │                 │ GC: instance B    │
└──────────────────┘                 └──────────────────┘
        │                                     │
        ▼                                     ▼
  obj.typeInfo == 0xA100               obj.typeInfo == 0xB200

  Business 的 `is SharedData` 检查:
    obj.typeInfo == 0xB200 ?
    Foundation obj: 0xA100 ≠ 0xB200 → false
    Business obj:   0xB200 == 0xB200 → true
```

每个 K/N 运行时为同一个 Kotlin 类生成了不同的 `typeInfo` 指针。跨运行时的对象携带的是"对方"的 typeInfo，在"本方"的类型检查中不被识别。

---

## 影响与结论

### 包体浪费

| 指标 | 当前值 | 理想值（瘦框架） | 浪费比例 |
|------|--------|-----------------|---------|
| businessKit binary | 723 KB (release) | ~60 KB | **91%** |
| App 总 framework 体积 | 1343 KB | ~680 KB | **49%** |
| 重复符号数 | 2130 (release) | 0 | 100% |

### 运行时隔离

| 问题 | 状态 | 证据来源 |
|------|------|---------|
| 两套 GC 独立运行 | 已确认 | `sample` 采样: 4 个 GC 线程 (Evidence 2) |
| 两套类型系统独立 | 已确认 | ObjC 类层次对比 (Evidence 3a) |
| 两套 ObjC 类层次共存 | 已确认 | `objc_copyClassList` 枚举 (Evidence 3c) |
| 跨框架 Kotlin 对象不兼容 | 已确认 | `is SharedData` 返回 false (Evidence 4) |
| 跨框架强制转换崩溃 | 已确认 | `as SharedData` → ClassCastException (Evidence 4) |

### 对 V3 架构的影响

1. **当前模型不可行**：如果业务模块需要传递 Kotlin 对象给基础模块（或反过来），当前的分体 XCFramework 架构会导致崩溃
2. **仅适用于完全隔离的场景**：如果两个框架之间完全不传递 Kotlin 对象（只通过 Swift/ObjC 原生类型桥接），当前架构勉强可用，但包体浪费严重
3. **需要 thin framework 方案**：JetBrains 已确认 1-2 年内不会原生支持（KMT-2364），Q2 需要评估以下技术路径：
   - Path A: 构建后脚本剥离重复符号
   - Path B: weak framework linking
   - Path C: K/N 编译器自定义 flags

---

## 复现步骤

```bash
cd get-started

# 1. 构建 XCFrameworks
./gradlew :foundation:buildIOSDebug :business:buildIOSDebug

# 2. XCFramework 级别符号分析
cd xcframework_viz
python3 xcframework-analyzer.py --project-config project.json

# 3. 构建 iOS App
cd ../iosApp
LANG=en_US.UTF-8 pod install
xcodebuild build -workspace KMPGetStartedCodelab.xcworkspace \
  -scheme KMPGetStartedCodelab -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath ./build-output

# 4. App 包符号分析
cd ../xcframework_viz
python3 app-binary-analyzer.py ../iosApp/build-output/Build/Products/Debug-iphonesimulator/KMPGetStartedCodelab.app

# 5. 运行 App，点击 "Run All Tests" 查看运行时证据
```

---

## 文件清单

| 文件 | 用途 |
|------|------|
| `foundation/src/commonMain/.../SharedData.kt` | 定义跨框架测试用 data class |
| `business/src/commonMain/.../SharedDataProcessor.kt` | 类型检查 + 强转测试函数 |
| `business/build.gradle.kts` | 添加 `export(project(":foundation"))` + `api` 依赖 |
| `iosApp/.../RuntimeDuplicateTest.swift` | ObjC runtime 级别的双运行时检测 |
| `iosApp/.../ContentView.swift` | 集成所有测试的 UI |
| `xcframework_viz/app-binary-analyzer.py` | App 包内 framework 符号分析工具 |
| `xcframework_viz/xcframework-analyzer.py` | XCFramework 符号分析工具（已有） |
