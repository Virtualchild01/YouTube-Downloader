plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Версия берётся из gradle.properties, релизный workflow переопределяет её через -PversionCode/-PversionName.
// Важно: versionCode должен строго возрастать, иначе Android откажется ставить обновление поверх.
val appVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 10040
val appVersionName = project.findProperty("versionName")?.toString() ?: "1.0.40"

android {
    namespace = "com.example.ytdownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ytdownloader"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Оптимизация размера: только 64-битная архитектура современных смартфонов
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        // debug и release подписываются ОДНИМ И ТЕМ ЖЕ ключом.
        // Если ключ сменить, обновление поверх установленной версии упадёт с
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE — пользователю придётся удалять приложение.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            // R8 выключен осознанно: Jackson разбирает JSON в VideoInfo/VideoFormat по аннотациям,
            // а youtubedl-android не поставляет consumer-rules. Включать после проверки на устройстве.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
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

    // Галерея и встроенный плеер
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    val media3 = "1.2.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // youtubedl-android (движок yt-dlp & FFmpeg из приложения Seal)
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
}
