pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AutoCrackApp"
include(":app")
include(":simplehook-core")
include(":simplehook-runtime")
include(":simplehook-test-app")
include(":xposed-api-stubs")
