description = "Forging, item identity, binding, slots and gradual revelation"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    testImplementation("org.yaml:snakeyaml:2.4")
}

// W3-A3：行为级验证需要驱动真实出站（`JdbcEquipmentOperationService` + 装备账本）
// 到终态，因此测试编译 rpg-core 的持久化源码；生产编译面不变（仍只依赖 rpg-api）。
val corePersistence = sourceSets.create("corePersistence") {
    java.srcDir(rootProject.project(":plugins:rpg-core").file("src/main/java"))
}

dependencies {
    "corePersistenceImplementation"(project(":plugins:rpg-api"))
    "corePersistenceImplementation"("org.yaml:snakeyaml:2.4")
    "corePersistenceCompileOnly"("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    "corePersistenceCompileOnly"("com.zaxxer:HikariCP:7.0.2")
    "corePersistenceCompileOnly"("com.h2database:h2:2.3.232")
    "corePersistenceCompileOnly"("org.postgresql:postgresql:42.7.7")

    testImplementation(project(":plugins:rpg-core"))
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation(files(corePersistence.output))
}

tasks.named<JavaCompile>("compileTestJava") {
    dependsOn(tasks.named("compileCorePersistenceJava"))
}
