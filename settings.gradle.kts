pluginManagement {
    repositories {
        google()
        maven { url = uri("https://maven-central-asia.storage-download.googleapis.com/maven2/") }
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" apply false
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven-central-asia.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "RUTA"
include(":app")
