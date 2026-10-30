import java.util.Properties

plugins {
    id("com.android.application")
}

val standaloneSigning = Properties().apply {
    val f = rootProject.file(".signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dpadtv.client"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "local.libretv.standalone.tv"
        // 23 = Android 6.0：Media3 的下限之上，老盒子都能装
        minSdk = 23
        targetSdk = 37
        versionCode = 9
        versionName = "0.1.8"
        testInstrumentationRunner = "com.dpadtv.client.NativeCmsInstrumentation"
    }

    signingConfigs {
        create("standalone") {
            storeFile = rootProject.file(".signing/standalone.p12")
            storePassword = standaloneSigning.getProperty("storePassword")
            keyAlias = "standalone"
            keyPassword = standaloneSigning.getProperty("storePassword")
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("standalone") }
        release {
            signingConfig = signingConfigs.getByName("standalone")
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    // 加 -Pdpadtv.release=true 构建就不带"测试版"字样；平时构建默认是测试版
    defaultConfig {
        buildConfigField(
            "boolean",
            "TEST_BUILD",
            if (project.findProperty("dpadtv.release") == "true") "false" else "true",
        )
    }

    defaultConfig {
        // Retained only for unreachable upstream setup UI; standalone has no server credentials.
        buildConfigField("String", "DEFAULT_URL", "\"\"")
        buildConfigField("String", "DEFAULT_PASSWORD", "\"\"")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 原生播放：ExoPlayer 对 HLS 的容错远好于 MediaPlayer，H.265 取决于 SoC 硬解
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // 二维码生成（纯 Java，仅 core，不含 Android 集成包）
    implementation("com.google.zxing:core:3.5.3")
}
