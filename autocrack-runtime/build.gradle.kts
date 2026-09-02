plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.luckylca.autocrack.runtime"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.luckylca.autocrack.runtime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        "../simplehook-runtime/src/main/java",
        "../runtime-inspector-runtime/src/main/java",
    )
    sourceSets["main"].java.exclude(
        "**/SimpleHookXposedEntry.java",
        "**/InspectorXposedEntry.java",
        "**/InspectorProvider.java",
        "**/InspectorResultReceiver.java",
    )

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":simplehook-core"))
    compileOnly(project(":xposed-api-stubs"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
