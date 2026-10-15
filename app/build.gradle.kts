import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("recommend4me.spring")
    application
    alias(libs.plugins.jooq.codegen)
    alias(libs.plugins.openapi.generator)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":plugin-api"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.hikari)
    // Its own classes too: a file is compacted while open through them (Database.compact)
    implementation(libs.h2)
    // Shared with the source plugins: the pages they read
    implementation(libs.jsoup)
    // Pictures: WebP is not among the formats ImageIO reads by itself, and some sites keep every
    // original as AVIF, read by javif, a pure Java AV1 decoder
    implementation(libs.imageio.webp)
    implementation(libs.javif)
    // The stemmer of Russian words: a character is found however its name is declined
    implementation(libs.lucene.analysis.common)

    jooqCodegen(platform(libs.spring.boot.bom))
    jooqCodegen(libs.h2)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.h2)
    // The suggestions of tags, worked out by the plugin of this build
    testImplementation(project(":plugins:suggester-bilinear"))
    // A model to explain: the pairwise scorer of this build
    testImplementation(project(":plugins:scorer-pairwise"))
    // The search of the works by their words, of this build
    testImplementation(project(":plugins:search-lucene"))
}

// --- jOOQ: the classes of the tables of every kind of database file, generated from its migrations ---

val migrations = layout.projectDirectory.dir("src/main/resources/db")

/**
 * Every kind of database file, by the folder of its migrations: the package of its classes. A content
 * type's corrections of before (`typecorrections`) are migrated only to be moved, read by plain SQL.
 */
val schemas = listOf("app", "source", "corrections", "ratings", "model", "dictionaries", "preferences")

/**
 * H2 ITSELF READS THE SCHEMA: the generator is given an empty database in memory that runs every
 * migration of the kind in order as it opens, so the statements are parsed by the parser that
 * runs them in the application.
 */
fun h2Of(kind: String): String {
    val scripts = migrations.dir(kind).asFile.listFiles { f -> f.name.matches(Regex("V\\d+__.*\\.sql")) }!!
        .sortedBy { it.name.substringAfter('V').substringBefore("__").toInt() }
        .joinToString("\\;") { "RUNSCRIPT FROM '${it.absolutePath.replace('\\', '/')}' CHARSET 'UTF-8'" }
    return "jdbc:h2:mem:jooq_$kind;INIT=$scripts"
}

jooq {
    executions {
        schemas.forEach { kind ->
            create(kind) {
                configuration {
                    jdbc {
                        driver = "org.h2.Driver"
                        url = h2Of(kind)
                    }
                    generator {
                        name = "org.jooq.codegen.KotlinGenerator"
                        database {
                            name = "org.jooq.meta.h2.H2Database"
                            inputSchema = "PUBLIC"
                            // Flyway's own table is not the application's
                            excludes = "flyway_schema_history"
                            forcedTypes {
                                forcedType {
                                    name = "INSTANT"
                                    // The pattern is read with COMMENTS on: a plain space is no space
                                    includeTypes = "(?i:TIMESTAMP(\\(\\d\\))?\\ WITH\\ TIME\\ ZONE)"
                                }
                            }
                        }
                        generate {
                            isRecords = true
                            isPojos = false
                            isDaos = false
                            isJavaTimeTypes = true
                            isKotlinNotNullRecordAttributes = false
                        }
                        target {
                            packageName = "io.github.vlsergey.recommend4me.database.$kind"
                            directory = layout.buildDirectory.dir("generated/jooq").get().asFile.absolutePath
                        }
                    }
                }
            }
        }
    }
}

tasks.matching { it.name.startsWith("jooqCodegen") }.configureEach { inputs.dir(migrations) }

// --- OpenAPI: the contract in api/openapi.yaml is the single source of truth ---

val openApiSpec = rootProject.layout.projectDirectory.file("api/openapi.yaml")
val generatedApiDir = layout.buildDirectory.dir("generated/openapi")

