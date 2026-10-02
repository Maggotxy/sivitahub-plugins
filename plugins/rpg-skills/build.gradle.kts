description = "Attribute-gated class skill trees and skill-book extension points"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    compileOnly(files(rootProject.file("libs/AuthMe-6.0.0-Paper.jar")))
    testImplementation(project(":plugins:rpg-api"))
    testImplementation("org.yaml:snakeyaml:2.4")
}
