pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "recommend4me"

include(
    "plugin-api",
    "libs:matrix",
    "libs:optim",
    "libs:onnx",
    "app",
    "plugins:catalogue-wikidata",
    "plugins:encoder-bge-m3",
    "plugins:encoder-e5",
    "plugins:encoder-siglip2",
    "plugins:reranker-bge",
    "plugins:scorer-pairwise",
    "plugins:scorer-knn",
    "plugins:scorer-fm",
    "plugins:scorer-kernel",
    "plugins:scorer-boosting",
    "plugins:search-lucene",
    "plugins:suggester-bilinear",
    "plugins:source-author-today",
    "plugins:source-ficbook",
)
