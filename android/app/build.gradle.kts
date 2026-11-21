import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.js8call.example"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.s2underground.js8tactical"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Which commit a test build came from, shown in crash and diagnostic reports.
        buildConfigField("String", "GIT_SHA", "\"${(System.getenv("GITHUB_SHA") ?: "local").take(8)}\"")
    }

    signingConfigs {
        // One fixed debug key for every test build (CI runners otherwise make a new random one
        // each run, and Android then refuses to install a build over the previous one).
        // Debug-only key, not a secret; release builds are signed separately.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            val envStoreFile = System.getenv("KEYSTORE_PATH") ?: System.getenv("JS8_KEYSTORE_FILE")
            val envStorePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getenv("JS8_KEYSTORE_PASSWORD")
            val envKeyAlias = System.getenv("KEY_ALIAS") ?: System.getenv("JS8_KEY_ALIAS")
            val envKeyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("JS8_KEY_PASSWORD")

            if (keystorePropsFile.exists()) {
                val props = Properties().apply {
                    FileInputStream(keystorePropsFile).use { load(it) }
                }
                props.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
                props.getProperty("storePassword")?.let { storePassword = it }
                props.getProperty("keyAlias")?.let { keyAlias = it }
                props.getProperty("keyPassword")?.let { keyPassword = it }
            } else if (!envStoreFile.isNullOrBlank()) {
                storeFile = rootProject.file(envStoreFile)
                envStorePassword?.let { storePassword = it }
                envKeyAlias?.let { keyAlias = it }
                envKeyPassword?.let { keyPassword = it }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null &&
                releaseSigning.storeFile != null &&
                !releaseSigning.storePassword.isNullOrBlank() &&
                !releaseSigning.keyAlias.isNullOrBlank() &&
                !releaseSigning.keyPassword.isNullOrBlank()
            ) {
                signingConfig = releaseSigning
            } else {
                logger.warn(
                    "Release signing is not configured. Building an unsigned release APK. " +
                        "Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD " +
                        "(or JS8_* equivalents), or provide keystore.properties."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // JS8Call core library
    implementation(project(":js8core-lib"))
    implementation(project(":llm"))

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Map tab: MapLibre Native (BSD-2), vector maps, works offline with local tiles
    // OpenGL ES backend (13.x defaults to Vulkan); 13.1+ fixes the HeatmapLayer crash (#4217).
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // LocalBroadcastManager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
