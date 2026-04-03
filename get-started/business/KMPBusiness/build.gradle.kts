// ─── KMPBusiness — XCFramework delivery module ────────────────────────────────
//
// This module contains NO Kotlin source files and NO compilation targets.
// All Business Kotlin code, cinterop bridge, and iOS framework configuration
// live in :business:businessCommon.
//
// KMPBusiness's sole responsibility is CocoaPods delivery:
//   1. Trigger businessCommon's assembleBusinessKitXXXXXCFramework task.
//   2. Copy businessKit.podspec alongside the XCFramework output.
//   3. Inject bridge ObjC headers so Swift sees full protocol definitions.
//
// iOS app integrates via:
//   pod 'businessKit', :path => 'business/businessCommon/build/XCFrameworks/debug'

/**
 * Copy business bridge headers into every businessKit.framework slice and patch
 * the umbrella header (same pattern as KMPFoundation / foundationCommon).
 */
fun injectBridgeHeaders(xcframeworkDir: File) {
    val bridgeHeadersDir = file("../business-bridge/headers")
    val bridgeHeaders = listOf("KMPBusinessBridgeAuth.h", "KMPBusinessBridgeNetwork.h")

    xcframeworkDir.walkTopDown()
        .filter { it.name == "businessKit.framework" && it.isDirectory }
        .forEach { frameworkDir ->
            val headersDir = frameworkDir.resolve("Headers")
            bridgeHeaders.forEach { name ->
                bridgeHeadersDir.resolve(name).copyTo(headersDir.resolve(name), overwrite = true)
            }
            val umbrella = headersDir.resolve("businessKit.h")
            val imports = bridgeHeaders.joinToString("\n") { "#import \"$it\"" } + "\n"
            val existing = umbrella.readText()
            if (!existing.startsWith(imports)) {
                umbrella.writeText(imports + existing)
            }
        }
}

fun businessCommonXCFrameworkDir(variant: String): File =
    file("../businessCommon/build/XCFrameworks/$variant")

tasks.register("buildIOSDebug") {
    description = "Build iOS debug XCFramework (via businessCommon) and copy podspec"
    group = "kotlin multiplatform"
    dependsOn(":business:businessCommon:assembleBusinessKitDebugXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val target = businessCommonXCFrameworkDir("debug")
        file("businessKit.podspec").copyTo(file("${target}/businessKit.podspec"), overwrite = true)
        injectBridgeHeaders(target.resolve("businessKit.xcframework"))
        println("✅ businessKit debug built.\n   pod 'businessKit', :path => '${target}'")
    }
}

tasks.register("buildIOSRelease") {
    description = "Build iOS release XCFramework (via businessCommon) and copy podspec"
    group = "kotlin multiplatform"
    dependsOn(":business:businessCommon:assembleBusinessKitReleaseXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val target = businessCommonXCFrameworkDir("release")
        file("businessKit.podspec").copyTo(file("${target}/businessKit.podspec"), overwrite = true)
        injectBridgeHeaders(target.resolve("businessKit.xcframework"))
        println("✅ businessKit release built.\n   pod 'businessKit', :path => '${target}'")
    }
}
