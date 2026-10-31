pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        mavenCentral()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Picks the Loom variant per MC version: fabric-loom-remap up to 1.21.11 (obfuscated), fabric-loom on 26.x (unobfuscated).
    // The Loom version is loomx.loom_version in gradle.properties.
    id("dev.kikugie.loom-back-compat") version "0.4.3"
}

stonecutter {
    create(rootProject) {
        // Ascending. Each version needs versions/<v>/gradle.properties.
        versions("1.21.11", "26.1.2", "26.2", "26.3")
        // The form src/ is committed in
        vcsVersion = "1.21.11"
    }
}

rootProject.name = "smp-economy-plus"
