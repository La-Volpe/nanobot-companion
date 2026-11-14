import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // D2: every public symbol is deliberate, because Swift and Compose both consume it.
    explicitApi()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    androidLibrary {
        namespace = "de.arjmandi.nanobot.companion.core"
        compileSdk = 37
        minSdk = 26
        // Host (JVM) unit tests for androidMain, so the android target is testable
        // without a device: plan.md C1.2 and C3.2 run here.
        withHostTestBuilder {}.configure {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
        }
    }
}
