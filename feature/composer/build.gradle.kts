plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.composer"
    // Robolectric resolves the Material Symbol drawables (ComposerFileChipTests measures the chip).
    testOptions.unitTests.isIncludeAndroidResources = true
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    api(project(":feature:media"))
    api(project(":feature:voice"))
    implementation(libs.coil.compose)
    // Activity-result launchers for the Photos/Files pickers and the mic, camera and location prompts.
    implementation(libs.androidx.activity.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    // Android 12's bundled dictation engine (docs/android-12-support.md §10.3): k2-fsa publishes
    // sherpa-onnx only as release AARs, so it is vendored in libs/ — the one deviation from
    // catalog-only dependencies. AGP forbids direct local .aar dependencies in a library module,
    // so the vendoring is the AAR taken apart: the classes jar here, its .so files in
    // src/main/jniLibs (see libs/README.md for provenance).
    implementation(files("libs/sherpa-onnx-1.12.9-classes.jar"))
    // The dictation model download is a plain fetch, not an OpenCodeServer request.
    implementation(libs.okhttp)
    // ContentResolver/Bitmap encode-decode in the ported loader and photo-fixture JVM tests.
    testImplementation(libs.robolectric)
}
