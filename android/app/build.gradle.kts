plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.lumen.assistant"
    compileSdk = 35
    ndkVersion = "29.0.14206865" // the same NDK as llama.cpp (android/llama), so one C++ runtime serves both

    defaultConfig {
        applicationId = "ai.lumen.assistant"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // your own key, so each new build installs over the last one: put it at signing/lumen.keystore and set
    // LUMEN_KEYSTORE_PASSWORD (never commit it). Without it, the standard Android debug key is used.
    val ks = rootProject.file("signing/lumen.keystore")
    if (ks.exists()) signingConfigs {
        getByName("debug") {
            storeFile = ks
            storePassword = System.getenv("LUMEN_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("LUMEN_KEY_ALIAS") ?: "lumen"
            keyPassword = System.getenv("LUMEN_KEYSTORE_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files("../tsbridge/out/tsbridge.aar")) // Tailscale inside the app: build it with tsbridge/build.sh
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
