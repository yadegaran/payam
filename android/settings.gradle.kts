pluginManagement {
    repositories {
        // Google Maven is filtered from Iran, so Iranian mirrors come first; elsewhere they just fall through to google().
        maven {
            url = uri("https://maven.myket.ir")
            content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
        }
        maven { url = uri("https://en-mirror.ir") }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://en-mirror.ir") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Payam"
include(":app")
