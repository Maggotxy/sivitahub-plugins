description = "Player-adaptive monsters, elite affixes and level-gap loot rules"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:3.3.0")
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    testImplementation("org.yaml:snakeyaml:2.4")
}
