plugins {
    id("recommend4me.addon")
}

dependencies {
    // The names' loss on the card, through the one ONNX runtime of the process
    implementation(project(":libs:onnx"))
    testImplementation(project(":libs:onnx"))
    // The log of a fitting in the output of a bench, as the application's would be
    testRuntimeOnly(platform(libs.spring.boot.bom))
    testRuntimeOnly("ch.qos.logback:logback-classic")
}
