# K/N XCFramework 符号组成深度分析报告

> 日期：2026-03-30
> 工程：`codelab-android-kmp/get-started`
> 产物：`foundation/build/XCFrameworks/release/foundationKit.xcframework`
> Kotlin 版本：2.2.0

---

## 一、背景

KMP V3 分体交付架构（Foundation + Shell 模式）的核心挑战是：K/N 默认生成**自包含（self-contained）framework**，每个 XCFramework 都会独立 embed 完整的 runtime、stdlib、kotlinx 依赖。多个 framework 同时加载会导致**符号冲突 / duplicate symbol 链接错误**。

本次分析目标：
1. 精确测量 `foundationKit.xcframework` 的符号组成
2. 验证 runtime 检测是否准确（修复 false negative）
3. 为 V3 拆分方案建立基线数据

---

## 二、产物结构

```
foundationKit.xcframework/
├── Info.plist
├── ios-arm64/
│   ├── dSYMs/
│   └── foundationKit.framework/
│       ├── foundationKit          ← Mach-O binary (arm64)
│       ├── Headers/foundationKit.h
│       ├── Modules/module.modulemap
│       └── Info.plist
└── ios-arm64_x86_64-simulator/
    └── foundationKit.framework/
        └── foundationKit          ← fat binary (arm64 + x86_64)
```

**关键：** simulator slice 是 fat binary（两架构合一），设备 slice 是单 arm64。

---

## 三、符号组成（Release / arm64 slice）

| 类别 | Defined | Undefined | 说明 |
|------|--------:|----------:|------|
| **Kotlin/Native Runtime** | **127** | 0 | GC、内存管理、线程挂起等 |
| **Kotlin Stdlib** | **651** | 0 | kfun:kotlin.* 等标准库符号 |
| kotlinx libraries | 4 | 0 | kotlinx 核心桥接 |
| ObjC Export | 69 | 25 | class/metaclass/ivar |
| Kotlin User API | 1 | 0 | 业务代码（仅 `platform()` 函数） |
| C++ RTTI | 43 | 10 | typeinfo / vtable（K/N runtime 内部类） |
| C++ symbols | 101 | 41 | libc++ 静态链接的模板实例 + K/N C++ 实现 |
| **Other** | **1320** | 119 | GCC 异常表、ObjC method selectors、C 接口 |
| **Total** | **2316** | **195** | |

**健康状态：**
```
⚠️  Kotlin/Native runtime EMBEDDED  (127 symbols)
⚠️  Kotlin Stdlib EMBEDDED  (651 symbols)
```

---

## 四、符号分类详解

### 4.1 Kotlin/Native Runtime（127 个）

K/N 运行时的 C++ 实现，分三类命名模式：

**匿名命名空间（anonymous namespace）：**
```
(anonymous namespace)::initRuntime()
(anonymous namespace)::runtimeState
(anonymous namespace)::gSafePointActivator
(anonymous namespace)::safePointActionImpl(kotlin::mm::ThreadData&)
(anonymous namespace)::TerminateHandler::kotlinHandler()
(anonymous namespace)::processUnhandledException(ObjHeader*)
(anonymous namespace)::Kotlin_deinitRuntimeCallback(void*)
```
这些符号 mangle 为 `__ZN12_GLOBAL__N_1...`，**不含 "kotlin" 字符串**，是之前 false negative 的根本原因。

**kotlin:: 命名空间（内存管理 / GC）：**
```
kotlin::mm::ThreadSuspensionData::suspendIfRequested()
kotlin::mm::RequestThreadsSuspension(char const*)
kotlin::mm::ResumeThreads()
kotlin::gc::internal::MainGCThread::PerformFullGC(long long)  ← 这就是 GC 的核心
kotlin::gc::mark::ParallelMark::parallelMark(...)
kotlin::alloc::CustomAllocator::Allocate(kotlin::alloc::AllocationSize)
kotlin::alloc::NextFitPage::TryAllocate(unsigned int)
```

