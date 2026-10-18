plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anuki.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anuki.launcher"
        minSdk = 26
        targetSdk = 34
        // CI passes its run number so every build can be installed over the previous one
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.0.$run"
    }

    // Stable release key comes from GitHub secrets (see .github/workflows/build.yml).
    // Without it the release build falls back to the debug key.
    val keystorePath = System.getenv("ANUKI_KEYSTORE")
    signingConfigs {
        create("release") {
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANUKI_KEYSTORE_PASS")
                keyAlias = "nyang" // alias stored inside the existing key file
                keyPassword = System.getenv("ANUKI_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null && file(keystorePath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
