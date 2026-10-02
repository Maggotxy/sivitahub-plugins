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
    // worldguard 是 Sivita 自改版（公网不可得），人类从生产机取回后放 libs/vendor/。
    // worldedit 7.4.4 的 bukkit 发布物不含 BukkitAdapter/BlockVector2（本模块实际用到的类），
    // 故按 core + bukkit 两个 artifact 组合引用，二者都由 scripts/fetch-deps.sh 拉取。
    compileOnly(files(
        rootProject.file("libs/vendor/worldguard-bukkit-7.0.18-Sivita.jar"),
        rootProject.file("libs/worldedit-core-7.4.4.jar"),
        rootProject.file("libs/worldedit-bukkit-7.4.4.jar"),
        rootProject.file("libs/AuthMe-6.0.0-Paper.jar"),
    ))
}
