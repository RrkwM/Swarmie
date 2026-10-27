import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        // The verified local store is used when present; a fresh public clone
        // resolves the same artifacts from the official repositories.
        if (settingsDir.resolve("tools/repo").isDirectory) {
            maven {
                name = "swarmieLocal"
                url = settingsDir.resolve("tools/repo").toURI()
                metadataSources {
                    mavenPom()
                    gradleMetadata()
                    artifact()
                }
            }
        }
        maven {
            name = "fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Loom adds project-level repositories for Minecraft artifacts and its
    // local remapped-mod/Minecraft caches. FAIL_ON_PROJECT_REPOS and
    // PREFER_SETTINGS both break that resolution. PREFER_PROJECT is required
    // for Loom 1.19.3; offline verification uses the explicit --offline flag.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        if (settingsDir.resolve("tools/repo").isDirectory) {
            maven {
                name = "swarmieLocal"
                url = settingsDir.resolve("tools/repo").toURI()
            }
        }
        maven {
            name = "fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        maven {
            name = "minecraftLibraries"
            url = uri("https://libraries.minecraft.net/")
        }
    }
}

rootProject.name = "swarmie"

include(":contract")
