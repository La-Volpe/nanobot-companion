import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// `-Pcontract` is the opt-in for the live-gateway tests (plan.md §3.4). Fast runs never see
// them: every contract test asserts the flag and returns skipped without it.
tasks.withType<Test>().configureEach {
    // Presence is the flag: `-Pcontract` alone is the documented invocation, and that sets
    // the property to an empty string rather than to "true".
    systemProperty("nanobot.contract", providers.gradleProperty("contract").isPresent.toString())
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
            // api: CoreDeps and the facade expose CoroutineDispatcher/StateFlow to Swift and Compose.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
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
        jvmTest.dependencies {
            // A real WebSocket server in-process, so the frame pump is tested against a socket.
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.websockets)
            // The contract tests talk to a real gateway over the JVM engine.
            implementation(libs.ktor.client.java)
        }
    }
}
