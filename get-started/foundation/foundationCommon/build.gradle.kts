import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
}

// ─── foundationCommon ─────────────────────────────────────────────────────────
//
// Central module for all Foundation Kotlin code, cinterop bridge declarations,
// and dependency management.
//
// • Android artifact    — consumed directly by :androidApp
// • iOS klibsand framework — compiled here; delivered via :foundation:KMPFoundation

kotlin {

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

    val xcfName = "foundationKit"
    val xcf = XCFramework(xcfName)

    iosArm64 {
        compilations["main"].cinterops.create("foundationBridge") {
            defFile(project.file("../foundation-bridge/foundationBridge.def"))
            includeDirs(project.file("../foundation-bridge/headers"))
        }
        binaries.framework {
            baseName = xcfName
            xcf.add(this)
            // Split-framework: foundationKit embeds the runtime so businessKit doesn't have to.
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

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        androidMain {
            dependencies {
            }
        }

        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.runner)
                implementation(libs.androidx.core)
                implementation(libs.androidx.junit)
            }
        }

        // iosBridgeMain: cinterop-using sources (BridgeSetup, KMPLogger, Platform.ios).
        // K/N 2.x cinterop klibsare target-specific; adding the same directory to each
        // target-specific source set ensures bridge type imports resolve in both compilations.
        getByName("iosArm64Main") {
            kotlin.srcDir("src/iosBridgeMain/kotlin")
        }
        getByName("iosSimulatorArm64Main") {
            kotlin.srcDir("src/iosBridgeMain/kotlin")
        }

        iosMain {
            dependencies {
            }
        }
    }
}
