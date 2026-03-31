# KMP V3 分体交付：运行时共享路径探索报告

> 在 [v3-dual-runtime-evidence-report.md](./v3-dual-runtime-evidence-report.md) 确认双运行时问题之后，本报告记录了所有尝试解决该问题的技术路径及其结论。
>
> **核心目标**：两个独立交付的 XCFramework（foundationKit + businessKit）共享同一套 K/N 运行时，避免双 GC 线程和跨框架对象不兼容问题。

---

## 背景：为什么这很难？

K/N 的编译模型决定了问题的根本难度：

1. **LTO 编译**：K/N 将 Kotlin IR 和 runtime.bc 通过 LLVM LTO（链接时优化）合并编译，runtime 代码直接嵌入最终 binary
2. **直接分支调用**：Kotlin 代码调用 runtime 函数（如 `_IsInstance`、`_CallInitGlobalPossiblyLock`）使用的是 **PC-relative 直接 bl 指令**，不通过 GOT/stub
3. **iOS 二级命名空间**：每个 framework 有独立符号命名空间，`businessKit!_IsInstance` ≠ `foundationKit!_IsInstance`
4. **Local 符号**：大部分 runtime 函数编译后为 `t`（local），不对外导出，其他 dylib 无法引用

这四点共同构成了"运行时隔离"的根本原因。

---

## 各路径探索结果

### Path A：构建后剥离重复符号 ❌

**思路**：构建完成后，用 `strip` 或类似工具去除 businessKit 中的 runtime 符号。

**验证结果**：
- K/N 不产生中间 `.o` 文件（直接从 Kotlin IR → Mach-O binary）
- `strip` 只能删除符号表条目，**无法删除代码段中的实际机器码**
- `strip -R` 期望精确符号名，且删除符号表不影响代码执行
- runtime 代码仍然存在于 binary 中，只是符号表项消失

**结论**：不可行。符号表和代码段是两层，strip 只能操作符号表。

---

### Path B：`isStatic = true` 静态框架 ❌

**思路**：将 businessKit 编译为静态 framework（`.a` 文件），App 链接时静态合并，理论上链接器可去重。

**验证结果（实测）**：
```
# App 内 sample 采样结果（Path B 构建后）
Thread: GC Timer thread   ← foundationKit runtime
Thread: Main GC Thread    ← foundationKit runtime
Thread: GC Timer thread   ← businessKit runtime (仍然独立！)
Thread: Main GC Thread    ← businessKit runtime
```
- `isStatic = true` 时，K/N 产出 `.a` 归档文件，内含所有 bitcode object
- `.a` 中的 runtime object 以 `t`（local）符号写入
- iOS 链接器对 local 符号无法跨 `.a` 去重（每个 `.a` 内的 local 符号相互独立）
- 结果：App binary 中仍然包含两套完整的 runtime 代码

**结论**：不可行。`t` (local) 符号无法被 static linker 去重。

---

### Path C：重链接中间产物 ❌

**思路**：获取 businessKit 的中间 `.o` 文件，手动重链接时去除 runtime 部分。

**验证结果**：
- K/N 的编译流水线：`Kotlin IR → LLVM IR → (LTO + runtime.bc 合并) → Mach-O binary`
- **没有中间 `.o` 文件**产生，K/N 直接输出最终 binary
- 无法在编译流程中"拦截"并重排 runtime

**结论**：不可行。K/N 不产生可重链接的中间产物。

---

### Path D：合并为单个 XCFramework（Umbrella 模式） ✋ 用户否决

**思路**：在 businessKit 的 build.gradle.kts 中 `export(project(":foundation"))`，让 businessKit 成为包含 foundationKit 所有类型的 umbrella framework。App 只需一个 pod。

**技术可行性**：完全可行，已验证：
- businessKit 单个 pod 中包含所有 foundation 类型
- ObjC header 正确 export 所有类型
- 只加载一套运行时

