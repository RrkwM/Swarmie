plugins {
    alias(libs.plugins.loom)
}

// Minecraft 26.3 ships unobfuscated and Loom uses split client/server jars
// instead of a merged jar. This mirrors the official 26.3 example mod.
loom {
    splitEnvironmentSourceSets()
    mods {
        create("swarmie") {
            sourceSet(sourceSets.getByName("main"))
            sourceSet(sourceSets.getByName("client"))
        }
    }
    runs {
        named("client") {
            if (providers.gradleProperty("swarmieQuickPlay").isPresent) {
                programArgs("--quickPlaySingleplayer", providers.gradleProperty("swarmieQuickPlay").get())
            }
        }
    }
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

base {
    archivesName.set(providers.gradleProperty("archives_base_name").get())
}

dependencies {
    // Loom exposes these as dynamic configuration names rather than Kotlin DSL
    // extension functions; the string-invoke form is the stable Kotlin DSL API.
    "minecraft"("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    // Minecraft 26.3 ships unobfuscated; Loom rejects officialMojangMappings()
    // in a non-obfuscated environment, so no mappings dependency is declared.
    // Minecraft 26.3 ships unobfuscated; Loom applies its no-remap plugin,
    // which does not create the modImplementation configuration.
    "implementation"("net.fabricmc:fabric-loader:${libs.versions.loader.get()}")
    "implementation"("net.fabricmc.fabric-api:fabric-api:${libs.versions.fabricApi.get()}")
    "implementation"(project(":contract"))

    // Local test framework only; these do not enter the production runtime.
    testImplementation("org.junit.jupiter:junit-jupiter-api:${libs.versions.junit.get()}")
    testImplementation("org.junit.jupiter:junit-jupiter-params:${libs.versions.junit.get()}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${libs.versions.junit.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${libs.versions.junitPlatform.get()}")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(libs.versions.java.get().toInt())
}

tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    dependsOn(":contract:test")
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<Jar>("jar") {
    from(project(":contract").sourceSets.main.get().output)
}

tasks.named<Jar>("sourcesJar") {
    from(project(":contract").layout.projectDirectory.dir("src/main/java"))
}

tasks.processResources {
    val expandProps = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to libs.versions.minecraft.get(),
        "loader_version" to libs.versions.loader.get()
    )
    inputs.properties(expandProps)
    filesMatching("fabric.mod.json") {
        expand(expandProps)
    }
}
