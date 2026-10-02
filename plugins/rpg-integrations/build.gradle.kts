description = "Non-blocking integrations for mature third-party plugin APIs"

repositories {
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    api(project(":plugins:rpg-api"))
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("net.luckperms:api:5.5")
    compileOnly(files(rootProject.file(
        "server/runtime/plugins/BetterHud-bukkit-2.1.0-SNAPSHOT-447.jar"
    )))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}
