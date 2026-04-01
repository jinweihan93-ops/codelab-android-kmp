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
            export(project(":foundation"))
            // KMT-2364 Consumer mode: runtime provided by foundationKit at dyld load time
            binaryOption("embedRuntime", "false")
            linkerOpts("-undefined", "dynamic_lookup")
        }
    }

    iosArm64 {
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            export(project(":foundation"))
            // KMT-2364 Consumer mode: runtime provided by foundationKit at dyld load time
            binaryOption("embedRuntime", "false")
            linkerOpts("-undefined", "dynamic_lookup")
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            export(project(":foundation"))
            // KMT-2364 Consumer mode: runtime provided by foundationKit at dyld load time
            binaryOption("embedRuntime", "false")
            linkerOpts("-undefined", "dynamic_lookup")
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
