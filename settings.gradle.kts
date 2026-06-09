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

rootProject.name = "fakelocation-repro-v1"

include(
    ":app",
    ":core-model",
    ":core-ipc",
    ":core-runtime",
    ":core-hookbridge",
    ":injector-orchestrator",
    ":payload-shared",
)
