package com.puretone.player.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * 诊断导出的线程模型单测（2026-11-16）。
 *
 * 背景：原实现里「导出诊断」这一行在**主线程**同步做完所有事——读三份日志、
 * 查 `PowerManager` / `FFmpegKit` 的 Binder 状态、把整份报告同步写盘，最后拉起分享面板。
 * 低端机 / 慢盘上这一串足以让界面卡住半秒以上。
 *
 * 本测试用 [ThreadSpy] 这个 `ContextWrapper` 替身记录「谁在哪个线程上被调用」，
 * 把两条线程约定钉死：
 * 1. 报告组装与写盘**不得**发生在主线程；
 * 2. 分享面板（`startActivity`）**必须**发生在主线程。
 *
 * 为什么不用「给生产代码加测试钩子」：报告组装全程要问 `Context` 要系统服务、
 * 要文件目录，分享面板要 `Context.packageName`——这些 `Context` 入口本身就是观测点，
 * 加专用钩子反而给生产代码留了只有测试才走的分支。
 *
 * 钩子选择（都是 `Context` 的**可覆盖**方法；`getString` 不行——SDK 里是 final）：
 * - [getSystemService] → 只在报告组装阶段被问（查 `PowerManager` / `AudioManager`），分享阶段不碰
 * - [getFilesDir] → 只在报告组装阶段被问（读崩溃日志），分享阶段不碰
 * - [getPackageName] → 报告组装与分享阶段都会问；分享阶段那次是**最后一次**调用
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticExportThreadTest {

    /**
     * 记录调用线程的 Context 替身。
     *
     * 三个钩子各自对应一段活儿，线程记录分开存，便于断言「报告阶段」与「分享阶段」。
     */
    private class ThreadSpy(base: Context) : ContextWrapper(base) {
        val serviceThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val filesDirThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val packageNameThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun getSystemService(name: String): Any? {
            serviceThreads += Thread.currentThread().name
            return super.getSystemService(name)
        }

        override fun getFilesDir(): File {
            filesDirThreads += Thread.currentThread().name
            return super.getFilesDir()
        }

        override fun getPackageName(): String {
            packageNameThreads += Thread.currentThread().name
            return super.getPackageName()
        }
    }

    /**
     * 剥掉协程派发时给线程名加的「@coroutine#N」后缀。
     *
     * kotlinx 协程把任务派发到某个线程执行时，会把线程名临时改成
     * 「原名 @coroutine#7」，跑完再改回去。比对「是不是同一个线程」时
     * 必须先剥掉后缀，否则主线程与后台线程的名字会带着不同编号，
     * 看起来像两个线程。
     */
    private fun baseName(threadName: String): String = threadName.substringBefore(" @coroutine#")

    @Test
    fun `诊断报告在非主线程组装写盘且分享动作在主线程发起`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val spy = ThreadSpy(base)
        val callerThread = baseName(Thread.currentThread().name)

        // 说明：Robolectric 下 androidx FileProvider 解析出的根目录与测试临时目录对不上，
        // getUriForFile 会抛 IllegalArgumentException。这发生在 share() 的第一条语句
        // （context.packageName）**之后**，不影响线程归属的断言，故此处刻意容忍。
        val shareFailure = runCatching {
            runBlocking { DiagnosticExport.exportAndShare(spy) }
        }.exceptionOrNull()
        if (shareFailure != null) {
            assertTrue(
                "分享阶段的失败应来自 FileProvider 解析，而不是别的环节：${shareFailure.message}",
                shareFailure is IllegalArgumentException
            )
        }

        assertTrue("报告组装应至少查过一次系统服务", spy.serviceThreads.isNotEmpty())
        assertFalse(
            "报告组装绝不能占用主线程（实测线程=${spy.serviceThreads.toSet()}，主线程=$callerThread）",
            spy.serviceThreads.any { baseName(it) == callerThread }
        )

        assertTrue("报告组装应至少读过一次文件目录", spy.filesDirThreads.isNotEmpty())
        assertFalse(
            "报告组装绝不能占用主线程（实测线程=${spy.filesDirThreads.toSet()}，主线程=$callerThread）",
            spy.filesDirThreads.any { baseName(it) == callerThread }
        )

        // packageName：报告组装问过一次，分享阶段再问（最后一次）。
        assertTrue("packageName 应至少被问过两次", spy.packageNameThreads.size >= 2)
        assertFalse(
            "报告阶段取 packageName 不该在主线程（实测线程=${spy.packageNameThreads.toSet()}）",
            baseName(spy.packageNameThreads.first()) == callerThread
        )
        assertEquals(
            "分享阶段必须在主线程发起（实测线程=${spy.packageNameThreads.toSet()}，主线程=$callerThread）",
            callerThread,
            baseName(spy.packageNameThreads.last())
        )
    }

    @Test
    fun `导出报告落在 cacheDir 的 diagnostics 目录且文件名带时间戳`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val spy = ThreadSpy(base)

        // 这里直接调同步写盘接口：只验证产物落位，不牵扯分享面板。
        val file = DiagnosticExport.writeReport(spy)

        assertEquals("应落在 diagnostics 子目录", "diagnostics", file.parentFile?.name)
        assertEquals("父目录应是 cacheDir", base.cacheDir, file.parentFile?.parentFile)
        assertTrue(
            "文件名应以 puretone_diagnostic_ 开头：${file.name}",
            file.name.startsWith("puretone_diagnostic_")
        )
        assertTrue("文件名应是 txt：${file.name}", file.name.endsWith(".txt"))
        // 正文必须真的写进去了，不能是空文件蒙混过关
        assertTrue("报告正文不应为空", file.readText().isNotBlank())
    }

    @Test
    fun `写盘失败时异常向上抛出交由调用方提示`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        // cacheDir 指向一个「普通文件」：mkdirs 必然失败 -> 写盘抛异常
        val blocker = File(base.cacheDir, "diagnostics_blocker.txt")
        blocker.writeText("x")
        val broken = object : ContextWrapper(base) {
            override fun getCacheDir(): File = blocker
        }

        val error = assertThrows(IOException::class.java) {
            runBlocking { DiagnosticExport.exportAndShare(broken) }
        }
        assertTrue(
            "异常信息应能说明是写盘失败：${error.message}",
            error.message?.contains("puretone_diagnostic") == true
        )
    }
}