**用户决策**：
> "两个模块合并为单个 XCFramework 这个方案不用试了，我们要的是拆分，不是合并，合并的话不用测肯定可行，因为 TikTok 现在就是这么干的"

**结论**：技术可行，但不符合"分体交付"架构目标。TikTok 当前就是这个模式。

---

### Path E：K/N 编译器参数 / 链接器选项 ❌

这是探索最深入的一条路。

#### E1：`-nostdlib` 标志

**验证**：
```bash
konanc -target ios_simulator_arm64 -produce framework -nostdlib ...
```
结果：symbol 数量 **4215（与不加相同）**，runtime 完全未减少。仅 ObjC bridge class 有差异（`TestfwBase` 等 ObjC 类消失），但 K/N 运行时不受影响。

**结论**：`-nostdlib` 不影响 runtime 嵌入。

#### E2：`-Xruntime=<path>` 指向空文件

**验证**：
```bash
konanc -target ios_simulator_arm64 -produce framework -Xruntime=/dev/null ...
```
结果：
```
error: compilation failed: file too small to contain bitcode header
exception: java.lang.Error: file too small to contain bitcode header
    at Runtime.<init>(Runtime.kt:23)
```

**结论**：`-Xruntime` 必须提供合法的 LLVM bitcode 文件，且 K/N 会将其 LTO-merge 进 binary。无法通过此标志"跳过" runtime 嵌入。

#### E3：`-linker-options "-framework foundationKit"` — 添加框架依赖

**验证**：成功构建，`otool -L` 显示 `@rpath/foundationKit.framework/foundationKit` 加入 load commands。但 symbol 数量仍为 4224（含完整 runtime），runtime 仍然嵌入。

**结论**：添加依赖不影响 runtime 嵌入，两套 runtime 仍然并存。

#### E4：弱符号合并（Weak Symbol Coalescing）分析

**发现**：
```
nm -m businessKit: 00000000000c1688 (__TEXT,__text) weak external _IsInstance
nm -m foundationKit: 000000000000394c (__TEXT,__text) weak external _IsInstance
```
`_IsInstance` 在两个框架中都以 `weak external` 导出！理论上 dyld 应该合并弱符号。

**关键验证** — 检查 businessKit 的 weak bind table：
```
Weak bind table: only 6 entries:
  _Konan_DebugBuffer        (GOT)
  _Konan_DebugBufferSize    (GOT)
  _Konan_DebugObjectToUtf8Array (GOT)
  __ZTISt12length_error     (GOT)
  __ZdlPvm                  (GOT)
  __Znwm                    (GOT)
```

**`_IsInstance` 不在 weak bind table 中。** 这意味着：
- businessKit 内部的 Kotlin 代码调用 `_IsInstance` 使用的是**直接 PC-relative `bl` 指令**，地址写死在 binary 里
- `weak external` 属性仅影响**外部调用方**（Swift 代码调用 businessKit 时），不影响 businessKit 内部调用
- dyld 的 weak coalescing 对这些直接分支指令**无效**

**结论**：weak 符号合并路线不通。只有 3 个 `_Konan_Debug*` 符号通过 GOT 访问（可被合并），但这些是调试符号而非核心 runtime。

#### E5：`-Xbinary=` 参数分析

K/N 通过 `-Xbinary=<option=value>` 支持多种 binary-level 选项，但没有任何与 runtime 嵌入相关的选项。主要选项包括：
- `bundleId=<id>`：设置 Info.plist bundle ID
- `gc=<type>`：GC 算法选择（cms/noop/stms）
- `allocator=<type>`：内存分配器选择

无任何"skip runtime embedding"或"use external runtime"选项。

---

## 关键技术结论

### 为什么在当前 K/N 版本（2.2.0）下不可能实现分体运行时共享？

```
Kotlin IR (businessKit)
    + runtime.bc (K/N runtime)
    + mm.bc (memory management)
    + gc-related.bc (GC implementations)
        ↓ LLVM LTO (全程序优化)
    LLVM 优化后的 IR
        ↓ LLVM CodeGen
    Mach-O binary (self-contained, runtime symbols as local 't')
```

