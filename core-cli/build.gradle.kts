plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// JDK 21 is the baseline everywhere (plan.md §0); the daemon JVM here is 25.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

application {
    mainClass.set("de.arjmandi.nanobot.companion.cli.MainKt")
}

// A distribution is a distribution (T59): `installDist` and `distZip` are what a reviewer runs,
// so the MIT licence and the consolidated notices travel beside the script rather than only
// living in the repository.
distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("THIRD_PARTY_NOTICES.txt"))
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    // The recorder is a harness, not the library: it reads wire JSON directly and
    // talks to the gateway itself so it can capture frames before :core can.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.java)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
