description = "Profiles, progression, energy, currency ledger and shared persistence"

dependencies {
    api(project(":plugins:rpg-api"))
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    compileOnly("com.h2database:h2:2.3.232")
    compileOnly("org.postgresql:postgresql:42.7.7")
    testImplementation("com.zaxxer:HikariCP:7.0.2")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    testImplementation("org.yaml:snakeyaml:2.4")
}
