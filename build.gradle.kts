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
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        targetSdk = 36
    }
}

dependencies {
    implementation(libs.androidx.media3.container)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.jcodec)
}
