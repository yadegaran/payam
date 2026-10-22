// Google Maven is filtered from Iran, so Iranian mirrors come first locally.
// On CI (GitHub runners abroad) they are skipped: some of them refuse foreign IPs.
val iranMirrors = System.getenv("CI") == null

pluginManagement {
    repositories {
        if (System.getenv("CI") == null) {
            maven {
                url = uri("https://maven.myket.ir")
                content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
            }
            maven { url = uri("https://en-mirror.ir") }
        }
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
        if (iranMirrors) {
            maven { url = uri("https://maven.myket.ir") }
            maven { url = uri("https://en-mirror.ir") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Payam"
include(":app")
