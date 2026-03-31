# KMP V3 分体交付：XCFramework 符号重复问题实测报告

> 日期：2026-03-30
> 工程：`codelab-android-kmp/get-started`
> Kotlin 版本：2.2.0
> 测试产物：`foundationKit.xcframework` + `businessKit.xcframework`

---

## 一、背景与目标

KMP V3 分体交付架构的核心设想是：将单体 KMP Framework 拆分为多个独立交付的 XCFramework——
- **Foundation**：持有 K/N runtime、stdlib，提供基础能力
- **Business（Shell）**：仅包含业务逻辑，依赖 Foundation，不重复打包 runtime

本报告通过搭建原型工程，**实测验证** K/N 默认行为下的符号重复问题，量化 V3 需要解决的技术债规模。

---

## 二、测试环境搭建

### 工程结构

```
get-started/
├── foundation/          # KMP 模块，产出 foundationKit.xcframework
│   └── src/commonMain/
│       └── Platform.kt  # expect fun platform(): String
└── business/            # KMP 模块，产出 businessKit.xcframework
    └── src/commonMain/
        ├── model/
        │   ├── User.kt       # data class User(id, name, platform)
        │   └── FeedItem.kt   # data class FeedItem(id, title, author)
        ├── UserService.kt    # 调用 foundation.platform()
        └── FeedService.kt    # 生成 List<FeedItem>
```

### 依赖关系

```
:business  ──implementation──▶  :foundation
```

- `UserService.kt` 显式调用 `:foundation` 的 `platform()` 函数
- 两个模块各自独立构建为 XCFramework，模拟分体交付场景

### 构建命令

```bash
./gradlew :foundation:assembleFoundationKitReleaseXCFramework
./gradlew :business:assembleBusinessKitReleaseXCFramework
```

---

## 三、各 Framework 符号组成（Release / arm64）

### 3.1 foundationKit（基座）

| 类别 | Defined | 说明 |
|------|--------:|------|
| Kotlin/Native Runtime | **127** | GC、内存管理、线程挂起、ObjC 桥接层 |
| Kotlin Stdlib | **651** | kfun:kotlin.* 标准库 |
| kotlinx libraries | 4 | kotlinx 核心桥接 |
| ObjC Export | 69 | 24 个 class + metaclass + ivar |
| Kotlin User API | 1 | `platform()` 函数 |
| C++ RTTI | 43 | typeinfo / vtable（runtime 内部类） |
| C++ symbols | 101 | 静态链接的 libc++ 模板实例 |
| Other | 1320 | GCC 异常表、ObjC method selector 等 |
| **Total defined** | **2316** | |
| **Binary size** | **618 KB** | |

### 3.2 businessKit（业务层）

| 类别 | Defined | 说明 |
|------|--------:|------|
| Kotlin/Native Runtime | **127** | 与 Foundation 完全相同 ← **重复** |
| Kotlin Stdlib | **769** | 比 Foundation 多 118 个（data class 方法） |
| kotlinx libraries | 4 | 与 Foundation 完全相同 ← **重复** |
| ObjC Export | 75 | 27 个 class（含 User/FeedItem/UserService/FeedService） |
| Kotlin User API | **35** | 业务方法（equals/hashCode/copy/toString + Service 方法）|
| C++ RTTI | 43 | 与 Foundation 完全相同 ← **重复** |
| C++ symbols | 101 | 与 Foundation 完全相同 ← **重复** |
| Other | 1472 | 比 Foundation 多 152 个 |
| **Total defined** | **2626** | |
| **Binary size** | **702 KB** | 比 Foundation 还大 84 KB！ |

### 3.3 两个 Framework 对比