**ObjC 桥接层（C 符号）：**
```
blockToKotlinImp
SwiftObject_toKotlinImp
boxedBooleanToKotlinImp
convertKotlinObjectToRetained
getOrCreateClass / getOrCreateTypeInfo / setAssociatedTypeInfo
Kotlin_ObjCExport_refToRetainedObjC_slowpath
```

> **注意：** `kotlin::gc::GC::collect()` 不出现为独立符号——它是 thin wrapper，编译时被内联进 `MainGCThread::PerformFullGC`。

### 4.2 Kotlin Stdlib（651 个）

全部为 `kfun:kotlin.*` 形式，包含：
- `kfun:kotlin.collections.*` — List、Map、Set 的实现
- `kfun:kotlin.text.*` — 字符串处理
- `kfun:kotlin.coroutines.*` — 协程基础（即便没直接用 coroutines）
- `ktypew:kotlin.*` / `kclass:kotlin.*` — 类型信息

### 4.3 C++ symbols（101 个）

主要来自 **libc++ 静态链接**：
```
std::__1::__function::__func<...>::operator()()
std::__1::vector<...>::__throw_length_error()
std::__1::__thread_proxy<...>
std::__1::unordered_map<...>::~unordered_map()
```

K/N 不依赖 `/usr/lib/libc++.1.dylib`（虽然 linked libs 里有），而是把 STL 模板实例直接静态打入 binary。这意味着 **V3 中 libc++ 模板实例也会在多个 framework 中重复存在**。

### 4.4 Other（1320 个）

主要成分：
- **`GCC_except_table*`** — C++ 异常处理表（数量最多，每个 try/catch block 产生一个）
- **ObjC method selectors** — `initialize]`, `load]`, `allocWithZone:]` 等
- **C 接口函数** — 不含 `_` 前缀的 runtime C API

---

## 五、Debug vs Release 对比

| 维度 | Release (arm64) | Debug (arm64) |
|------|:-----------:|:----------:|
| Binary size | **618 KB** | 3.1 MB（**5×**） |
| Total defined symbols | 2316 | 7912（**3.4×**） |
| Kotlin/Native Runtime | 127 | 127（相同）|
| Kotlin Stdlib | 651 | 651（相同）|
| Debug-only symbols | — | ~5596（未 strip 的函数名、调试符号） |

Debug binary 体积膨胀主要原因：LLVM debug info（DWARF）未 strip，未做 dead code elimination。Runtime/stdlib 数量相同，说明即使 debug 模式也不会额外增加 runtime 内容。

---

## 六、ObjC 导出接口（Public API）

`foundationKit.h` 导出的 ObjC 类（24 个），Swift 侧可见：

```objc
// 业务 API（唯一的用户代码）
@interface FoundationKitPlatform_iosKt : FoundationKitBase
+ (NSString *)platform __attribute__((swift_name("platform()")));
@end

// K/N 基础类型包装（runtime 提供，非业务代码）
@interface FoundationKitBase : NSObject        // swift_name: KotlinBase
@interface FoundationKitNumber : NSNumber      // swift_name: KotlinNumber
@interface FoundationKitByte : FoundationKitNumber
@interface FoundationKitBoolean : FoundationKitNumber
// ... Int/Long/Float/Double/UByte/UShort/UInt/ULong/ULong/Short
@interface FoundationKitMutableSet<T> : NSMutableSet<T>
@interface FoundationKitMutableDictionary<K,V> : NSMutableDictionary<K,V>
// 集合桥接
@interface FoundationKitKListAsNSArray<T>
@interface FoundationKitKMapAsNSDictionary<K,V>
@interface FoundationKitKMutableListAsNSMutableArray<T>
@interface FoundationKitKSetAsNSSet<T>
@interface FoundationKitKIteratorAsNSEnumerator<T>
// 内部桥接
@interface FoundationKitKotlinObjCWeakReference
@interface FoundationKitKotlinObjectHolder
@interface FoundationKitKotlinSelectorsHolder
```

**观察：** 24 个导出类中，23 个是 K/N runtime 强制生成的基础类型桥接（不可移除），1 个是真正的业务 API（`Platform_iosKt`）。这说明即使业务极简，ObjC 层的"底座"开销也固定存在。

