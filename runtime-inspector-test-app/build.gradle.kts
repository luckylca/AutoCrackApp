plugins {
    alias(libs.plugins.android.application)
}

val delayedClasses = layout.buildDirectory.dir("intermediates/runtimeTestDelayed/classes")
val delayedIntermediate = layout.buildDirectory.dir("intermediates/runtimeTestDelayed")
val delayedJarFile = delayedIntermediate.map { it.file("delayed.jar") }
val delayedAssets = layout.buildDirectory.dir("generated/runtimeTestDelayed/assets")

android {
    namespace = "com.luckylca.runtimeinspector.testapp"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.luckylca.runtimeinspector.testapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(delayedAssets)

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

val compileDelayedTarget by tasks.registering(JavaCompile::class) {
    source(fileTree("src/delayed/java") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(delayedClasses)
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}

val packageDelayedTarget by tasks.registering(Jar::class) {
    dependsOn(compileDelayedTarget)
    from(delayedClasses)
    archiveFileName.set("delayed.jar")
    destinationDirectory.set(delayedIntermediate)
}

val dexDelayedTarget by tasks.registering(Exec::class) {
    dependsOn(packageDelayedTarget)
    inputs.file(delayedJarFile)
    outputs.file(delayedAssets.map { it.file("delayed/classes.dex") })
    doFirst {
        val output = delayedAssets.get().dir("delayed").asFile
        output.mkdirs()
        output.resolve("classes.dex").delete()
        commandLine(
            android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}/d8"),
            "--min-api", "26", "--output", output,
            delayedJarFile.get().asFile,
        )
    }
}

tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true)) {
        dependsOn(dexDelayedTarget)
    }
}

dependencies {
    testImplementation(libs.junit)
}