1. **LTO 不可分割**：K/N 将 Kotlin 代码和 runtime 在同一个 LLVM module 内完成 LTO，无法在 module 边界处"分离" runtime
2. **直接分支写死**：经过 LTO + 优化后，所有 runtime 函数调用都成为直接 PC-relative branch，无法被 dyld 重定向
3. **无外部运行时模式**：K/N 没有任何编译器选项支持"引用外部 runtime"模式
4. **二级命名空间强制隔离**：iOS 的两级命名空间确保每个 framework 的符号独立，无法跨 framework 共享

### 理论上可行的路径（当前不切实际）

**Path F：自定义 stub runtime + Mach-O 二进制修补**
- 理论：创建一个 stub runtime.bc，所有函数为空声明（`extern`），让 businessKit 产生 undefined runtime 引用，再通过 `@rpath/foundationKit.framework` 动态解析
- 障碍：K/N 要求 runtime.bc 是合法 bitcode 且**必须有函数实现**；stub 的 undefined 引用在链接时会报错
- 即便绕过，还需要 foundationKit 将 runtime 符号从 `t`（local）变为 `T`（global），需要修改 K/N 编译流程

**Path G：Mach-O 符号可见性修补**
- 理论：构建后用工具（如 `llvm-objcopy --globalize-symbol`）将 foundationKit 的 runtime 符号从 `t` 改为 `T`，然后使 businessKit 在 `-flat_namespace` 模式下引用这些符号
- 障碍：macOS Xcode toolchain 不提供 `llvm-objcopy`；即便修改了符号可见性，businessKit 内部调用仍为直接分支（不通过 GOT），无法被 dyld 重定向；`-flat_namespace` 在 iOS App Store 中不被支持

**Path H：编译独立的 KNRuntime.xcframework**
- 理论：将 K/N 的 runtime.bc 文件编译成独立的动态 framework（`KNRuntime.xcframework`），foundationKit 和 businessKit 都链接到这个 shared runtime
- 障碍：K/N 的 LTO 过程会无论如何把 runtime symbols 嵌入进去；需要 K/N 编译器本身支持"引用外部 runtime"的模式，即 KMT-2364 请求的功能

---

## 官方解决方案状态

**JetBrains YouTrack Issue**: [KMT-2364 - Thin Kotlin/Native Apple frameworks](https://youtrack.jetbrains.com/issue/KMT-2364)

- 请求：支持"thin"框架模式，不嵌入 K/N runtime，由一个共享的 runtime framework 提供
- 状态：**已确认，但预计 1-2 年内不会实现**
- JetBrains 内部称此为"non-self-contained framework"支持

**相关讨论**：[Kotlin forum - Thin K/N Apple Frameworks](https://discuss.kotlinlang.org/t/feature-request-discussion-thin-kotlin-native-apple-frameworks-shared-runtime-deps-non-self-contained-framework/31018)

---

## 行业现状参考

| 方案 | 代表 | 运行时共享 | 独立交付 |
|------|------|-----------|---------|
| 单一大 XCFramework | TikTok 当前 | ✅ | ❌ |
| 分体 + 双运行时（现有 K/N） | 本研究原型 | ❌（双份） | ✅ |
| 分体 + 共享运行时（KMT-2364） | 未来 K/N | ✅ | ✅ |

---

## 总结

在 K/N 2.2.0（2025-2026）时代，**两个独立 XCFramework 共享运行时**在技术上不可行，除非修改 K/N 编译器本身。

当前唯一可行的"运行时共享"方案是 Path D（合并为单个 XCFramework），这也是 TikTok 等大型应用的实际做法。

分体交付 + 运行时共享的正确解需要等待 JetBrains 实现 [KMT-2364](https://youtrack.jetbrains.com/issue/KMT-2364)。

---

*探索日期：2026-03-31*
*K/N 版本：2.2.0*
*实验平台：macOS arm64 (Apple Silicon), iOS Simulator arm64/x86_64*
