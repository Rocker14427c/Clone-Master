plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}

// Deterministic build configuration – no local machine paths
buildscript {
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

task("clean") {
    delete(rootProject.buildDir)
}

tasks.register("printEnvironment") {
    doLast {
        println("Gradle: ${gradle.gradleVersion}")
        println("Java: ${System.getProperty("java.version")}")
        println("OS: ${System.getProperty("os.name")}")
        println("Build is deterministic – no local paths committed")
    }
}
