import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localSecret(key: String, fallback: String = ""): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: fallback)

android {
    namespace = "com.neverhide.empire"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neverhide.empire"
        minSdk = 26
        targetSdk = 34
        versionCode = 64
        versionName = "2.13.5"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "BRIDGE_SECRET", "\"" + localSecret("bridge.secret") + "\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            // Path is overridable so CI can keep the keystore OUT of the repo
            // workspace entirely (in $RUNNER_TEMP). Default = local dev layout.
            storeFile = file(localSecret("keystore.path", "../empire-release.keystore"))
            storePassword = localSecret("keystore.storePassword")
            keyAlias = "empire-key"
            keyPassword = localSecret("keystore.keyPassword")
        }
    }

    lint {
        // False positive: we use ComponentActivity (no fragments anywhere in
        // the Empire), so the fragment-version check for ActivityResult is moot.
        disable += "InvalidFragmentVersionForActivityResult"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Also sign debug with release key for Samsung compatibility
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.coroutines.android)
    implementation(libs.firebase.messaging)
    implementation(libs.webrtc)
}