val generateApi = tasks.register<GenerateTask>("generateApi") {
    generatorName = "kotlin-spring"
    remoteInputSpec = openApiSpec.asFile.toURI().toString()
    inputs.file(openApiSpec)
    outputDir = generatedApiDir
    apiPackage = "io.github.vlsergey.recommend4me.api"
    modelPackage = "io.github.vlsergey.recommend4me.api.model"
    cleanupOutput = true
    globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "false")
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "skipDefaultInterface" to "true",
        "useSpringBoot3" to "true",
        "useTags" to "true",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "useBeanValidation" to "false",
        "enumPropertyNaming" to "UPPERCASE",
        "serializationLibrary" to "jackson",
    )
}

sourceSets {
    main {
        kotlin.srcDir(generatedApiDir.map { it.dir("src/main/kotlin") })
        kotlin.srcDir(layout.buildDirectory.dir("generated/jooq"))
    }
}

tasks.named("compileKotlin") { dependsOn(generateApi, "jooqCodegen") }

// --- Frontend: built by npm and served as static resources ---

val frontendDir = rootProject.layout.projectDirectory.dir("frontend")

fun npm(): String = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmInstall = tasks.register<Exec>("npmInstall") {
    workingDir = frontendDir.asFile
    commandLine(npm(), "install", "--no-audit", "--no-fund")
    inputs.file(frontendDir.file("package.json"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    dependsOn(npmInstall)
    workingDir = frontendDir.asFile
    commandLine(npm(), "run", "build")
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("index.html"))
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("vite.config.ts"))
    inputs.file(openApiSpec)
    outputs.dir(frontendDir.dir("dist"))
}

// --- The Firefox extension of the browser tracking mode: a folder of the distribution, extension/ ---

val extensionDir = rootProject.layout.projectDirectory.dir("browser-extension/extension")

tasks.named<ProcessResources>("processResources") {
    if (!project.hasProperty("skipFrontend")) {
        dependsOn(buildFrontend)
        from(frontendDir.dir("dist")) { into("static") }
    }
}

// --- The distribution: lib/ the application, plugins/<name>/ every plugin of this build ---

val pluginProjects = rootProject.subprojects.filter { it.path.startsWith(":plugins:") }
pluginProjects.forEach { evaluationDependsOn(it.path) }
val pluginDists = pluginProjects.map { it.tasks.named("pluginDist") }

/** The Vector API is incubating, and JavaCPP and the off-heap segments call native code. */
val jvmFlags = listOf("--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED")

application {
    applicationName = "recommend4me"
    mainClass = "io.github.vlsergey.recommend4me.launcher.LauncherKt"
    applicationDefaultJvmArgs = jvmFlags + listOf("-Xmx8g")
}

distributions {
    main {
        distributionBaseName = "recommend4me"
        contents {
            // Every plugin in a folder of its own: plugins/<name>/
            pluginProjects.forEach { p -> from(p.tasks.named("pluginDist")) { into("plugins/${p.name}") } }
            // Firefox loads the extension from its manifest.json on the disk
            from(extensionDir) { into("extension") }
        }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn(pluginDists)
    workingDir = rootDir
    // The plugins of this build, and those of other builds named by -PpluginDirs=<dir>;<dir>
    val dirs = pluginProjects.map { it.layout.buildDirectory.dir("plugin").get().asFile.absolutePath } +
        (project.findProperty("pluginDirs") as String?).orEmpty().split(';').filter { it.isNotBlank() }
    systemProperty("recommend4me.plugin-dirs", dirs.joinToString(File.pathSeparator))
    systemProperty("recommend4me.extension-dir", extensionDir.asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    systemProperty("recommend4me.data-dir", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    // The tests read the browser extension's files beside the module: a change of them runs them again
    inputs.dir(rootProject.layout.projectDirectory.dir("browser-extension/extension")).withPropertyName("extension")
    // And the documents of the databases' schemas
    inputs.dir(rootProject.layout.projectDirectory.dir("docs")).withPropertyName("docs")
}
