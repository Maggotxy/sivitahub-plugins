import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    base
}

allprojects {
    group = "cn.originalrpg"
    version = providers.gradleProperty("projectVersion").get()

    repositories {
        // ⚠️ 依赖下载必须优先走国内镜像，否则直连 repo.maven.apache.org 会撞
        // HTTP 429 Too Many Requests（CNB 共享出口 IP，实测 429 窗口可持续
        // ~10 分钟，指数退避 30/60/120/240s 全部落窗内 —— 见
        // sn=cnb-vom-1k4p6bh4h / sn=cnb-mq0-1k4p3lnu1 / sn=cnb-lbv-1k4p4gf85）。
        // 阿里云公共仓库对 maven-central 是全量代理，先命中即不再暴露在 429 下。
        // 多仓顺序查找由 Gradle 保证：镜像没有的（如 PaperMC 的 paper-api）返回
        // 404，Gradle 自动落到下一个仓库，无需额外处理。
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/central")
        }
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }

    dependencies {
        if (path.startsWith(":plugins:")) {
            add("compileOnly", "io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
        }
        add("testImplementation", platform("org.junit:junit-bom:5.13.4"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:all")
    }

    val expandedPluginVersion = version
    val expandedPaperApi = rootProject.property("minecraftVersion")
    tasks.withType<ProcessResources>().configureEach {
        filesMatching("plugin.yml") {
            expand(mapOf("version" to expandedPluginVersion, "paperApi" to expandedPaperApi))
        }
    }

    tasks.withType<Test>().configureEach {
        // CI 门禁按标签排除「环境依赖测试」（如 @Tag("env") 的部署产物断言）。
        // 默认不排除任何东西 —— 本机 `./gradlew test` 永远是全量。
        // 门禁显式传 `-PexcludeTags=env`，且退出码仍由 Gradle 决定（不设 ignoreFailures）。
        val excludedTags = providers.gradleProperty("excludeTags").orNull
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            ?: emptyList()
        useJUnitPlatform {
            excludedTags.forEach { tag -> excludeTags(tag) }
        }
        // 门禁需要一条「排除确实生效」的**机器证据**：Gradle 自己的
        // "excluding tests with tag" 是 INFO 级，默认日志级别下不会输出，
        // 导致 .cnb.yml 的测试门 grep 不到而整门 RED。
        // 这里把本任务实际应用的排除标签以 LIFECYCLE 级打出来 ——
        // 这是构建脚本自己声明的、可被 CI 抓取的事实，而不是手写日志。
        // 只在确实有排除项时打印，避免「空排除也打印一行」变成新的假证据。
        //
        // ★ 2026-10-13 修正（缓存交互 bug）：原实现在 `doFirst` 里打印，
        // 但 Gradle 开启构建缓存/UP-TO-DATE 后 **不会执行任何任务 action（含 doFirst）**，
        // 于是 `test` 任务被判定 UP-TO-DATE 时这行证据消失 ⇒ 门禁假红
        // （实测 sn=cnb-roh-1k4p8ri2m：SUM tests=1106 failed=0 modules=26 全过，
        //  却因 grep 不到该行而 `TEST_GATE=RED`）。
        // 改为在**配置期**打印（`println` 走 quiet 级别也能被重定向捕获），
        // 命令一跑就输出，与任务是否实际执行无关。
        if (excludedTags.isNotEmpty()) {
            println("excluding tests with tag: " + excludedTags.joinToString(", "))
        }
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            events("failed", "skipped")
        }
    }
}

val buildResourcePack = tasks.register<Exec>("buildResourcePack") {
    group = "distribution"
    description = "Validate and build the Minecraft 26.2 resource pack used by staged plugins."
    workingDir(layout.projectDirectory)
    commandLine("bash", layout.projectDirectory.file("resource-pack/build.sh").asFile.absolutePath)
    outputs.upToDateWhen { false }
}

val customPluginProjects = subprojects.filter { it.path.startsWith(":plugins:rpg-") }
val pluginCandidateDirectory = layout.projectDirectory.dir("server/deploy-candidates/custom-plugins")

val stagePluginCandidates = tasks.register<Sync>("stagePluginCandidates") {
    dependsOn(customPluginProjects.map { pluginProject ->
        pluginProject.tasks.named("jar")
    })
    from(customPluginProjects.map { it.layout.buildDirectory.dir("libs") })
    include("*.jar")
    exclude("*-sources.jar")
    into(pluginCandidateDirectory)
}

val stagePlugins = tasks.register<Exec>("stagePlugins") {
    group = "distribution"
    description = "Validate and atomically stage all custom plugins while Paper is stopped."
    dependsOn(stagePluginCandidates)
    workingDir(layout.projectDirectory)
    commandLine(
        "bash",
        layout.projectDirectory.file("server/bin/stage-custom-plugins.sh").asFile.absolutePath,
        "--from",
        pluginCandidateDirectory.asFile.absolutePath,
    )
}

tasks.register("stageRelease") {
    group = "distribution"
    description = "Validate the resource pack before staging the matching plugin jars."
    dependsOn(buildResourcePack, stagePlugins)
}

// A normal release must publish the pack before the jars that can reference
// its models.  `stagePlugins` deliberately remains independently callable for
// the guarded first phase of a BetterHud layout migration: Paper has to read
// the new HUD YAML once before its generated pack can be synchronized.
stagePlugins.configure {
    mustRunAfter(buildResourcePack)
}
