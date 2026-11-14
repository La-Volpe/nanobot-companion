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

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
