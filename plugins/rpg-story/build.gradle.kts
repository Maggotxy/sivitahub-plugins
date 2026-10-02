description = "Citizens actors and BetonQuest orchestration bridged to authoritative RPG services"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly(files(
        rootProject.file("libs/Citizens-2.0.43-b4231.jar"),
        rootProject.file("libs/BetonQuest-3.1.0.jar"),
    ))
    testImplementation(project(":plugins:rpg-api"))
}
