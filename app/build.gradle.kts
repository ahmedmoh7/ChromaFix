plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.chromafix.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.chromafix.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
dependencies {
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9")
}
