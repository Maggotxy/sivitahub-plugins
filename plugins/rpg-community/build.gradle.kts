dependencies {
    implementation(project(":plugins:rpg-api"))
    // 文案键解析测试直接读 rpg-core 的 lang/*.yml（与运行时 YamlMessageService 同一份资源）。
    testImplementation("org.yaml:snakeyaml:2.4")
    // 治理事件消费者测试要直接构造 Bukkit 事件，测试类路径必须有 paper-api（与运行时同版本）。
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}

// 文案键解析测试需要能读到 rpg-core 的语言资源，因此把它的 resources 目录挂进测试运行时。
tasks.named<ProcessResources>("processTestResources") {
    dependsOn(":plugins:rpg-core:processResources")
    from(project(":plugins:rpg-core").layout.projectDirectory.dir("src/main/resources"))
}
