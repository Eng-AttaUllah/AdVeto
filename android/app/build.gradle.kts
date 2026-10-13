plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")          // Room annotation processor
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

    // KSP needs the schema location so Room migrations are checkable later.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Stage (7): SQLite via Room for the local event log.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Stage (3b) — on-device text recognition (proposal §10, ML Kit).
    // Bundled model: no network, no cloud call, consistent with §8 privacy.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Age-policy decision cases are pure JVM tests — they run without a device
    // (./gradlew test), which is what makes deliverable 3 executable.
    testImplementation("junit:junit:4.13.2")
}
