# Business 模块设计方案

> 目标：构建第二个 KMP 模块，产出 `businessKit.xcframework`，
> 与 `foundationKit.xcframework` 组成 V3 分体架构的原型验证环境。

---

## 一、核心目标

| 目标 | 说明 |
|------|------|
| **验证 duplicate symbol** | business 默认会把 runtime+stdlib 再 embed 一遍，用分析工具量化这个问题 |
| **建立 thin framework 基线** | 记录"去除 runtime 前 vs 后"的 binary size 和符号数差异 |
| **模拟真实业务依赖** | business 调用 foundation 的 API，贴近生产场景 |

---

## 二、模块结构

```
business/
├── build.gradle.kts
└── src/
    ├── commonMain/kotlin/com/example/kmp/business/
    │   ├── model/
    │   │   ├── User.kt          # 数据类
    │   │   └── FeedItem.kt      # 数据类
    │   ├── UserService.kt       # 业务逻辑（调用 foundation）
    │   └── FeedService.kt       # 业务逻辑
    ├── androidMain/kotlin/com/example/kmp/business/
    │   └── UserServiceAndroid.kt  # Android actual（如有需要）
    └── iosMain/kotlin/com/example/kmp/business/
        └── (空，或 iOS specific impl)
```

---

## 三、Kotlin 代码设计

### 3.1 数据模型

```kotlin
// model/User.kt
data class User(
    val id: String,
    val name: String,
    val platform: String   // 从 foundation.platform() 填充
)

// model/FeedItem.kt
data class FeedItem(
    val id: String,
    val title: String,
    val author: User
)
```

### 3.2 业务逻辑

```kotlin
// UserService.kt
import com.example.kmp.foundation.platform   // 调用 foundation 的 expect fun

class UserService {
    fun currentUser(): User = User(
        id = "u001",
        name = "KMP User",
        platform = platform()                 // <- 这里依赖 foundation
    )

    fun formatUserTag(user: User): String =
        "[${user.platform}] ${user.name}"
}

// FeedService.kt
class FeedService(private val userService: UserService) {
    fun generateFeed(count: Int): List<FeedItem> =
        (1..count).map { i ->
            FeedItem(
                id = "item_$i",
                title = "Feed Item #$i",
                author = userService.currentUser()
            )
        }
}
```

**为什么这样设计：**
- `platform()` 来自 `:foundation`，强制建立跨模块依赖
- `data class` 会生成 `kfun:*#equals/hashCode/toString/copy`，让 Kotlin User API 符号数量多几个，更有代表性
- `List<FeedItem>` 会触发 kotlinx 集合桥接，测试 K/N 的集合互操作

### 3.3 ObjC 可见的 API 预期

Business framework 构建后，iOS 侧可以看到：
```objc
// businessKit.h
@interface BusinessKitUser : BusinessKitBase
@property (readonly) NSString *id;
@property (readonly) NSString *name;
@property (readonly) NSString *platform;
@end

@interface BusinessKitFeedItem : BusinessKitBase
@property (readonly) NSString *id;
@property (readonly) NSString *title;
@property (readonly) BusinessKitUser *author;
@end

@interface BusinessKitUserService : BusinessKitBase
- (instancetype)init;
- (BusinessKitUser *)currentUser __attribute__((swift_name("currentUser()")));
- (NSString *)formatUserTagUser:(BusinessKitUser *)user __attribute__((swift_name("formatUserTag(user:)")));
@end

@interface BusinessKitFeedService : BusinessKitBase
- (instancetype)initWithUserService:(BusinessKitUserService *)userService;
- (NSArray<BusinessKitFeedItem *> *)generateFeedCount:(int32_t)count
    __attribute__((swift_name("generateFeed(count:)")));
@end
```

---

## 四、build.gradle.kts

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

    iosX64 {
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            // 关键：默认不加任何 -Xstatic-framework 参数
            // 观察 K/N 默认行为下的 duplicate symbol 现象
        }
    }

    iosArm64 {
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":foundation"))   // 依赖 foundation
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}
```

---

## 五、settings.gradle.kts 变更

```kotlin
include(":androidApp")
include(":foundation")
include(":business")     // 新增
```

---

## 六、预期分析结果（V3 问题的实证）

构建完成后，用 project 模式分析：

```bash
python3 xcframework-analyzer.py \
  --project ../build/XCFrameworks/release/ \
  --save-project project_v3.json
```

或手动配置：
```json
{
  "name": "V3-prototype",
  "frameworks": [
    { "path": "../foundation/build/XCFrameworks/release/foundationKit.xcframework", "role": "foundation" },
    { "path": "../business/build/XCFrameworks/release/businessKit.xcframework",     "role": "business"  }
  ]
}
```

**预期输出：**
```
⚠️  N symbols defined in multiple frameworks!

  Category                  Dup symbols
  ──────────────────────────────────────
  Kotlin/Native Runtime         127
  Kotlin Stdlib                 651
  kotlinx libraries               4
  C++ RTTI                       43
  C++ symbols                   ~100
  Other                         ~700+
  ─────────────────────────────────────
  Total                        ~1500+
```

这个数字就是 V3 要消灭的重复量，也是 RFC 中"business 模块 thin 化"的量化目标。

---

## 七、实施步骤

1. 在 `settings.gradle.kts` 里 `include(":business")`
2. 创建 `business/` 目录及代码
3. 构建：`./gradlew :business:assembleBusinessKitReleaseXCFramework`
4. 更新 `xcframework_viz/project.json`，加入 business 的路径
5. 运行 `python3 xcframework-analyzer.py --project-config project.json`
6. 记录 duplicate symbol 数量，作为 V3 优化的 baseline

---

## 八、后续对照实验（可选）

实验完默认行为后，可以尝试以下干预手段，观察效果：

### 实验 A：`isStatic = true`（静态库模式）
```kotlin
binaries.framework {
    isStatic = true    // 生成 .a 而非 .dylib
}
```
静态 framework 链接时会被 linker 合并，duplicate 变成链接错误而非运行时问题。

### 实验 B：`-Xpartial-linkage`（K/N 实验性 flag）
K/N 2.x 有实验性的 partial linkage 支持，允许 framework 不 embed 某些符号。

### 实验 C：post-build strip 脚本
构建后用 `strip -u -r` 或自定义 linker 脚本移除 business binary 中的 runtime 符号，观察最终 binary 是否可以正常加载（预期：如果 Foundation 先加载，runtime 已初始化，business 可以复用）。
