// Top-level build file for JS8Call Android project

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.10.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
}

// Kotlin compiler warnings fail the build when the js8KotlinWarningsAsErrors property is true. CI sets
// it (ORG_GRADLE_PROJECT_js8KotlinWarningsAsErrors), so the warning count stays at zero; local builds
// with another Kotlin version are not held to it.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors.set(
            providers.gradleProperty("js8KotlinWarningsAsErrors").map { it.toBoolean() }.orElse(false)
        )
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
