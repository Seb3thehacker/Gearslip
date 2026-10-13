// From sherpa-onnx v1.13.8 (Apache-2.0, https://github.com/k2-fsa/sherpa-onnx), kotlin-api.
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