```
                      foundationKit    businessKit    差值
Binary size              618 KB          702 KB      +84 KB
Defined symbols            2316            2626       +310
  └─ Runtime                127             127         0   ← 100% 重复
  └─ Stdlib                 651             769       +118  ← 651 个重复
  └─ kotlinx                  4               4         0   ← 100% 重复
  └─ User API                 1              35        +34  ← 真正的业务增量
  └─ ObjC Export             69              75        +6
  └─ C++ RTTI                43              43         0   ← 100% 重复
  └─ C++ symbols            101             101         0   ← 100% 重复
  └─ Other                 1320            1472       +152
```

**关键发现：** businessKit 的 702 KB 中，仅约 50–80 KB 是真正的业务代码增量，其余 ~620 KB 是 Foundation 已经持有的重复内容。

---

## 四、跨 Framework 重复符号分析

```
⚠️  2092 symbols defined in multiple frameworks!

  Category                  Dup symbols    占 Foundation 总符号比
  ─────────────────────────────────────────────────────────────
  Kotlin/Native Runtime         127             100%
  Kotlin Stdlib                 651             100%
  kotlinx libraries               4             100%
  C++ RTTI                       43             100%
  C++ symbols                   101             100%
  Other                        1166              88%
  ─────────────────────────────────────────────────────────────
  Total                        2092
```

两个 framework 在同一 iOS App 中同时加载时：
- **2092 个符号**同时存在于两个动态库的 `__TEXT` 段
- Runtime / Stdlib / RTTI / C++ symbols 全部 100% 重复
- Other 类（GCC 异常表、ObjC method selector）88% 重复

### Business 的"净增量"

```
businessKit 总符号      2626
 - 与 Foundation 重复  2092
 ─────────────────────────
 仅 business 持有        534
   └─ Kotlin User API     35  （UserService/FeedService/User/FeedItem 的方法）
   └─ ObjC Export         ~6  （业务类的 ObjC 桥接）
   └─ Other             ~493  （业务逻辑的异常表、selector 等）
```

**理想的 thin businessKit 应仅含这 534 个符号（估计 50–80 KB）。**
当前实际 702 KB，"浪费" 约 620 KB 在重复内容上。

---

## 五、V3 问题的实际影响

### 5.1 包大小浪费

| 场景 | Foundation | Business（当前） | Business（thin 目标） |
|------|:----------:|:---------------:|:-------------------:|
| arm64 binary | 618 KB | 702 KB | ~60 KB |
| 用户下载包净增 | - | **702 KB** | **60 KB** |

若 App 最终集成 N 个 Shell framework，每个都浪费 ~620 KB，包大小线性膨胀。

### 5.2 符号冲突风险

K/N runtime 采用 **global mutable state**（`runtimeState`、`gSafePointActivator`、GC 线程等），多个 runtime 实例同时存在会导致：

- **GC 状态不一致**：两个 GC 线程同时运行，各自管理独立的 heap，对象跨 framework 传递时引用计数失效
- **运行时初始化竞争**：`initRuntime()` 被调用两次，`TerminateHandler` 注册两次
- **ObjC 类注册冲突**：`BusinessKitBase` 和 `FoundationKitBase` 各自注册，但共享同一套 K/N ObjC runtime 机制，可能产生 `+initialize` 互干扰

iOS dynamic linker 对 duplicate symbol 的处理是**取先加载者**——哪个 framework 先被 `dlopen`，其 runtime 实例就"胜出"，另一个 framework 的 runtime 符号被忽略。这在 debug 构建下会产生 linker 警告，release 下行为取决于加载顺序，**不可预测**。

### 5.3 ObjC 命名空间污染

两个 framework 各自生成一套完整的 ObjC 类体系：

```objc
// Foundation 提供
@interface FoundationKitBase : NSObject
@interface FoundationKitNumber : NSNumber
// ... 24 个类

// Business 再次生成（完全独立的类层级！）
@interface BusinessKitBase : NSObject      ← 不是 FoundationKitBase 的子类
@interface BusinessKitNumber : NSNumber    ← 与 FoundationKitNumber 无关
// ... 27 个类
```

