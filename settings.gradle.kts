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
        // Agora's RTC SDK.
        mavenCentral()
        // The Nosmai SDK ships as a local .aar rather than from a repository.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "NosmaiAgoraExample"
include(":app")
