plugins {
    id("recommend4me.kotlin")
    `java-library`
}

dependencies {
    // The products of the weights in the wide registers
    implementation(project(":libs:matrix"))
}

base.archivesName = "recommend4me-optim"
