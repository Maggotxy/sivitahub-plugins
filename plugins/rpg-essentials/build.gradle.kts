description = "RPG-safe EssentialsX command and teleport bridge"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly(files(rootProject.file("libs/vendor/EssentialsX-2.22.1-dev-382de4f.jar")))
}
