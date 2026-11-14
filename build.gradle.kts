plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
    // :app's plugins, declared once at the root like the rest: AGP is already on the build
    // classpath from :core, and a second version claim cannot be checked against it.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kover) apply false
}

// Every task in plan.md §5 has a Gate line. `check` is what all of them run.
tasks.register("gateCore") {
    group = "verification"
    description = "Runs the fast core suite (plan.md G1, mechanical items only)."
    dependsOn(":core:allTests")
}
