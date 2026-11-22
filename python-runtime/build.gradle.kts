plugins { id("com.android.application") }

android {
    namespace = "com.example.videobrowser.pythonruntime"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.videobrowser.pythonruntime"
        minSdk = 29
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters.add("arm64-v8a") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { jniLibs { useLegacyPackaging = true } }
}
