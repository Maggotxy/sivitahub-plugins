description = "Reproducible isolated hub, native mannequin NPCs and public map landmarks"

repositories {
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    testImplementation("org.yaml:snakeyaml:2.4")
    compileOnly("xyz.jpenilla:squaremap-api:1.3.15")
    // WorldGuard publishes strict Guava/Gson constraints that conflict with
    // Paper 26.2's API metadata. The server bootstrap already pins both jars,
    // so compile against the same runtime artifacts without resolving their
    // transitive dependency graphs.
    compileOnly(files(
        rootProject.file("server/runtime/plugins/worldguard-bukkit-7.0.18-Sivita.jar"),
        rootProject.file("server/runtime/plugins/worldedit-bukkit-7.4.4.jar"),
        rootProject.file("server/runtime/plugins/AuthMe-6.0.0-Paper.jar"),
    ))
}
