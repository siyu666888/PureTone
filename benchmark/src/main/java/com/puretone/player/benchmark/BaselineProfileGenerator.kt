package com.puretone.player.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 采集器（P2-1）。
 *
 * 采集三条用户真实路径：
 * 1. **冷启动 → 曲库首屏**（启动期最重的路径：全库扫描 + 双 ExoPlayer 构建 + Compose 首帧）
 * 2. **冷启动 → 播放页**（多覆盖一层 MediaSession 与播放服务初始化）
 * 3. **播放页 → 队列面板**（覆盖播放页内的 Compose 重组与 LazyColumn 展开路径）
 *
 * 与手写 profile 的差别（这才是本项的意义）：
 * 手写只能覆盖「猜得到的热路径」；采集拿到的是**真实执行**的调用链，
 * 包含手写几乎不可能想到的反射、lambda 桥接方法、协程挂起恢复、Room 生成代码等。
 *
 * ⚠️ 采集需要**物理设备**：androidx.benchmark 的 Macrobenchmark 明确拒绝模拟器
 * （设备指纹含 generic、无 cpufreq、ART 行为与真机不同），
 * 模拟器上采出的 profile 在真机上没有收益。
 * 执行方式见 scripts/collect-baseline-profile.sh。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private val packageName = "com.puretone.player"

    /**
     * 直达播放页。
     *
     * 用 Intent extra 而不是 UI 点击：靠点击进入播放页要求设备上**恰好有可点的歌曲**，
     * 采集环境不可控 → 路径 flaky，甚至根本走不到播放页。
     * 而播放页恰恰是最重的路径（MediaSessionService 初始化 + ExoPlayer 构建 + 歌词面板树）。
     *
     * 契约见 MainActivity.EXTRA_ROUTE，与采集脚本保持一致。
     */
    private fun MacrobenchmarkScope.openPlayerScreen() {
        device.executeShellCommand(
            "am start -W -n $packageName/com.puretone.player.MainActivity " +
                "--es puretone_route player"
        )
    }

    /** 等待条件：播放页出现播放控制按钮（contentDescription = "播放/暂停"）。 */
    private fun MacrobenchmarkScope.awaitPlayerScreen() {
        val btn = device.wait(Until.hasObject(By.desc("播放/暂停")), 20_000)
        check(btn) { "播放页未在 20s 内出现「播放/暂停」按钮" }
    }

    /** 等待条件：曲库页出现「歌曲」页签即视为首屏已渲染。 */
    private fun MacrobenchmarkScope.awaitBrowseScreen() {
        val tab = device.wait(Until.hasObject(By.text("歌曲")), 20_000)
        check(tab) { "曲库页未在 20s 内出现「歌曲」页签——可能是权限未授予或启动异常" }
    }

    /**
     * 路径 1：冷启动 → 曲库首屏。
     *
     * 用 `startActivityAndWait` 而非手动 am start：它会等首帧真正绘制完成，
     * 这样采集到的才是「用户看到首屏那一刻」的真实栈，而不是「Activity 创建完」的中间态。
     */
    @Test
    fun startupJourney() = baselineProfileRule.collect(
        packageName = packageName,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        awaitBrowseScreen()
    }

    /**
     * 路径 2：冷启动 → 播放页。
     *
     * 播放页会触发 MediaSessionService 初始化 + ExoPlayer 构建 + 歌词面板渲染，
     * 这些都在启动热路径上，手写 profile 几乎必然漏掉。
     */
    @Test
    fun startupToPlayerJourney() = baselineProfileRule.collect(
        packageName = packageName,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        openPlayerScreen()
        awaitPlayerScreen()
    }

    /**
     * 路径 3：播放页 → 队列面板。
     *
     * 覆盖播放页内的 Compose 重组与 LazyColumn 展开——这类路径涉及的类
     * （如 LazyList 的测量/布局逻辑）在冷启动时并不会被执行到。
     */
    @Test
    fun playerInteractionJourney() = baselineProfileRule.collect(
        packageName = packageName,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        openPlayerScreen()
        awaitPlayerScreen()
        device.findObject(By.desc("播放队列"))?.click()
        device.wait(Until.hasObject(By.text("播放队列")), 5_000)
    }
}
