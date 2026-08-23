pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Clone-Master"
include(":app")
include(":core")
include(":runtime")

// Enable version catalog and build cache for deterministic builds
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
