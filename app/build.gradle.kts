plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // P2-2：detekt 静态分析门禁。配置见 config/detekt/detekt.yml，
    // 存量冻结在 app/detekt-baseline.xml。详细设计说明见该配置文件顶部注释。
    // 插件在根项目已 apply（apply false），这里**不能带 version**
    // （否则报 "already on the classpath"）。
    id("io.gitlab.arturbosch.detekt")
}

/**
 * 版本常量：唯一来源。
 * 同时用于 versionName 与产物文件名（archivesName），避免二者漂移。
 * 发版时只改这里（版本号按语义化版本递增）。
 */
val appVersionName = "1.9.2"
val appVersionCode = 54

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
        // targetSdk 35（2026-11-01 自 34 升级，自分发路线）。
        //
        // 路线图 P0-1 已拍板「**自分发，不上 Google Play**」，对应路线图第 1 步的轻量版：
        // targetSdk 升到 35 即可显著改善 Android 15/16 设备上的行为兼容，工具链代价小
        // （compileSdk 35 与 AGP 8.6.1 均已就位，本次仅改此一行）。
        //
        // 升 targetSdk 35 的四类主要风险点已逐项核查（代码证据见
        // dist/v1.7.25_targetSdk35_2026-11-01.md）：
        //   ① 强制 edge-to-edge —— **早已预埋**：MainActivity 调 enableEdgeToEdge()，
        //      外层 Scaffold 用 Material3 默认 systemBars insets、内层 Tab 用 WindowInsets(0)
        //      （P1-7「不重复叠加」设计），故系统强制化不引入新的 UI 变化；
        //   ② Safer intents —— 全部为显式 Intent，无隐式启动 Activity；
        //   ③ 前台服务类型 —— mediaPlayback 已声明且权限齐备；
        //   ④ PendingIntent —— 已带 FLAG_IMMUTABLE。
        //
        // 16KB 页设备兼容（P0-3）：原 ffmpeg-kit-min-6.0-2.LTS 的 .so 全为 4KB 页对齐，
        // arm64-v8a 在 16KB 页设备上加载失败（影响 APE 转码投送 / ReplayGain）。
        // 已换 dev.ffmpegkit-maintained:ffmpeg-kit-min:6.0.3（仅 arm64-v8a + x86_64、
        // 全部 16KB 对齐、API 全不变）——代价是**不再支持 armeabi-v7a（32 位手机）与 x86**
        // （这两类设备本就不支持 16KB 页，4KB 对齐无害；见 dist/ 路线图 §0.三）。
        targetSdk = 35
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
        /**
         * P2-1：Baseline Profile 采集专用构建类型。
         *
         * 与 release 的区别只有两处，且都是采集的硬性要求：
         * 1. `isProfileable = true` —— ART 只对 profileable 应用做 AOT 预编译，
         *    这是 Baseline Profile 能生效的前提（release 包因安全策略默认不可 profile）。
         * 2. **不加 applicationIdSuffix** —— 采集工具按包名定位被测应用，
         *    带 `.debug` 后缀会让「启动哪个包」变得不确定；不带后缀则与正式包同名，
         *    采集脚本无需为不同构建类型维护两份包名。
         *
         * 其余保持 release 语义（R8 开启）：**必须在混淆后的代码上采集**，
         * 否则 profile 里的类名与实际安装包的 dex 对不上，等于白采。
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("release")
            isProfileable = true
            matchingFallbacks += listOf("release")
            // applicationId 不加后缀（刻意不写 applicationIdSuffix）
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

    // ffmpeg-kit AAR 含两 ABI 原生库（arm64-v8a / x86_64），release 分包控制体积。
    // 注：维护版 ffmpeg-kit-min 只发 64 位（16KB 页对齐），故不再构建 armeabi-v7a / x86。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
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
            // P0-3 / LGPL 合规：ffmpeg-kit 等 LGPL 库的许可证文件必须随 APK 分发，
            // 不能 exclude 掉。改用 pickFirsts 在多个依赖出现重复条目时保留首个，
            // 既满足合规又避免「duplicate entry」构建冲突。
            pickFirsts += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
            // kotlin_module 仅是 Gradle 模块元数据，非许可证，排除无害。
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // lint 门禁（P2）：存量告警冻结进 lint-baseline.xml，只有**新增**告警才会让 CI 变红。
    // - baseline 文件入库，本地与 CI 共用同一份，避免「同一个告警在 CI 上才算」的偏差；
    // - warningsAsErrors：让新出现的 Warning 也阻断构建（否则 abortOnError 只管 Error，
    //   Warning 会被静默放过，门禁形同虚设）；
    // - 要清理存量告警时，从基线里删掉对应条目再修，不要整体删文件重生成——
    //   那会一次性把既有问题全部重新冻结，等于闸门失效。
    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        disable += setOf(
            // 检查的是**第三方库自带的** lint.jar 是否引用了过时 API
            //（报 "Library lint checks reference invalid APIs"）。衡量的不是本项目代码质量，
            // 结果完全取决于当时依赖缓存里有哪些库——本地 3 条（Warning），CI 上 63 条
            //（Error），两者不一致且无法在代码层面修正。
            "ObsoleteLintCustomCheck",
            // 以下三项是「有新版本可用」类检查：AGP 9.5.0、依赖升级等。它们随时间推移
            // 必然变化——今天通过，明天某个库发版就红，与本次改动毫无关系。留着会让
            // 门禁变成随机失败的噪音源，反而掩盖真正的代码问题。依赖升级另行跟进。
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            // 「未面向最新 Android 版本」提示，判定基准是**当前机器上已安装的 SDK platform 最高版本**：
            // 本地只装到 android-35 → targetSdk 35 不报；CI runner 镜像预装了更新的 platform →
            // 同一份 targetSdk 35 就报 Error。2026-11-04 删掉 lint-baseline 里的 OldTargetApi 后
            // CI 首次暴露这个差异（本地 lintDebug 始终 0 errors）。它与代码质量无关、随镜像漂移，
            // 与上面四项同属环境噪声；且 targetSdk 35 是 2026-11-01 评估后的主动选择（自分发路线，
            // 详见 dist/v1.7.25_targetSdk35_2026-11-01.md），不是疏漏。升 compileSdk/targetSdk 36
            // 属独立的大版本任务，届时再重新启用本检查。
            "OldTargetApi"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * LGPL 许可证随包分发（P0-3 实测修复，2026-11-01）。
 *
 * 背景：ffmpeg-kit-min-6.0-3-maintained.aar（dev.ffmpegkit-maintained，LGPL-3.0）是
 *   原 com.arthenica:ffmpeg-kit-min 的社区维护续作，它把
 *   - `res/raw/license.txt`            → LGPL 许可证全文
 *   - `res/raw/license_cpu_features.txt` → CPU features 相关许可
 *   - `res/raw/source.txt`             → **源码获取方式**（LGPL 第 6 条要求）
 * 放在 AAR 的 res/raw/ 下。
 *
 * 问题（实测）：release 构建开启 `isShrinkResources = true`，
 * 而这三个 raw 资源**没有任何代码引用**（不是 R.raw.license），
 * AAPT 的资源裁剪把它们全删了——
 *   debug   APK → res/raw/license.txt 7651 字节、source.txt 700 字节 ✅
 *   release APK → res/raw 下 0 个文件 ❌
 * 即：发布出去的 APK 既无 LGPL 许可证全文，也无源码获取方式，合规未闭环。
 * （另：`packaging.resources.pickFirsts` 里配的 META-INF/NOTICE / LGPL2.1
 *   对本 AAR **无效**——AAR 内根本没有这些条目。）
 *
 * 为什么用 assets 而不是 `res/raw/keep.xml`：
 * `tools:keep` 实测**未生效**（加 keep.xml 后重新构建 release，APK 内仍无这些文件）；
 * 而 assets 目录**不参与资源裁剪**，是确定性的做法。
 * 且从 AAR **自动提取**而非手工复制——换 16KB 版 ffmpeg-kit 时，
 * 许可证会随 AAR 一起自动换掉，不会出现「库换了、许可证还是旧的」这种漂移。
 */
