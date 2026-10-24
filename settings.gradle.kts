rootProject.name = "Pillion"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // libadb-android + sun-security-android
    }
}

include(":composeApp", ":libadbPatched")
project(":libadbPatched").projectDir = file("third_party/libadb-android")
