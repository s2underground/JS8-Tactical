import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.js8call.example"

    lint {
        // Known findings are recorded in lint-baseline.xml and worked off over time; anything new
        // (including warnings) fails lint. Refresh after fixing some: ./gradlew :app:updateLintBaseline
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = true
        // The app logs with android.util.Log on purpose (the crash reporter reads logcat);
        // it does not use Timber, whose lint check would flag every log call.
        disable += "LogNotTimber"
        // The app is English-only and much of its text is radio data (callsigns, frequencies, SNR) that
        // isn't translated; this check flags every such setText. Localisation isn't a security or
        // correctness issue; locale-sensitive number formatting is still caught by DefaultLocale.
        disable += "SetTextI18n"
        // Dependency and target-SDK freshness changes whenever upstream ships a release, which would
        // fail unchanged code; Dependabot tracks dependency updates instead.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
    compileSdk = 36
    // NDK r28 builds 16 KB-page-compatible libraries (and ships a 16 KB libc++_shared). A machine
    // that only has an older NDK can build with -Pjs8.ndkVersion=<version>.
    ndkVersion = (findProperty("js8.ndkVersion") as String?) ?: "28.2.13676358"

    defaultConfig {
        applicationId = "com.s2underground.js8tactical"
        // The radio engine is built for ARM only, so the x86 map libraries MapLibre brings are dead weight.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        minSdk = 26
        targetSdk = 34
        // CI builds count up with the workflow run, so every build (rolling test build or tagged
        // release) installs over the one before it. Local builds stay at 5.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { 100 + it } ?: 5
        versionName = "1.2.0-alpha.5"

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

    // The release key is available (CI secrets or keystore.properties).
    val releaseReady = signingConfigs.findByName("release")?.let {
        it.storeFile?.exists() == true && !it.storePassword.isNullOrBlank() &&
            !it.keyAlias.isNullOrBlank() && !it.keyPassword.isNullOrBlank()
    } == true

    buildTypes {
        getByName("debug") {
            // Test builds say so in their version ("1.2.0-alpha.1-test"), and are signed with the
            // release key when it's available, so they install over a release and vice versa.
            versionNameSuffix = "-test"
            if (releaseReady) signingConfig = signingConfigs.getByName("release")
            // Line coverage of the unit tests: :app:createDebugUnitTestCoverageReport
            // (report in app/build/reports/coverage/test/debug; CI posts the totals).
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseReady) {
                signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.10.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.10.2")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Map tab: MapLibre Native (BSD-2), vector maps, works offline with local tiles
    // OpenGL ES backend (13.x defaults to Vulkan); 13.1+ fixes the HeatmapLayer crash (#4217).
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")

    // Material Design
    implementation("com.google.android.material:material:1.14.0")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // No longer used by the app (see AppBroadcasts); kept pinned because legacy-support-core-utils
    // depends on it and would otherwise resolve the older 1.0.0.
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

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
