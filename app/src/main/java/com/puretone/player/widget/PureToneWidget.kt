package com.puretone.player.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.puretone.player.MainActivity
import com.puretone.player.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 控件图标边长（像素） */
private const val ICON_PX = 48

/**
 * 桌面小部件：显示当前歌曲 + 播放 / 暂停 / 切歌，整块点击进入 App。
 *
 * 用 Glance 写（产出 RemoteViews），与 App 内同一套 Compose 心智模型。
 * 更新策略：**不轮询**——AppWidget 的 updatePeriodMillis 最短 30 分钟且会让
 * 进程空转；内容变化由 [WidgetUpdater] 在播放状态变化时主动推
 * （见 PureToneWidgetReceiver 的 appwidget-provider 配置）。
 */
class PureToneWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val provider = entry.widgetStateProvider()
        // 读库与封面解码走 IO：AppWidget 渲染有主线程时间窗，别在这儿解码大图
        val state = withContext(Dispatchers.IO) { provider.snapshot() }
        val palette = withContext(Dispatchers.IO) { provider.palette() }
        provideContent {
            WidgetContent(state, palette)
        }
    }
}

@Composable
private fun WidgetContent(state: WidgetState, palette: WidgetPalette) {
    val fg = ColorProvider(palette.onBackground)
    val muted = ColorProvider(palette.muted)
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(palette.background))
            .cornerRadius(22.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // 整块点击 = 打开 App（与小部件按钮的 clickable 不冲突，Glance 分区域命中）
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(state.cover, palette.onBackground)
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                state.title,
                style = TextStyle(color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                state.artist,
                style = TextStyle(color = muted, fontSize = 12.sp),
                maxLines = 1
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlIcon(
                R.drawable.ic_widget_skip_previous,
                "上一首",
                palette.onBackground,
                actionRunCallback<PreviousAction>()
            )
            ControlIcon(
                if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                if (state.isPlaying) "暂停" else "播放",
                palette.onBackground,
                actionRunCallback<PlayPauseAction>()
            )
            ControlIcon(
                R.drawable.ic_widget_skip_next,
                "下一首",
                palette.onBackground,
                actionRunCallback<NextAction>()
            )
        }
    }
}

@Composable
private fun CoverArt(cover: Bitmap?, tint: Color) {
    val context = LocalContext.current
    val provider = cover?.let { ImageProvider(it) }
        ?: remember(tint) { iconProvider(context, R.drawable.ic_widget_album, tint, ICON_PX * 2) }
    Image(
        provider = provider,
        contentDescription = "封面",
        modifier = GlanceModifier.size(56.dp).cornerRadius(10.dp),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun ControlIcon(
    @DrawableRes resId: Int,
    description: String,
    tint: Color,
    action: Action
) {
    val context = LocalContext.current
    Image(
        provider = remember(resId, tint) { iconProvider(context, resId, tint) },
        contentDescription = description,
        // 命中区域撑到 40dp（图标本体 48px），手指点得中
        modifier = GlanceModifier.size(40.dp).padding(6.dp).clickable(action),
        contentScale = ContentScale.Fit
    )
}

/**
 * vector → bitmap → [ImageProvider]。
 *
 * 为什么不直接 ImageProvider(@DrawableRes)：小部件的 RemoteViews 由桌面（另一进程）
 * 渲染，矢量图的兼容性取决于桌面实现，个别 launcher 直接空白；这里在自家进程里把
 * vector 画成 bitmap 再传，行为统一且可按当前主题着色。
 */
private fun iconProvider(
    context: Context,
    @DrawableRes resId: Int,
    tint: Color,
    sizePx: Int = ICON_PX
): ImageProvider {
    val drawable = ResourcesCompat.getDrawable(context.resources, resId, context.theme)
        ?: return ImageProvider(resId)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.colorFilter = PorterDuffColorFilter(tint.toArgb(), PorterDuff.Mode.SRC_IN)
    drawable.draw(canvas)
    return ImageProvider(bitmap)
}
