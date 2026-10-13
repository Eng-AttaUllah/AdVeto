plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Package: org.adveto.app — matches the proposal's seven-stage prototype.
    namespace = "org.adveto.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.adveto.app"
        // Proposal: Android 10-15 (API 29-35).
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Research prototype: minification is off so stack traces stay readable.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
