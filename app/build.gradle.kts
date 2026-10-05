plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ytdownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ytdownloader"
        minSdk = 24
        targetSdk = 34
        // Автоматическое повышение версии при каждой сборке на GitHub Actions
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 24
        versionName = "1.0.${(project.findProperty("versionCode") as? String) ?: "24"}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Оптимизация размера: только современная 64-битная архитектура смартфонов (~35 МБ)
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Image loading for thumbnails
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // youtubedl-android (Движок yt-dlp & FFmpeg из приложения Seal)
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
}
