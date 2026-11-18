pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        // AndroidX is published on Google Maven. A Central 429 stops Gradle before it can fall back.
        mavenCentral {
            content { excludeGroupByRegex("androidx(\\..*)?") }
        }
        google()
    }
}

rootProject.name = "nanobot-companion"

include(":core")
include(":core-cli")
include(":app")
