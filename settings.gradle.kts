pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ffmpeg-kit 已于 2025 年从 Maven Central 移除；本项目已将 AAR 内置在 app/libs/，
        // 此处保留阿里云镜像仅作历史版本兜底解析来源。
        maven("https://maven.aliyun.com/repository/public")
        // DLNA 投屏库（UPnPCast）仅发布在 JitPack，不在 Maven Central。
        maven("https://jitpack.io")
    }
}

rootProject.name = "PureTone"
include(":app")
