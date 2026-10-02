description = "Unified player menu, searchable help and onboarding guidance"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly(files(rootProject.file("libs/AuthMe-6.0.0-Paper.jar")))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
}
