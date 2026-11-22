// Rsensors for Android — Gradle settings.
//
// Repositories are restricted to Google's Maven repository and Maven Central.
// No other repository is declared: the app ships no network SDKs and its sensor
// data never leaves the phone (ANDROID.md §2.2).
pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
  }
}

rootProject.name = "Rsensors"

include(":app")
