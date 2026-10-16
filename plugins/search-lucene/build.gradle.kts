plugins {
    id("recommend4me.addon")
}

dependencies {
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    // The piece of a found text shown with a search result
    implementation(libs.lucene.highlighter)
    // The language of a text long enough to tell it by itself: what it is stemmed by
    implementation(libs.opennlp.tools)
    runtimeOnly(libs.opennlp.langdetect)
}
