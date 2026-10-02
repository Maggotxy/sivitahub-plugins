description = "Non-blocking integrations for mature third-party plugin APIs"

repositories {
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    api(project(":plugins:rpg-api"))
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("net.luckperms:api:5.5")
    // BetterHud 上游从未公开发布 2.1.0-SNAPSHOT-447，而运行时用的正是这一版；
    // 用近似版本会因 API 漂移而编译失败或产生语义差异，因此固定到与运行时同版本的
    // vendor jar（libs/vendor/，由人类从生产机取回，见 libs/vendor/README.md）。
    compileOnly(files(rootProject.file("libs/vendor/BetterHud-bukkit-2.1.0-SNAPSHOT-447.jar")))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}
