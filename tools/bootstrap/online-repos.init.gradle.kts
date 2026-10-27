// Provisioning-only Gradle init script (P1-T1c).
//
// This file is NEVER referenced by settings.gradle.kts, build.gradle.kts, or
// gradle.properties. It is passed explicitly with -I only by
// tools/scripts/provision.ps1, which requires -ApproveNetwork.
//
// It adds the official remote repositories needed for the first online
// resolution. Normal builds continue to see only tools/repo (file:).

beforeSettings {
    pluginManagement.repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
        gradlePluginPortal()
        mavenCentral()
    }
    dependencyResolutionManagement.repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
        mavenCentral()
    }
}
