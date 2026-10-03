description = "Energy-gated dungeon sessions, hidden tickets and return safety"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    testImplementation(project(":plugins:rpg-api"))
    // G-302-RESIDUAL-2：超时/会话契约测试改成真跑（装动态代理 Server/Plugin/World 后真实构造
    // DungeonManager），测试类路径必须有与运行时同版本的 paper-api。生产编译面不变（仍只 compileOnly rpg-api）。
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    testImplementation("org.yaml:snakeyaml:2.4")
}
