plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zcode.tingmu"
    compileSdk = 35

    defaultConfig {
        // -Pvariant=bilingual|fast：不同 applicationId + 不同应用名，两个版本可在同一手机共存
        val variant = (project.findProperty("variant") as? String) ?: "fast"
        applicationId = if (variant == "bilingual") "com.zcode.tingmu.bilingual" else "com.zcode.tingmu.fast"
        resValue("string", "app_name", if (variant == "bilingual") "听幕双语版" else "听幕快速版")
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "1.1"
        ndk {
            // 手机发布只含 ARM；模拟器构建用 -PextraAbis=x86_64 追加
            val lite = (project.findProperty("lite") as? String) == "true"
            abiFilters += if (lite) listOf("arm64-v8a") else listOf("arm64-v8a", "armeabi-v7a")
            abiFilters += ((project.findProperty("extraAbis") as? String)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // sherpa-onnx 官方 AAR（含全 ABI JNI 库），下载自
    // https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("androidx.annotation:annotation:1.8.2")
}
