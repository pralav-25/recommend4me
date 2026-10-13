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

    // The check of a plugin's translations reads its JSON files
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.jackson.kotlin)
}

base.archivesName = "recommend4me-plugin-api"
