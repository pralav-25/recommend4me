plugins {
    id("recommend4me.addon")
}

dependencies {
    testImplementation(testFixtures(project(":plugin-api")))
}
