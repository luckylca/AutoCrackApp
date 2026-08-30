plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.luckylca.simplehook.testapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.luckylca.simplehook.testapp"
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

dependencies {
    testImplementation(libs.junit)
}
