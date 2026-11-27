# Vendored: sherpa-onnx 1.12.9 (Android)

The bundled dictation engine for Android 12 (< 33) (docs/android-12-support.md §10.3).

k2-fsa publishes no Maven Central artifact for sherpa-onnx, so the release AAR is vendored here —
taken apart, because AGP forbids direct local `.aar` file dependencies inside a library module:

- `sherpa-onnx-1.12.9-classes.jar` — the AAR's `classes.jar`, referenced from `build.gradle.kts`
  via `implementation(files(...))`.
- `src/main/jniLibs/<abi>/*.so` — the AAR's `jni/` folders (`libonnxruntime`,
  `libsherpa-onnx-c-api`, `libsherpa-onnx-cxx-api`, `libsherpa-onnx-jni` for
  arm64-v8a, armeabi-v7a, x86 and x86_64), packaged with the app by AGP's jniLibs handling.

Provenance:

- AAR: <https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.9/sherpa-onnx-1.12.9.aar>
  (37,023,886 bytes, SHA-256 `509a29272301da15dd8ac170656bf31d0af0e1957cfabf338a01ddc56b30d90a`,
  verified against the checksum published with the release).
- License: Apache-2.0 (k2-fsa/sherpa-onnx).
- The ASR *model* is not vendored: the streaming Zipformer English int8 model is downloaded at
  runtime into the app's `files/dictation-model/` (`ModelManager`), from
  <https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26>.

To upgrade, replace the classes jar and the jniLibs folders with the newer release's `classes.jar`
and `jni/*`.
