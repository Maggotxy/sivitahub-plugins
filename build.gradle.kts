import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    base
}

allprojects {
    group = "cn.originalrpg"
    version = providers.gradleProperty("projectVersion").get()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }

    dependencies {
        if (path.startsWith(":plugins:")) {
            add("compileOnly", "io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
        }
        add("testImplementation", platform("org.junit:junit-bom:5.13.4"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:all")
    }

    val expandedPluginVersion = version
    val expandedPaperApi = rootProject.property("minecraftVersion")
    tasks.withType<ProcessResources>().configureEach {
        filesMatching("plugin.yml") {
            expand(mapOf("version" to expandedPluginVersion, "paperApi" to expandedPaperApi))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            events("failed", "skipped")
        }
    }
}

val buildResourcePack = tasks.register<Exec>("buildResourcePack") {
    group = "distribution"
    description = "Validate and build the Minecraft 26.2 resource pack used by staged plugins."
    workingDir(layout.projectDirectory)
    commandLine("bash", layout.projectDirectory.file("resource-pack/build.sh").asFile.absolutePath)
    outputs.upToDateWhen { false }
}

val customPluginProjects = subprojects.filter { it.path.startsWith(":plugins:rpg-") }
val pluginCandidateDirectory = layout.projectDirectory.dir("server/deploy-candidates/custom-plugins")

val stagePluginCandidates = tasks.register<Sync>("stagePluginCandidates") {
    dependsOn(customPluginProjects.map { pluginProject ->
        pluginProject.tasks.named("jar")
    })
    from(customPluginProjects.map { it.layout.buildDirectory.dir("libs") })
    include("*.jar")
    exclude("*-sources.jar")
    into(pluginCandidateDirectory)
}

val stagePlugins = tasks.register<Exec>("stagePlugins") {
    group = "distribution"
    description = "Validate and atomically stage all custom plugins while Paper is stopped."
    dependsOn(stagePluginCandidates)
    workingDir(layout.projectDirectory)
    commandLine(
        "bash",
        layout.projectDirectory.file("server/bin/stage-custom-plugins.sh").asFile.absolutePath,
        "--from",
        pluginCandidateDirectory.asFile.absolutePath,
    )
}

tasks.register("stageRelease") {
    group = "distribution"
    description = "Validate the resource pack before staging the matching plugin jars."
    dependsOn(buildResourcePack, stagePlugins)
}

// A normal release must publish the pack before the jars that can reference
// its models.  `stagePlugins` deliberately remains independently callable for
// the guarded first phase of a BetterHud layout migration: Paper has to read
// the new HUD YAML once before its generated pack can be synchronized.
stagePlugins.configure {
    mustRunAfter(buildResourcePack)
}
