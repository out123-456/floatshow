import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放在项目根目录的 keystore.properties(git 忽略), 仓库里不含任何密钥
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.qiqi.floatshow"
    compileSdk = 34

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.qiqi.floatshow"
        minSdk = 26
        targetSdk = 34
        versionCode = 100
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // 有 keystore.properties 就用真密钥, 否则退回 debug 签名
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // 开混淆与资源压缩: 这两项对安装体积影响最大
            isMinifyEnabled = true
            isShrinkResources = true
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
    implementation("androidx.compose.material3:material3")
    // 不引入任何 material-icons 库: 用到的 14 个图标已手写在 ui/icons/AppIcons.kt.
    // 原先的 material-icons-extended 会编进约 5000 个图标, 是安装体积的大头
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    // SVG 渲染: Apache-2.0, 无传递依赖 (~220KB)
    implementation("com.caverock:androidsvg-aar:1.4")

}
