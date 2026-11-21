plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qiqi.floatshow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qiqi.floatshow"
        minSdk = 26
        targetSdk = 34
        versionCode = 67
        versionName = "1.67"
    }

    buildTypes {
        release {
            // 个人使用: 沿用 debug 密钥签名, 与已装的 debug 包同签名, 可直接覆盖安装
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        // release 构建自带的 lintVital 要联网下载 lint 组件, 网络不稳时会超时失败, 与 APK 内容无关
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // material3 1.1.x 与 compose 1.6.0 的 keyframes 签名不兼容(运行时 NoSuchMethodError), 用 2024.02.00 对齐到 material3 1.2.0
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    // SVG 渲染: Apache-2.0, 无传递依赖 (~220KB)
    implementation("com.caverock:androidsvg-aar:1.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
