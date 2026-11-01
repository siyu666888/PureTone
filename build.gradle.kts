plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // P2-2：detekt（静态分析门禁）与 dependencyUpdates（依赖漂移跟踪）。
    // dependencyUpdates 只提供命令，不需要作用到 Android 子模块；
    // detekt 在 app 模块应用（见 app/build.gradle.kts）——它对 Android 项目有原生支持，
    // 且能自动把 detekt task 挂到 check 生命周期。
    alias(libs.plugins.benManesVersions)
}
