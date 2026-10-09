plugins {
    id("recommend4me.addon")
}

dependencies {
    implementation(project(":libs:onnx"))
    // The SentencePiece tokenizer of XLM-RoBERTa, as the reranker was trained with
    implementation(libs.djl.tokenizers)
    testImplementation(project(":libs:onnx"))
}
