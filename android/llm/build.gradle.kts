plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// On-device language model (llama.cpp, vendored at /vendor/llama.cpp) for the optional AI
// summary. Always built optimised: an unoptimised debug build is ~10x slower and would make
// every speed test meaningless.
android {
    namespace = "com.js8call.llm"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // 64-bit ARM only: every phone or tablet able to run a model has it.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26",
                )
                targets += listOf("js8llm")
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
