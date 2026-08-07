import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credentials are read from local.properties when it has them, and fall back to
// the placeholders below. local.properties is git-ignored, so a real licence key
// or Agora token cannot reach a commit by accident. A fresh clone just sees the
// placeholders and the app tells you which one is missing.
//
//   nosmai.applicationId=com.example.yourapp
//   nosmai.licenseKey=NOSMAI-...
//   agora.appId=...
//   agora.token=            # leave empty for App ID authentication
//   agora.channel=nosmai-demo
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cred(key: String, fallback: String): String = localProps.getProperty(key) ?: fallback

android {
    namespace = "com.nosmai.agora.example"
    compileSdk = 35

    // 28.2.x is a broken install on some machines (missing source.properties,
    // fails with CXX1101). 29.0.14206865 is what the Nosmai SDK is built with.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // Nosmai licence keys are bound to an application id — set this to the
        // id your key was issued for.
        applicationId = cred("nosmai.applicationId", "com.your.app")

        buildConfigField("String", "NOSMAI_LICENSE_KEY",
            "\"${cred("nosmai.licenseKey", "YOUR_NOSMAI_ANDROID_KEY")}\"")
        buildConfigField("String", "AGORA_APP_ID",
            "\"${cred("agora.appId", "YOUR_AGORA_APP_ID")}\"")
        // Empty = App ID authentication, which needs no token.
        buildConfigField("String", "AGORA_TOKEN", "\"${cred("agora.token", "")}\"")
        buildConfigField("String", "AGORA_CHANNEL", "\"${cred("agora.channel", "nosmai-demo")}\"")

        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The Nosmai SDK only ships arm64. Without this filter the build pulls
        // in other ABIs that have no native library and fails at runtime.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Nosmai — camera, filters, AR. Bundled as a local .aar.
    implementation(files("libs/nosmai-release.aar"))

    // Agora RTC. full-sdk (not the "voice" or "lite" variants) is what supplies
    // io.agora.base.TextureBufferHelper, which the zero-readback path needs.
    implementation("io.agora.rtc:full-sdk:4.6.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
}
