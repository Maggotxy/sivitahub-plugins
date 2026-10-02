description = "Attribute-gated class skill trees and skill-book extension points"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly(files(rootProject.file("libs/AuthMe-6.0.0-Paper.jar")))
    testImplementation(project(":plugins:rpg-api"))
    // 行为级测试需要真实的 ConfigurationSection（隐藏路线可达性自检读 live config）
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    testImplementation("org.yaml:snakeyaml:2.4")
}
