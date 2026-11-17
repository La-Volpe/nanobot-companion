import com.android.build.api.component.analytics.AnalyticsEnabledVariantBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 compiles Kotlin itself, so `org.jetbrains.kotlin.android` is not applied here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The third and last platform. It links the same :core the iOS app links, so a
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
            // The DEBUG menu parity the iOS app has: the eight failures by hand.
            buildConfigField("boolean", "DEBUG_MENU", "true")
        }
        release {
            buildConfigField("boolean", "DEBUG_MENU", "false")
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Compose Preview's per-variant runfiles task is created by AGP and refuses to build unless
        // unit tests compile Android resources: `./gradlew :app:tasks` died on
        // `generateDebugComposePreviewRunfiles` with "Compose Preview requires compiled Android
        // resources to resolve layout XML and theme attributes" (T68).
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    beforeVariants { variant ->
        // AGP 9 enables unit tests for debug only, and Compose Preview's per-variant runfiles task
        // refuses to be created for a variant that has none: `./gradlew :app:tasks` died with
        // "Unit tests are disabled for this variant (enableUnitTest = false in beforeVariants)"
        // (T68). The property is not on the public VariantBuilder interface in AGP 9, but it is on the
        // type the DSL hands us at runtime, which is what this cast names. Release unit tests are never
        // run by CI, so the cost is a configuration entry, not minutes.
        (variant as AnalyticsEnabledVariantBuilder).enableUnitTest = true
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

    // View models are tested off-device; the Compose tests are the
    // taps, and they need a device because a Composable has no host JVM.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}
