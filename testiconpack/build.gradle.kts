// Tiny icon pack used only by the emulator check (not shipped).
plugins {
    id("com.android.application")
}

android {
    namespace = "com.anuki.testiconpack"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.anuki.testiconpack"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
