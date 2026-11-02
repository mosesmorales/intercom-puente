plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.intercompuente"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.intercompuente"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Firma fija: así cada APK nuevo se instala encima del anterior sin desinstalar.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
