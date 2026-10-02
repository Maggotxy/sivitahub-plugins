plugins {
    application
}

dependencies {
    implementation("com.h2database:h2:2.3.232")
    implementation("org.postgresql:postgresql:42.7.7")
}

application {
    mainClass.set("cn.originalrpg.tools.dbmigration.MigrationMain")
    applicationName = "sivita-db-migration"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.withType<Test>().configureEach {
    // SchemaManifestContractTest reads RpgCore's authoritative migration
    // source directly. Declare it as an input so Gradle's build cache cannot
    // reuse a green result after a new gameplay table is added.
    inputs.file(rootProject.file(
        "plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java"
    ))
    systemProperty("sivita.projectRoot", rootProject.projectDir.absolutePath)
    listOf(
        "SIVITA_MIGRATION_TEST_POSTGRES_URL",
        "SIVITA_MIGRATION_TEST_POSTGRES_USER",
        "SIVITA_MIGRATION_TEST_POSTGRES_PASSWORD"
    ).forEach { variable ->
        System.getenv(variable)?.let { environment(variable, it) }
    }
}
