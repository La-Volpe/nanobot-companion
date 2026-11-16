import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 compiles Kotlin itself, so there is no `org.jetbrains.kotlin.android` here (D47).
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The third and last platform (plan.md §8). It links the same :core the iOS app links, so a
// parity bug found here is a screen-layer bug or a core bug, never a new protocol question.
android {
    namespace = "de.arjmandi.nanobot.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.arjmandi.nanobot.companion"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        debug {
            // The DEBUG menu parity the iOS app has: the eight failures by hand (I8, A8).
            buildConfigField("boolean", "DEBUG_MENU", "true")
        }
        release {
            buildConfigField("boolean", "DEBUG_MENU", "false")
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // View models are tested off-device (plan.md A1–A8's unit half); the Compose tests are the
    // taps, and they need a device because a Composable has no host JVM.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}
