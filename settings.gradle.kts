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
        // Published Android AAR contains the libtorrent native binaries and R8 rules.
        maven { url = uri("https://dl.frostwire.com/maven") }
    }
}

rootProject.name = "AudioShelf"
include(":app")