这意味着 iOS 侧无法直接将 `FoundationKitUser` 传给接收 `BusinessKitBase` 参数的方法，需要手动桥接，**破坏了统一 API 体验**。

---

## 六、V3 技术路径评估

基于以上数据，对三条技术路径的可行性做初步评估：

### 路径 A：post-build strip 脚本

**方案：** 构建完 businessKit 后，用脚本从 binary 中移除 runtime/stdlib 符号，仅保留业务符号。

```bash
# 概念验证（伪代码）
nm businessKit | grep runtime_symbols | generate_linker_script
ld -r -exported_symbols_list business_only.txt \
   businessKit.o -o businessKit_thin.o
```

**优点：** 不修改 Gradle/K/N 构建流程，灵活
**风险：**
- K/N binary 不是普通静态库，符号之间有隐式依赖（异常表指向 runtime 函数）
- strip 后 binary 在 runtime 初始化前可能崩溃
- 需要深入了解 K/N ABI 才能安全操作

**结论：** 技术上可行，但实现复杂，需要逐一验证依赖关系

### 路径 B：链接阶段介入（`-weak_framework`）

**方案：** 让 businessKit 以 weak 方式链接 foundationKit，依赖 Foundation 的 runtime 符号，不自行 embed。

```kotlin
// build.gradle.kts
iosArm64 {
    binaries.framework {
        linkerOpts("-weak_framework", "foundationKit")
        // 或 freeCompilerArgs += listOf("-Xlinker", "-weak_framework", "-Xlinker", "foundationKit")
    }
}
```

**优点：** 符合 Apple 平台惯例，linker 天然支持
**风险：**
- K/N framework 不像系统 framework 有稳定 ABI，weak link 可能产生符号解析问题
- business 的 runtime 入口（`initRuntime`）仍需被调用，需要 Foundation 提前初始化
- JetBrains 未正式支持此用法，行为不确定

**结论：** 原理上最优雅，但需要验证 K/N 的 runtime 初始化顺序假设

### 路径 C：K/N 编译器 flag（`-Xstatic-framework` 等）

**方案：** 探索 K/N 现有的 experimental flag，控制哪些内容被打入 framework。

已知相关 flag：
- `-Xstatic-framework`：生成静态 framework（问题转移为链接时冲突）
- `-Xpartial-linkage`：实验性，允许 framework 包含 undefined symbol（2.x 新增）

**优点：** 最"官方"的路径，若成功则维护成本最低
**风险：**
- `Xpartial-linkage` 目前文档极少，行为未经验证
- JetBrains 明确表示 1–2 年内不原生支持 thin framework（KMT-2364）
- experimental flag 可能随版本变化

**结论：** 值得作为路径 B 的补充实验，风险较高

---

## 七、量化总结

| 指标 | 当前（默认行为） | V3 目标（thin business） | 节约 |
|------|:--------------:|:---------------------:|:----:|
| businessKit binary size | 702 KB | ~60 KB | **91%** |
| businessKit defined symbols | 2626 | ~534 | **80%** |
| 跨 framework 重复符号 | **2092** | 0 | **100%** |
| runtime 冲突风险 | 高（不可预测） | 无 | ✓ |
| ObjC 类体系统一 | 否（各自独立） | 可复用 Foundation 类 | ✓ |

---

## 八、下一步行动

1. **Q2 实验 1（路径 B）**：对 businessKit 加 `-weak_framework foundationKit` linker flag，观察构建产物和运行时行为
2. **Q2 实验 2（路径 A）**：编写 post-build strip 脚本，验证最小可运行 business binary 的边界
3. **iOS 集成验证**：将 foundationKit + businessKit 同时集成进 iosApp Xcode 工程，复现 duplicate symbol linker 警告，建立完整的 end-to-end 测试环境
4. **指标持续追踪**：每次 Kotlin 升级后重新运行分析，监控 runtime 符号数量变化
