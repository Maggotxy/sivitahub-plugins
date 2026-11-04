description = "Disposable Paper startup probe; never part of the 25-plugin release"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}

val manifest = layout.buildDirectory.file("generated/smoke/expected-plugins.tsv")
val generateSmokeManifest = tasks.register<Exec>("generateSmokeManifest") {
    inputs.file(rootProject.file("scripts/gen-runtime-smoke-own-plugins.py"))
    inputs.files(rootProject.subprojects.filter { it.path.startsWith(":plugins:rpg-") }
        .map { it.file("src/main/resources/plugin.yml") })
    outputs.file(manifest)
    commandLine("python3", rootProject.file("scripts/gen-runtime-smoke-own-plugins.py").absolutePath,
        "--output", manifest.get().asFile.absolutePath)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateSmokeManifest)
    from(manifest)
}
