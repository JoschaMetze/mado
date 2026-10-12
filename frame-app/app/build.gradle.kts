plugins {
    id("com.android.application")
}

android {
    namespace = "jp.co.xenocode.mado"
    compileSdk = 36
    // Pinned to what is already installed so AGP doesn't download another copy.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "jp.co.xenocode.mado"
        // The reference frame (Uhale 102KZ) is Android 6.0.1 (API 23), 32-bit ARM, 1280x800 @ 160dpi.
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
}

dependencies {
    // WebSocket link to the hub. 4.x still supports API 21+.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
