package com.puretone.player.ui

import android.content.Context
import androidx.annotation.StringRes

/**
 * UI 一次性提示的统一载体：要么引用一个字符串资源（可带格式参数），
 * 要么携带已解析的动态文本（用户内容 / 异常信息 / 用例返回）。
 *
 * 用 [Res] 承载可国际化的模板；[Raw] 仅用于无法预先资源化、且可能含有
 * `%` 等特殊字符的动态文本（歌名、异常 message、文件路径），避免 format 崩溃。
 */
sealed interface UiMessage {
    /** 资源字符串模板；[args] 建议只放非用户可控内容（数字、内部常量名），避免 `%` 格式化崩溃 */
    data class Res(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiMessage
    /** 已解析的动态文本（用户内容 / 异常信息 / 用例返回） */
    data class Raw(val text: String) : UiMessage

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) {
            context.getString(resId)
        } else {
            @Suppress("SpreadOperator")
            context.getString(resId, *args.toTypedArray())
        }
        is Raw -> text
    }
}
