plugins {
    alias(libs.plugins.android.application)
}

val delayedIntermediate = layout.buildDirectory.dir("intermediates/simplehookDelayed")
val delayedClasses = delayedIntermediate.map { it.dir("classes") }
val delayedJarFile = delayedIntermediate.map { it.file("delayed.jar") }
val delayedAssets = layout.buildDirectory.dir("generated/simplehookDelayed/assets")

android {
    namespace = "com.luckylca.simplehook.testapp"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

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

    sourceSets["main"].assets.srcDir(delayedAssets)
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
            delayedJarFile.get().asFile
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
