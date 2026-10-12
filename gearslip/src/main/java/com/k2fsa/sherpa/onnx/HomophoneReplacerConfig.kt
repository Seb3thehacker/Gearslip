// From sherpa-onnx v1.13.8 (Apache-2.0, https://github.com/k2-fsa/sherpa-onnx), kotlin-api.
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
