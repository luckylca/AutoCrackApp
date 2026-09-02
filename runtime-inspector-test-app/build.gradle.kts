plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.luckylca.runtimeinspector.testapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.luckylca.runtimeinspector.testapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
