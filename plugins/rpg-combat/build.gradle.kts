description = "Shared combat policies, execute caps and PvE/PvP normalization"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    testImplementation(project(":plugins:rpg-api"))
    // 事件级接线测试要直接构造 Bukkit 事件，测试类路径必须有 paper-api。
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}
