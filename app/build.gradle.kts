plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * 版本常量：唯一来源。
 * 同时用于 versionName 与产物文件名（archivesName），避免二者漂移。
 * 发版时只改这里（版本号按语义化版本递增）。
 */
val appVersionName = "1.7.2"
val appVersionCode = 26

/**
 * 产物文件名包含完整版本号。
 * AGP 会以 archivesName 为基名，再拼接 ABI 与构建类型：
 *   PureTone-v1.0.0-arm64-v8a-release.apk
 *   PureTone-v1.0.0-release.apk        （universal，无 ABI 过滤）
 * debug 构建同理（applicationIdSuffix 不影响文件名）。
 */
base { archivesName.set("PureTone-v$appVersionName") }

/**
 * 读取本地签名配置。
 * local.properties 已 gitignore，keystore 文件亦已忽略，避免口令/密钥入库。
 * 这里手写解析而不用 java.util.Properties：脚本作用域内 `java` 被遮蔽，全限定名解析会失败。
 */
val keystoreProps: Map<String, String> = rootProject.file("local.properties").let { f ->
    if (!f.exists()) return@let emptyMap()
    f.readLines()
        .map { it.trim() }
        .filter { it.contains("=") && !it.startsWith("#") }
        .associate { line ->
            val (k, v) = line.split("=", limit = 2)
            k.trim() to v.trim()
        }
}

android {
    namespace = "com.puretone.player"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.puretone.player"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ksp {
            arg("room.incremental", "true")
        }
    }

    // Room schema 导出（供后续版本迁移校验）
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }

    // 发布签名：口令来自顶层 keystoreProps（local.properties）
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] ?: "keystore/puretone-release.jks")
            storePassword = keystoreProps["storePassword"] ?: ""
            keyAlias = keystoreProps["keyAlias"] ?: "puretone"
            keyPassword = keystoreProps["keyPassword"] ?: ""
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ffmpeg-kit AAR 含四 ABI 原生库，release 分包控制体积
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.reorderable)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Media3 播放
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    // Room 数据层
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore 偏好
    implementation(libs.datastore.preferences)

    // 封面图加载
    implementation(libs.coil.compose)

    // 桌面小部件（Glance：Compose 语法写 AppWidget）
    implementation(libs.glance.appwidget)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // APE 解码：本地 AAR（Maven Central 已下架）+ 其传递依赖（files() 方式不解析 AAR 的 POM，须显式声明）
    implementation(files("libs/ffmpeg-kit-min-6.0-2.LTS.aar"))
    implementation(libs.smart.exception.java)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room.testing)
}
