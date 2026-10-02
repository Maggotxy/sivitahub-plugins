description = "Audited administration and development commands"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    // 换季管理面的行为级测试跑在真 JDBC 事务上：换季状态机是 rpg-core 的实现，
    // 用假的「状态机」测「入口接上了」会同时放过实现里的崩溃一致性问题。
    testImplementation(project(":plugins:rpg-core"))
    testImplementation("com.zaxxer:HikariCP:7.0.2")
    testImplementation("com.h2database:h2:2.3.232")
}
