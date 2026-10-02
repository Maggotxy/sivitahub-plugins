description = "Attribute-gated class skill trees and skill-book extension points"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly(files(rootProject.file("libs/AuthMe-6.0.0-Paper.jar")))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("org.yaml:snakeyaml:2.4")
    // 事件级接线测试要直接构造 Bukkit 伤害事件，测试类路径必须有 paper-api
    // （与 rpg-combat 同一做法，不引入新的第三方依赖）。
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}
