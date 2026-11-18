import com.android.build.api.component.analytics.AnalyticsEnabledVariantBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 compiles Kotlin itself, so `org.jetbrains.kotlin.android` is not applied here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release key, when the runner has one. `NANOBOT_KEYSTORE_PATH` points at a file the
// workflow decoded from a repository secret into the runner's temp directory; the passwords and
// alias come from the environment, never from a file in the repository and never from
// `gradle.properties` (which is committed). Absent, the release build stays unsigned rather than
// failing — an unsigned artefact is honest, a fake key is not.
class ReleaseKey(
    val store: java.io.File,
    val storePassword: String,
    val alias: String,
    val keyPassword: String,
)

val releaseKey: ReleaseKey? = run {
    val path = System.getenv("NANOBOT_KEYSTORE_PATH")
    val storePassword = System.getenv("NANOBOT_KEYSTORE_PASSWORD")
    val alias = System.getenv("NANOBOT_KEY_ALIAS")
    val keyPassword = System.getenv("NANOBOT_KEY_PASSWORD")
    when {
        path == null && storePassword == null && alias == null && keyPassword == null -> null
        // Half a key is a wiring mistake, and a silently unsigned release is how it ships.
        listOf(path, storePassword, alias, keyPassword).any { it == null } ->
            throw GradleException(
                "signing is half configured: NANOBOT_KEYSTORE_PATH, NANOBOT_KEYSTORE_PASSWORD, " +
                    "NANOBOT_KEY_ALIAS and NANOBOT_KEY_PASSWORD must all be set, or none of them",
            )
        !file(path!!).isFile -> throw GradleException("NANOBOT_KEYSTORE_PATH is set but $path is not a file")
        else -> ReleaseKey(file(path), storePassword!!, alias!!, keyPassword!!)
    }
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

    releaseKey?.let { key ->
        signingConfigs.create("release") {
            storeFile = key.store
            storePassword = key.storePassword
            keyAlias = key.alias
            keyPassword = key.keyPassword
            // minSdk is 26, so v1 is for nothing this app installs on; v2 and v3 are what a
            // modern device validates, and v1 stays on only because a store upload wants it.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
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
            // Signing is a secret the runner may not have: a fork, a PR, or a checkout without
            // the keystore still builds — the artefact is then unsigned, and the release workflow
            // says so instead of publishing something nobody can install.
            releaseKey?.let { signingConfig = signingConfigs.getByName("release") }
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
        // AGP 9 enables unit tests for debug only, and Compose Preview's per-variant runfiles task refuses
        // to be created for a variant that has none: `./gradlew :app:tasks` died with "Unit tests are
        // disabled for this variant (enableUnitTest = false in beforeVariants)" (T68). The property is not
        // on the public VariantBuilder interface, and the type the DSL hands the lambda differs by
        // environment — locally an `AnalyticsEnabled…` decorator, on a CI runner a plain
        // `ApplicationVariantBuilderImpl` — so a cast to either one throws somewhere. The interface-less
        // route is the only portable one: call the setter if the object has it.
        variant.javaClass.methods.firstOrNull { it.name == "setEnableUnitTest" }?.invoke(variant, true)
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
