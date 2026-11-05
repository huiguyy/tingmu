plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zcode.tingmu"
    compileSdk = 35

    defaultConfig {
        // -Pvariant=fast|bilingual|dialect|screen：不同 applicationId + 不同应用名，多个版本可在同一手机共存
        val variant = (project.findProperty("variant") as? String) ?: "fast"
        applicationId = when (variant) {
            "bilingual" -> "com.zcode.tingmu.bilingual"
            "dialect" -> "com.zcode.tingmu.dialect"
            "screen" -> "com.zcode.tingmu.screen"
            else -> "com.zcode.tingmu.fast"
        }
        resValue("string", "app_name", when (variant) {
            "bilingual" -> "听幕双语版"
            "dialect" -> "听幕方言版"
            "screen" -> "听幕·屏"
            else -> "听幕快速版"
        })
        // 各变体首选的模型（defaultModel 读取）：方言版默认离线方言模型，内置的流式小模型只做第一遍
        resValue("string", "model_pref", when (variant) {
            "bilingual" -> "x-asr"
            "dialect" -> "sense"
            else -> "small"
        })
        // 听幕·屏 = 独立产品（悬浮字幕+KWS 哨兵），launcher 指向自己的 Activity；版本线独立起步
        manifestPlaceholders["launcherActivity"] =
            if (variant == "screen") ".ScreenActivity" else ".MainActivity"
        minSdk = 24
        targetSdk = 35
        if (variant == "screen") {
            versionCode = 8
            versionName = "0.8"
        } else {
            versionCode = 23
            versionName = "1.9.4"
        }
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