---

## 七、关键发现：Runtime 检测 False Negative 修复

**问题根因：**

K/N runtime 的 C++ 实现大量使用 `anonymous namespace`，这些符号 mangle 后形如：
```
__ZN12_GLOBAL__N_112runtimeStateE       → (anonymous namespace)::runtimeState
__ZN12_GLOBAL__N_119gSafePointActivatorE → (anonymous namespace)::gSafePointActivator
```

原始 regex 只匹配明文 `kotlin_native_runtime`、`_konan_` 等，**完全漏掉了 anonymous namespace 和 `kotlin::` namespace 的 C++ mangled 符号**，导致 framework 明明 embed 了 127 个 runtime 符号，却报告 `✓ NOT embedded`。

**修复方案（加入 C++ mangled prefix 匹配）：**
```python
r'|_ZN12_GLOBAL__N_1|__ZN12_GLOBAL__N_1'   # anonymous namespace
r'|_ZN6kotlin|__ZN6kotlin'                  # kotlin:: namespace
r'|_ZN5konan|__ZN5konan'                    # konan:: namespace
```

修复后正确输出：`⚠️  Kotlin/Native runtime EMBEDDED (127 symbols)`

---

## 八、V3 架构基线数据

当前单模块（Foundation 持有一切）的数据：

```
Foundation XCFramework (Release, arm64):
  Binary size      : 618 KB
  Runtime symbols  : 127   ← 只能出现在 Foundation
  Stdlib symbols   : 651   ← 只能出现在 Foundation
  Business API     : 1     ← platform() 函数
  ObjC base types  : 24 classes (23 runtime + 1 business)
```

**V3 目标状态（Business framework）：**
```
Business XCFramework (Release, arm64):
  Binary size      : << 618 KB（理想 < 100 KB）
  Runtime symbols  : 0     ← 不 embed，依赖 Foundation 提供
  Stdlib symbols   : 0     ← 不 embed，依赖 Foundation 提供
  Business API     : N     ← 只有业务逻辑
```

**当前实际状态（无优化）：**
```
Business XCFramework (Release, arm64) [预测]:
  Runtime symbols  : ~127  ← 重复 embed！
  Stdlib symbols   : ~651  ← 重复 embed！
  → 触发 "⚠️ N symbols defined in multiple frameworks!"
```

这个重复就是 V3 需要解决的核心问题。

---

## 九、工具使用备忘

```bash
cd xcframework_viz

# 基础分析
python3 xcframework-analyzer.py --project-config project.json

# 符号过滤（runtime 详情）
python3 xcframework-analyzer.py --project-config project.json \
  --filter "^(__ZN12_GLOBAL__N_1|__ZN6kotlin|__ZN5konan)" 2>/dev/null \
  | grep "\[DEF\]" | awk '{print $NF}' | c++filt | head -30

# ObjC 接口
python3 xcframework-analyzer.py foundationKit.xcframework --headers

# Debug vs Release 对比
python3 xcframework-analyzer.py release/foundationKit.xcframework \
  --compare debug/foundationKit.xcframework

# JSON 输出（CI 集成）
python3 xcframework-analyzer.py --project-config project.json --json 2>/dev/null \
  | python3 -c "import json,sys; d=json.load(sys.stdin); \
    print(d['frameworks'][0]['slices'][0]['has_kotlin_runtime'])"

# 自动发现
python3 xcframework-analyzer.py --project ./build/XCFrameworks/release/ \
  --save-project project.json
```

---

## 十、下一步

1. **搭建 `business` 模块**，构建 `businessKit.xcframework`
2. **project 模式双框架分析**，实测 duplicate symbol 数量和类别
3. **评估拆分路径：**
   - 路径 A：post-build 脚本 strip runtime symbols from business binary
   - 路径 B：link 阶段介入，business framework 以 `-weak_framework foundationKit` 形式链接，不 embed runtime
   - 路径 C：K/N 编译器参数（`-Xstatic-framework` 等）+ 自定义 linker script
