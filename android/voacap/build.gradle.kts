plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// VOACAP for the Prop tab (issue #57): the hfcast Rust port, vendored at /vendor/hfcast-engine,
// behind com.js8call.voacap.Voacap. Building it needs cargo with the toolchain and Android targets
// named in rust/rust-toolchain.toml (CI installs them).
android {
    namespace = "com.js8call.voacap"
    compileSdk = 36
    ndkVersion = (findProperty("js8.ndkVersion") as String?) ?: "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DJS8_VOACAP_STUB=" + (if ((findProperty("js8.voacapStub") as String?) == "true") "ON" else "OFF"),
                )
                targets += listOf("js8voacap")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
