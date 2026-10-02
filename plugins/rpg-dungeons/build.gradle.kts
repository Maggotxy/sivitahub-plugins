description = "Energy-gated dungeon sessions, hidden tickets and return safety"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("org.yaml:snakeyaml:2.4")
}
