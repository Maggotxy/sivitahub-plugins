description = "RPG and vanilla activity rewards, commissions and earned progression shop"

dependencies {
    testImplementation(project(":plugins:rpg-api"))
    compileOnly(project(":plugins:rpg-api"))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    testImplementation("org.yaml:snakeyaml:2.4")
}
