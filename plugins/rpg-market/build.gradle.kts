description = "Escrow market boundary and anti-bypass ownership enforcement"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("org.yaml:snakeyaml:2.4")
}
