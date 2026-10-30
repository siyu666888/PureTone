package com.puretone.player.data.settings

/**
 * 睡眠定时模式。
 *
 * - [OFF]：关闭（默认）；
 * - [MINUTES]：按分钟倒计时，到期暂停（到期时刻持久化，见 [PureToneSettings.sleepTimerWallDeadlineMs]）；
 * - [END_OF_TRACK]：当前曲目播完后停止（无时间上限，跨进程存活）。
 *
 * DataStore 以 `name` 字符串存储；未知值（如未来改名/降级）读取时回退 [OFF]，
 * 不让脏数据把 App 卡在无法理解的定时模式里。
 */
enum class SleepTimerMode {
    OFF,
    MINUTES,
    END_OF_TRACK;

    companion object {
        /** 字符串 → 枚举；未知/脏值回退 [OFF] */
        fun parse(raw: String?): SleepTimerMode =
            if (raw == null) OFF else entries.firstOrNull { it.name == raw } ?: OFF
    }
}
