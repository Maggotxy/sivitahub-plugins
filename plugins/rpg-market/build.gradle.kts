description = "Escrow market boundary and anti-bypass ownership enforcement"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("org.yaml:snakeyaml:2.4")
    // 事件级接线测试要反射读取 Bukkit 事件处理器，测试类路径必须有 paper-api。
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}
