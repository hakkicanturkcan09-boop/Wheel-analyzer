plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wheelanalyzer.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.wheelanalyzer.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-prototype"
    }
}
