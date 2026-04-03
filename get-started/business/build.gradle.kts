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
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            // KMT-2364: Do NOT re-export Foundation types — their ObjC bridge lives in
            // foundationKit.framework only. Re-exporting causes duplicate ObjC class
            // registration, corrupting the BackRef→ObjHeader* mapping.
            // export(project(":foundation"))
            binaryOption("embedRuntime", "false")
            binaryOption("externalKlibs", "com.example.kmp.foundation")
            val suffix = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
            linkerOpts(
                "-framework", "foundationKit",
                "-F", "${projectDir}/../foundation/build/bin/iosArm64/${suffix}Framework"
            )
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            // KMT-2364: Do NOT re-export Foundation types (see iosArm64 comment above).
            // export(project(":foundation"))
            binaryOption("embedRuntime", "false")
            binaryOption("externalKlibs", "com.example.kmp.foundation")
            val suffix = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
            linkerOpts(
                "-framework", "foundationKit",
                "-F", "${projectDir}/../foundation/build/bin/iosSimulatorArm64/${suffix}Framework"
            )
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":foundation"))
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}

// ─── CocoaPods delivery tasks ─────────────────────────────────────────────────

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
