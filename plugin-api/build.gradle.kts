plugins {
    id("recommend4me.kotlin")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":libs:matrix"))
    // The optimiser the scorers and the suggesters fit their weights with
    api(project(":libs:optim"))
    api(libs.jooq)
    // The pages the sources read, and the helpers of PageText
    api(libs.jsoup)
}

base.archivesName = "recommend4me-plugin-api"
