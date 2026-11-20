// Android host: runs the Web Player in a WebView and exposes it as a WaveBitsApi, plus the
// playback service (media session, notification, Android Auto browsing).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.wavebits.host.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":api-spotify"))
    api(project(":innertube"))
    implementation(libs.security.crypto)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.androidx.media)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.zstd)
    implementation(libs.play.services.cronet)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // The real org.json in unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
