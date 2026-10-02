description = "Load-aware cosmetic resonance auras inspired by the legacy Particles project"

dependencies {
    compileOnly(project(":plugins:rpg-api"))
    // G-205：测试要读 rpg-commerce/config.yml 对账外观定价表，需要 YAML 解析器。
    testImplementation("org.yaml:snakeyaml:2.4")
}
