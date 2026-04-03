import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
}

kotlin {

    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    androidLibrary {
        namespace = "com.example.kmp.foundation"
        compileSdk = 36
        minSdk = 24

        withHostTestBuilder {
        }

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // For iOS targets, this is also where you should
    // configure native binary output. For more information, see:
    // https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

    // A step-by-step guide on how to include this library in an XCode
    // project can be found here:
    // https://developer.android.com/kotlin/multiplatform/migrate
    val xcfName = "foundationKit"
    val xcf = XCFramework(xcfName)

    iosArm64 {
        compilations["main"].cinterops.create("foundationBridge") {
            // Bridge headers live in :foundation-ios-bridge; the def file and headers are
            // referenced by relative path so no inter-module compilation dependency is needed.
            defFile(project.file("../foundation-bridge/foundationBridge.def"))
            includeDirs(project.file("../foundation-bridge/headers"))
        }
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            binaryOption("exportRuntimeSymbols", "true")
            binaryOption("exportKlibSymbols", "com.example.kmp.foundation")
        }
    }

    iosSimulatorArm64 {
        compilations["main"].cinterops.create("foundationBridge") {
            defFile(project.file("../foundation-bridge/foundationBridge.def"))
            includeDirs(project.file("../foundation-bridge/headers"))
        }
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            binaryOption("exportRuntimeSymbols", "true")
            binaryOption("exportKlibSymbols", "com.example.kmp.foundation")
        }
    }

    // Source set declarations.
    // Declaring a target automatically creates a source set with the same name. By default, the
    // Kotlin Gradle Plugin creates additional source sets that depend on each other, since it is
    // common to share sources between related targets.
    // See: https://kotlinlang.org/docs/multiplatform-hierarchy.html
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                // Add KMP dependencies here
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        androidMain {
            dependencies {
                // Add Android-specific dependencies here. Note that this source set depends on
                // commonMain by default and will correctly pull the Android artifacts of any KMP
                // dependencies declared in commonMain.
            }
        }

        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.runner)
                implementation(libs.androidx.core)
                implementation(libs.androidx.junit)
            }
        }

        // iosBridgeMain: cinterop-using sources shared across iOS targets via srcDir.
        // In KMP 2.x, cinterop klibsare attached to specific target compilations; they are NOT
        // visible in intermediate source sets (like iosMain) even with commonization enabled.
        // Adding the physical directory as an extra kotlin.srcDir on each target-specific source
        // set means the same files are compiled twice (iosArm64 + iosSimulatorArm64), with each
        // compilation's cinterop klib on the classpath, so all bridge type imports resolve.
        getByName("iosArm64Main") {
            kotlin.srcDir("src/iosBridgeMain/kotlin")
        }
        getByName("iosSimulatorArm64Main") {
            kotlin.srcDir("src/iosBridgeMain/kotlin")
        }

        iosMain {
            dependencies {
                // Cinterop bindings are generated directly from :foundation-ios-bridge headers.
            }
        }
    }

}

// ─── CocoaPods delivery tasks ─────────────────────────────────────────────────
// Mirror the TikTok pattern: build XCFramework, then copy the podspec next to it.
// iOS side integrates via: pod 'foundationKit', :path => '<targetDir>'

/**
 * Copy bridge headers into every framework slice inside the XCFramework, and patch
 * the umbrella header so Swift can see the full protocol definitions.
 *
 * Background: K/N's generated umbrella header only *forward-declares* ObjC protocols
 * that come from cinterop (e.g. KMPPlatformInfoProvider).  Swift needs full definitions
 * to write conformances like `class AppProvider: NSObject, KMPPlatformInfoProvider`.
 * Copying the bridge headers and #import-ing them from the umbrella header resolves this.
 */
fun injectBridgeHeaders(xcframeworkDir: File) {
    val bridgeHeadersDir = file("../foundation-bridge/headers")
    val bridgeHeaders = listOf("KMPFoundationBridge.h", "KMPFoundationBridgeLogger.h")

    xcframeworkDir.walkTopDown()
        .filter { it.name == "foundationKit.framework" && it.isDirectory }
        .forEach { frameworkDir ->
            val headersDir = frameworkDir.resolve("Headers")
            // 1. Copy bridge headers into the framework's Headers directory.
            bridgeHeaders.forEach { name ->
                bridgeHeadersDir.resolve(name).copyTo(headersDir.resolve(name), overwrite = true)
            }
            // 2. Prepend #import lines to the umbrella header so the module sees them.
            val umbrella = headersDir.resolve("foundationKit.h")
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
    dependsOn("assembleFoundationKitDebugXCFramework")
    notCompatibleWithConfigurationCache("copies podspec file at execution time")
    doLast {
        val spec = file("foundationKit.podspec")
        val target = file("build/XCFrameworks/debug")
        spec.copyTo(file("${target}/foundationKit.podspec"), overwrite = true)
        injectBridgeHeaders(target.resolve("foundationKit.xcframework"))
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
        injectBridgeHeaders(target.resolve("foundationKit.xcframework"))
        println("✅ foundationKit release built.\n   pod 'foundationKit', :path => '${target}'")
    }
}