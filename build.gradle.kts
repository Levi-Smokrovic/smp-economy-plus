// Evaluated by Stonecutter once per MC version (versions/<mc>/).
plugins {
    id("dev.kikugie.loom-back-compat")
}

fun prop(key: String): String = property(key) as String

val mc: String = sc.current.version
val modVersion = prop("mod.version")

// No `group`: nothing is published to Maven.
version = "$modVersion+$mc"
base.archivesName = prop("mod.archives")

val javaRelease: Int = if (sc.current.parsed >= "26.1") 25 else 21

repositories {
    exclusiveContent {
        forRepository { maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" } }
        filter { includeGroup("eu.pb4") }
    }
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    loomx.applyMojangMappings()

    // loom-back-compat maps the mod* configurations to the plain ones on 26.x
    modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")

    // Bundled as jar-in-jar, so servers only need Fabric API
    val sgui = "eu.pb4:sgui:${prop("deps.sgui")}"
    val perms = "me.lucko:fabric-permissions-api:${prop("deps.permissions_api")}"
    modImplementation(sgui); include(sgui)
    modImplementation(perms); include(perms)

    // The API has no logic of its own; shop and reward mods bundle it the same way.
    val economyApi = "eu.pb4:common-economy-api:${prop("deps.common_economy_api")}"
    modImplementation(economyApi); include(economyApi)
    // Optional at runtime: only touched behind FabricLoader.isModLoaded("placeholder-api").
    modCompileOnly("eu.pb4:placeholder-api:${prop("deps.placeholder_api")}")
    // Same, behind FabricLoader.isModLoaded("vanish"). Not published to a Maven of its own.
    modCompileOnly("maven.modrinth:vanish:${prop("deps.vanish")}")
}

loom {
    runConfigs.all {
        // One run directory per version: a world from a newer MC version must never open on an older one
        runDirectory = rootProject.file("run/$mc")
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaRelease)
    targetCompatibility = JavaVersion.toVersion(javaRelease)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = javaRelease
        options.encoding = "UTF-8"
    }

    // runServer and friends need the runtime of their version, not the JDK 25 Gradle runs on
    withType<JavaExec>().configureEach {
        javaLauncher = project.javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(javaRelease)
        }
    }

    processResources {
        val modProps = mapOf(
            "version" to "$modVersion+$mc",
            "mc_compat" to prop("mod.mc_compat"),
            "loader_min" to prop("mod.loader_min"),
            "java" to javaRelease.toString(),
            "homepage" to prop("mod.homepage"),
            "sources" to prop("mod.sources"),
        )
        val mixinProps = mapOf("mixin_java" to "JAVA_$javaRelease")
        inputs.properties(modProps)
        inputs.properties(mixinProps)
        filesMatching("fabric.mod.json") { expand(modProps) }
        filesMatching("*.mixins.json") { expand(mixinProps) }
    }

    // MIT requires the license text in every copy. Only on the jar task: remapJar takes over its contents on 1.21.11, and on
    // 26.x jar is the final mod jar.
    named<Jar>("jar") {
        from(rootProject.file("LICENSE")) { rename { "${it}_smpeconomy" } }
        from(rootProject.file("THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("LICENSE_GPL3")) // LGPL-3.0 (sgui) requires a copy of the GPL alongside it
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to build/release/"
        dependsOn("build")
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("release"))
    }
}
