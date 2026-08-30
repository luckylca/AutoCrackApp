plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.luckylca.simplehook.runtime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.luckylca.simplehook.runtime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":simplehook-core"))
    compileOnly(project(":xposed-api-stubs"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