val ffmpegKitAar = file("libs/ffmpeg-kit-min-6.0-3-maintained.aar")
val ffmpegLicensesDir = layout.buildDirectory.dir("generated/ffmpegLicenses")

val extractFfmpegLicenses = tasks.register<Copy>("extractFfmpegLicenses") {
    description = "从 ffmpeg-kit AAR 提取 LGPL 许可证与源码获取方式到 assets/licenses/"
    from(zipTree(ffmpegKitAar)) {
        include("res/raw/license*.txt", "res/raw/source*.txt")
        eachFile { path = "licenses/${path.substringAfterLast('/')}" }
        includeEmptyDirs = false
    }
    into(ffmpegLicensesDir)
}

// assets 目录来自 build 产物，需要显式接线任务依赖
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(extractFfmpegLicenses)
}
tasks.named("preBuild") { dependsOn(extractFfmpegLicenses) }

android {
    sourceSets {
        getByName("main") {
            assets.srcDir(ffmpegLicensesDir)
        }
    }
}

/**
 * detekt 门禁（P2-2）。
 *
 * 目标：**卡住「下一个 1000+ 行文件」的出现**。P1-1 把 PlaybackManager 从
 * ~1400 行拆成 8 个协作类后，若无机械约束，下一个巨类迟早会长回来。
 *
 * 三条设计原则（与既有 lint 门禁保持一致）：
 * 1. **baseline 冻结存量**：detekt-baseline.xml 入库，本地与 CI 共用一份，
 *    避免「同一个问题只在 CI 上才算」的偏差；
 * 2. **阈值有节制**：配置见 config/detekt/detekt.yml，对 Compose 高频误报的规则
 *    刻意关闭（详见该文件注释）——门禁一旦随机红，团队就会习惯性忽略它；
 * 3. **清理存量要逐条删**：从 baseline 删条目再修，不要整体重生成 baseline，
 *    那等于把既有问题全部重新冻结，闸门等于失效。
 *
 * `buildUponDefaultConfig = false`：本项目以 detekt.yml 作为**完整配置**
 *（其中已显式声明各规则 active 状态），不叠加 detekt 默认配置，
 * 避免默认规则里对 Compose 高频误报的项被悄悄启用。
 *
 * 跑法：
 *   ./gradlew detekt          # 校验（有新增问题即失败 = 门禁生效）
 *   ./gradlew detektBaseline  # 重新生成 baseline（谨慎使用）
 */
detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = false
    ignoreFailures = false
    parallel = true
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

    // DLNA/UPnP 投屏（M1）：设备发现 + 本地文件直投 + 播控，coroutine-first Kotlin API。
    // 仅发布在 JitPack（见 settings.gradle.kts 仓库声明）。
    implementation("com.github.yinnho:UPnPCast:v1.3.0")

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // APE 解码：社区维护版 ffmpeg-kit-min（dev.ffmpegkit-maintained:ffmpeg-kit-min:6.0.3，
    // 16KB 页对齐、API 同原 com.arthenica 版）+ 其传递依赖（files() 不解析 AAR 的 POM，须显式声明）
    implementation(files("libs/ffmpeg-kit-min-6.0-3-maintained.aar"))
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
