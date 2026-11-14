plugins {
    // P2-1：Baseline Profile 真机采集模块。
    //
    // 与 app 模块的关系：
    // - app 是「被测对象」——采集时需要以 benchmark（可 profile）形态安装；
    // - benchmark 是「测量工具」——通过 UiAutomator 驱动 app，采集实际执行过的类与方法。
    //
    // 为什么用官方 baselineprofile 插件而不是纯手写：
    // 手写 profile 只能覆盖「猜得到的热路径」，而采集能拿到**真实执行**的调用链，
    // 包含手写几乎不可能想到的反射、lambda、协程挂起恢复等路径。
    //
    // 关键限制（务必先读）：
    // androidx.benchmark 的 Macrobenchmark **要求物理设备**，模拟器会被拒绝
    // （设备指纹含 generic / 无 cpufreq 等）。因此：
    // - CI **不跑**采集（GitHub runner 是虚拟机，更跑不了）；
    // - 本模块的职责是：① 让采集脚本可执行 ② 让 CI 能校验「profile 是采集产出而非手写」。
    //
    // 注意 com.android.test 插件在根项目已随 AGP 上 classpath，
    // 这里**不能再带 version**（否则报 "already on the classpath"）；
    // 而 androidx.baselineprofile 不在 classpath 上，**必须带 version**。
    id("com.android.test")
    alias(libs.plugins.baselineprofile)
    // P2-2 配套：benchmark 模块也纳入 detekt 门禁（配置与 app 模块同源）。
    // 注意：detekt 插件在 app 模块已 apply，故这里**不能带 version**
    // （会报 "already on the classpath"）；但根项目并未 apply 它，
    // 因此实际写法是把它加到根项目的 apply false 列表，再在此处用 alias 引用。
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.puretone.player.benchmark"
    compileSdk = 35

    defaultConfig {
        // 采集需要 profileable 的 release 包，见 app/build.gradle.kts 的 release buildType。
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // 采集产物落到 benchmark/build/outputs/baselineProfiles/，
    // 随后由 scripts/collect-baseline-profile.sh 拷进 app/src/main/baselineProfiles/。
    //
    // 注意：com.android.test 插件**自带** benchmark / release / nonMinified 三个
    // buildType（官方模板的标准结构），不要自己再建同名 buildType——
    // 否则 assembleBenchmark 任务名会歧义（assembleBenchmarkBenchmark /
    // assembleBenchmarkRelease / assembleNonMinifiedBenchmark）。
    // 采集用 :benchmark:assembleBenchmark（对应插件自带的 benchmark 类型）。

    // baselineprofile 插件要求 targetProjectPath 指向被测 app
    targetProjectPath = ":app"

    // ⚠️ 这里**刻意不覆盖** debug 签名配置。
    //
    // 曾经的写法是显式指定：
    //     getByName("debug") { storeFile = file("~/.android/debug.keystore") ... }
    // 结果在 CI 上炸了：
    //     Keystore file '/home/runner/.android/debug.keystore' not found
    //     for signing config 'debug'.
    //
    // 根因：AGP 的**默认** debug 签名在文件缺失时会自动创建 debug.keystore；
    // 而一旦显式给出 storeFile，AGP 就认为「这个文件应该已经存在」——
    // 自动创建被关掉。CI runner 上没人装过 Android Studio，于是必然失败。
    // 本地之所以「能过」，是因为 Android Studio 早就建好了该文件，属于假绿。
    //
    // 顺带澄清一个曾被写进注释里的误解：
    // 「采集时 app 与 benchmark 用同一个 debug 签名，才能互相识别安装」——**并不需要**。
    // app 的 benchmark 变体用 release 签名，benchmark 测试包用 debug 签名，
    // 两者本就是不同签名：instrumentation APK 与被测 app 是通过 adb 通信的，
    // 不存在「互相识别安装」这一说。
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)   // 提供 androidx.test.filters.LargeTest
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}

/**
 * P2-2 配套：benchmark 模块也纳入 detekt 门禁。
 *
 * 配置与 app 模块一致（同一份 config/detekt/detekt.yml），
 * 但**独立 baseline**——两个模块的问题域不同，混在一起会让「谁的问题」变模糊。
 * 当前该模块零告警，故 baseline 为空。
 */
detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = false
    ignoreFailures = false
    parallel = true
}
