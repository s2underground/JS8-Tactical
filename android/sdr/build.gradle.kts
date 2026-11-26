plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// RTL-SDR receive (issue #54): libusb + librtlsdr (vendored at /vendor/libusb and /vendor/rtl-sdr)
// and the SSB receiver, behind com.js8call.sdr.RtlSdr.
android {
    namespace = "com.js8call.sdr"
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
                )
                targets += listOf("js8sdr")
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
