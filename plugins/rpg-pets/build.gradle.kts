description = "MyPet-backed RPG companion rules, progression and unified-economy integration"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    // Sivita 自改版，公网不可得：人类从生产机取回后放 libs/vendor/（fetch-deps.sh 不下载）
    compileOnly(files(rootProject.file("libs/vendor/MyPet-4.0.0-Sivita.jar")))
    testImplementation("org.yaml:snakeyaml:2.4")
}
