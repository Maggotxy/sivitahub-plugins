description = "Non-blocking integrations for mature third-party plugin APIs"

repositories {
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    api(project(":plugins:rpg-api"))
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("net.luckperms:api:5.5")
    // BetterHud 上游未公开发布 2.1.0-SNAPSHOT-447；本构建固定到最近可用正式版
    // 1.14.1-bukkit（libs/BetterHud-bukkit-1.14.1.jar，由 scripts/fetch-deps.sh 拉取并校验）。
    compileOnly(files(rootProject.file("libs/BetterHud-bukkit-1.14.1.jar")))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}
