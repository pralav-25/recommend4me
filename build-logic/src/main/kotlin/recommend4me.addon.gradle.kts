import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

/*
 * A plugin of recommend4me: a jar with a Spring auto-configuration whose beans implement the
 * interfaces of plugin-api. What the application already has — Kotlin, Spring, Jackson, jOOQ,
 * the plugin API and the matrix library — is compileOnly here and is never copied into the
 * plugin's folder; what the plugin needs beyond that is.
 *
 * `pluginDist` lays the plugin out as it is installed: build/plugin/<name>/, its jar beside the libraries.
 */
plugins {
    id("recommend4me.spring")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

/** The plugin API: a project of this build, or of the build this one is composed with. */
fun api(path: String, coordinates: String): Any =
    rootProject.findProject(path) ?: coordinates

dependencies {
    "compileOnly"(platform(catalog.findLibrary("spring-boot-bom").get()))
    "compileOnly"(api(":plugin-api", "io.github.vlsergey.recommend4me:plugin-api"))
    "compileOnly"(catalog.findLibrary("spring-boot-autoconfigure").get())
    "compileOnly"(catalog.findLibrary("slf4j-api").get())

    "testImplementation"(platform(catalog.findLibrary("spring-boot-bom").get()))
    "testImplementation"(api(":plugin-api", "io.github.vlsergey.recommend4me:plugin-api"))
    "testImplementation"(catalog.findLibrary("spring-boot-autoconfigure").get())
    "testImplementation"(catalog.findLibrary("slf4j-api").get())
}

/** Groups whose jars the application carries itself: a plugin never brings its own copy. */
val providedGroups = setOf(
    "org.jetbrains.kotlin", "org.jetbrains", "org.jetbrains.kotlinx",
    "org.springframework", "org.springframework.boot",
    "tools.jackson", "tools.jackson.core", "tools.jackson.module", "com.fasterxml.jackson.core",
    "org.slf4j", "ch.qos.logback", "org.apache.logging.log4j",
    "org.jooq", "io.r2dbc", "org.reactivestreams",
    "org.bytedeco", "com.h2database", "org.jsoup", "jakarta.annotation",
)

/** Projects whose jars the application carries itself. */
val providedProjects = setOf("plugin-api", "matrix", "optim")

val pluginDist = tasks.register<Sync>("pluginDist") {
    group = "distribution"
    description = "Lays the plugin out as it is installed: its jar and the libraries the application does not have."
    into(layout.buildDirectory.dir("plugin/${project.name}"))
    from(tasks.named("jar"))
    // A view keeps the tasks that build the jars of other projects among the inputs
    from(configurations.named("runtimeClasspath").map { cfg ->
        cfg.incoming.artifactView {
            componentFilter { id ->
                when (id) {
                    is ModuleComponentIdentifier -> id.group !in providedGroups
                    is ProjectComponentIdentifier -> id.projectName !in providedProjects
                    else -> true
                }
            }
        }.files
    })
}

tasks.named("assemble") { dependsOn(pluginDist) }

// The plugin jar is named after the plugin: recommend4me-<name>.jar
base.archivesName = "recommend4me-${project.name}"
