plugins {
    id("dev.kikugie.loom-back-compat")
}

version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

val requiredJava: JavaVersion = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Mojang mappings on obfuscated versions, nothing on 26.x
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
}

loom {
    runConfigs.all {
        runDirectory = rootProject.file("run") // shared between versions
    }
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "femboyware-test"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

java {
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = requiredJava.majorVersion.toInt()
        options.compilerArgs.addAll(listOf("-Xmaxerrs", "3000"))
    }

    processResources {
        val props = mapOf(
            "version" to project.version.toString(),
            "minecraft" to sc.properties.get<String>("mod.mc_compat"),
            "java" to requiredJava.majorVersion,
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
        filesMatching("*.mixins.json") { expand(props) }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/${project.property("mod.version")}"))
        dependsOn("build")
    }
}
