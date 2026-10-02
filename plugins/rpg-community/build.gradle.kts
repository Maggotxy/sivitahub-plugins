dependencies {
    implementation(project(":plugins:rpg-api"))
    // 文案键解析测试直接读 rpg-core 的 lang/*.yml（与运行时 YamlMessageService 同一份资源）。
    testImplementation("org.yaml:snakeyaml:2.4")
}

// 文案键解析测试需要能读到 rpg-core 的语言资源，因此把它的 resources 目录挂进测试运行时。
tasks.named<ProcessResources>("processTestResources") {
    dependsOn(":plugins:rpg-core:processResources")
    from(project(":plugins:rpg-core").layout.projectDirectory.dir("src/main/resources"))
}
