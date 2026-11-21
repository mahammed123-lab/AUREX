import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Load .env secrets
val envFile = File(rootDir, ".env")
val envProperties = Properties()
if (envFile.exists()) {
    envFile.inputStream().use { envProperties.load(it) }
}
val geminiApiKey = System.getenv("GEMINI_API_KEY") 
    ?: envProperties.getProperty("GEMINI_API_KEY") 
    ?: ""

val nvidiaApiKey = System.getenv("NVIDIA_API_KEY") 
    ?: envProperties.getProperty("NVIDIA_API_KEY") 
    ?: project.findProperty("NVIDIA_API_KEY") as? String
    ?: ""

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistudio.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        // Inject GEMINI_API_KEY into BuildConfig
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "NVIDIA_API_KEY", "\"$nvidiaApiKey\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources {
        noCompress += listOf("onnx", "onnx.json")
    }

    packagingOptions {
        pickFirsts += listOf(
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/armeabi-v7a/libonnxruntime.so",
            "lib/arm64-v8a/libsherpa-onnx-c-api.so",
            "lib/armeabi-v7a/libsherpa-onnx-c-api.so",
            "lib/arm64-v8a/libsherpa-onnx-cxx-api.so",
            "lib/armeabi-v7a/libsherpa-onnx-cxx-api.so",
            "lib/arm64-v8a/libsherpa-onnx-jni.so",
            "lib/armeabi-v7a/libsherpa-onnx-jni.so"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking & Serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.serialization)
    implementation(libs.okhttp)

    // LiveKit room client — the voice path for JARVIS live calls.
    // 2.18.2 pins webrtc-sdk android-PREFIXED 125.6422.07 (livekit.org.webrtc
    // namespace). The old plain org.webrtc dep is gone: the /api/offer
    // fallback that used it pointed at a server that no longer exists, and its
    // unprefixed libjingle_peerconnection_so.so aborted (SIGABRT) the whole
    // :jarviscalls process when the fallback ran. Remote audio plays via
    // LiveKit's own AudioSwitchHandler — no manual sink needed.
    implementation(libs.livekit.android)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    // QR pairing — Google ML Kit code scanner (no CAMERA permission needed).
    implementation(libs.play.services.code.scanner)

    // Coil Image Loading
    implementation(libs.coil.compose)

    // Piper TTS Android Module
    implementation(project(":piper-tts-android"))

    // Test dependencies (optional)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
