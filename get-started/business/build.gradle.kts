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
                implementation(project(":foundation"))
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}
