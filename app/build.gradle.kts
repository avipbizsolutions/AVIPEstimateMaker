plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.avip.estimatemaker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avip.estimatemaker"
        minSdk = 26
        targetSdk = 36
        // GitHub Actions sets VERSION_CODE for every build, so each release is newer
        // than the last (needed both for Google Play and for the GitHub self-update).
        val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // GitHub (sideloaded) build: same key as every earlier APK, so updates install over them.
        create("avip") {
            storeFile = file("release.keystore")
            storePassword = "avipest2026"
            keyAlias = "avipestimate"
            keyPassword = "avipest2026"
        }
        // Google Play build: private upload key, supplied by GitHub Actions secrets
        // (PLAY_UPLOAD_KEYSTORE, PLAY_UPLOAD_PASSWORD). Never committed to the repo.
        val uploadStore = System.getenv("PLAY_UPLOAD_KEYSTORE_FILE")
        if (uploadStore != null && file(uploadStore).exists()) {
            create("playUpload") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("PLAY_UPLOAD_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("PLAY_UPLOAD_PASSWORD")
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        // Published on Google Play. No self-update; Play installs updates.
        create("play") {
            dimension = "store"
            applicationId = "in.avipsolar.biharsolarcircle"
            signingConfig = signingConfigs.findByName("playUpload")
        }
        // The APK shared from GitHub, which updates itself. Kept so existing users can
        // be pointed to the Play Store version.
        create("github") {
            dimension = "store"
            applicationId = "com.avip.estimatemaker"
            signingConfig = signingConfigs.getByName("avip")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Google Drive linking (Authorization API, drive.file scope)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
