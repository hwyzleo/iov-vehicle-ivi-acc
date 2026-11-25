pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The IVI-SEC contract AAR is published by iov-vehicle-ivi-sec (`:sec-api:publishToMavenLocal`).
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "iov-vehicle-ivi-acc"

include(":acc-domain")
include(":acc-data-local")
include(":acc-data-cloud")
include(":acc-platform")
include(":acc-data-sec")
include(":app")
