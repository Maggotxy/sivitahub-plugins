description = "MyPet-backed RPG companion rules, progression and unified-economy integration"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly(files("${rootProject.projectDir}/server/vendor/mypet/MyPet-4.0.0-Sivita.jar"))
    testImplementation("org.yaml:snakeyaml:2.4")
}
