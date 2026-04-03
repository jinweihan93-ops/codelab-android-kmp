// ─── KMPFoundation — XCFramework delivery module ─────────────────────────────
//
// This module contains NO Kotlin source files and NO compilation targets.
// All Foundation Kotlin code, cinterop bridge, and iOS framework configuration
// live in :foundation:foundationCommon.
//
// KMPFoundation's sole responsibility is CocoaPods delivery:
//   1. Trigger foundationCommon's assembleFoundationKitXXXXXCFramework task.
//   2. Copy foundationKit.podspec alongside the XCFramework output.
//   3. Inject bridge ObjC headers so Swift sees full protocol definitions.
//
// iOS app integrates via:
//   pod 'foundationKit', :path => 'foundation/foundationCommon/build/XCFrameworks/debug'

/**
 * Copy bridge headers into every foundationKit.framework slice inside the XCFramework,
 * and prepend #import directives to the umbrella header.
 *
 * K/N's generated umbrella header only forward-declares cinterop ObjC protocols.
 * Inserting the bridge headers makes full definitions visible to Swift so conformances
 * like `class AppProvider: NSObject, KMPPlatformInfoProvider` compile correctly.
 */
fun injectBridgeHeaders(xcframeworkDir: File) {
    val bridgeHeadersDir = file("../foundation-bridge/headers")
    val bridgeHeaders = listOf("KMPFoundationBridge.h", "KMPFoundationBridgeLogger.h")

    xcframeworkDir.walkTopDown()
        .filter { it.name == "foundationKit.framework" && it.isDirectory }
        .forEach { frameworkDir ->
            val headersDir = frameworkDir.resolve("Headers")
            bridgeHeaders.forEach { name ->
                bridgeHeadersDir.resolve(name).copyTo(headersDir.resolve(name), overwrite = true)
            }
            val umbrella = headersDir.resolve("foundationKit.h")
            val imports = bridgeHeaders.joinToString("\n") { "#import \"$it\"" } + "\n"
            val existing = umbrella.readText()
            if (!existing.startsWith(imports)) {
                umbrella.writeText(imports + existing)
            }
        }
}

// XCFramework output lives in foundationCommon's build directory.
fun foundationCommonXCFrameworkDir(variant: String): File =
    file("../foundationCommon/build/XCFrameworks/$variant")

tasks.register("buildIOSDebug") {
    description = "Build iOS debug XCFramework (via foundationCommon) and copy podspec"
    group = "kotlin multiplatform"
    dependsOn(":foundation:foundationCommon:assembleFoundationKitDebugXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val target = foundationCommonXCFrameworkDir("debug")
        file("foundationKit.podspec").copyTo(file("${target}/foundationKit.podspec"), overwrite = true)
        injectBridgeHeaders(target.resolve("foundationKit.xcframework"))
        println("✅ foundationKit debug built.\n   pod 'foundationKit', :path => '${target}'")
    }
}

tasks.register("buildIOSRelease") {
    description = "Build iOS release XCFramework (via foundationCommon) and copy podspec"
    group = "kotlin multiplatform"
    dependsOn(":foundation:foundationCommon:assembleFoundationKitReleaseXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val target = foundationCommonXCFrameworkDir("release")
        file("foundationKit.podspec").copyTo(file("${target}/foundationKit.podspec"), overwrite = true)
        injectBridgeHeaders(target.resolve("foundationKit.xcframework"))
        println("✅ foundationKit release built.\n   pod 'foundationKit', :path => '${target}'")
    }
}
