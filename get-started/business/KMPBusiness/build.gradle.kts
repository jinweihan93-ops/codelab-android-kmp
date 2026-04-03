import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

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

    iosArm64 {
        compilations["main"].cinterops.create("businessBridge") {
            defFile(project.file("../business-bridge/businessBridge.def"))
            includeDirs(project.file("../business-bridge/headers"))
        }
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            // KMT-2364: Do NOT re-export Foundation types — their ObjC bridge lives in
            // foundationKit.framework only. Re-exporting causes duplicate ObjC class
            // registration, corrupting the BackRef→ObjHeader* mapping.
            // export(project(":foundation:KMPFoundation"))
            binaryOption("embedRuntime", "false")
            binaryOption("externalKlibs", "com.example.kmp.foundation")
            val suffix = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
            linkerOpts(
                "-framework", "foundationKit",
                "-F", "${projectDir}/../../foundation/KMPFoundation/build/bin/iosArm64/${suffix}Framework"
            )
        }
    }

    iosSimulatorArm64 {
        compilations["main"].cinterops.create("businessBridge") {
            defFile(project.file("../business-bridge/businessBridge.def"))
            includeDirs(project.file("../business-bridge/headers"))
        }
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            // KMT-2364: Do NOT re-export Foundation types (see iosArm64 comment above).
            // export(project(":foundation:KMPFoundation"))
            binaryOption("embedRuntime", "false")
            binaryOption("externalKlibs", "com.example.kmp.foundation")
            val suffix = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
            linkerOpts(
                "-framework", "foundationKit",
                "-F", "${projectDir}/../../foundation/KMPFoundation/build/bin/iosSimulatorArm64/${suffix}Framework"
            )
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":foundation:KMPFoundation"))
                implementation(libs.kotlin.stdlib)
            }
        }

        // iosBridgeMain: same srcDir pattern as foundation.
        getByName("iosArm64Main") {
            kotlin.srcDir("src/iosBridgeMain/kotlin")
        }
        getByName("iosSimulatorArm64Main") {
            kotlin.srcDir("src/iosBridgeMain/kotlin")
        }

        iosMain {
            dependencies {
                // Cinterop bindings are generated directly from :business:business-bridge headers.
            }
        }
    }
}

// ─── CocoaPods delivery tasks ─────────────────────────────────────────────────

/**
 * Copy business bridge headers into every framework slice, and patch the umbrella
 * header so Swift can see the full ObjC protocol definitions (same pattern as foundation).
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

tasks.register("buildIOSDebug") {
    description = "Build iOS debug XCFramework and copy podspec for CocoaPods local integration"
    group = "kotlin multiplatform"
    dependsOn("assembleBusinessKitDebugXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val spec = file("businessKit.podspec")
        val target = file("build/XCFrameworks/debug")
        spec.copyTo(file("${target}/businessKit.podspec"), overwrite = true)
        injectBridgeHeaders(target.resolve("businessKit.xcframework"))
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
        injectBridgeHeaders(target.resolve("businessKit.xcframework"))
        println("✅ businessKit release built.\n   pod 'businessKit', :path => '${target}'")
    }
}
