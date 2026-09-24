plugins {
    alias(libs.plugins.android.library)
}


kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.catanddev.rtsp"
    compileSdk = 37

    defaultConfig {
        // Android 9 (API 28) — минимальная поддерживаемая версия
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        targetSdk = 36
    }

    buildFeatures {
        // Нужен BuildConfig.DEBUG для отключения debug-логов в release-сборках
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.media3.container)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.jcodec)
}
