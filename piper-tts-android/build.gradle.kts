plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.jarvis.piper"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        noCompress.addAll(listOf("onnx", "bin"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // sherpa-onnx for Kitten TTS and Piper TTS (latest from JitPack) - exclude duplicate JVM module
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.8") {
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
}
