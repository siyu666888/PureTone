package com.puretone.player.ui.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 媒体读取权限门控。
 *
 * 权限分级（P1-8）：
 * - **音频读取是硬门槛**（API 33+ READ_MEDIA_AUDIO / 低版本 READ_EXTERNAL_STORAGE）：
 *   未授权无法建库播放，停留在引导页；
 * - **通知是软权限**（API 33+ POST_NOTIFICATIONS）：与音频一起请求，但被拒绝也放行——
 *   Android 13+ 没有通知权限时前台服务仍可运行（系统媒体控制仍可用），
 *   旧实现把它设为硬门槛，用户拒绝通知就完全进不了 App。
 *
 * 另外：从系统设置页返回时经 [LifecycleEventObserver] ON_RESUME 重查，
 * 修掉「授权后返回仍停留在引导页」的问题（granted 旧实现只在首组合计算一次）。
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val audioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    /** 一起请求的权限（含软权限通知）；硬门槛只认 audioPermission */
    val requestSet = remember {
        buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 只以音频权限判定是否放行；通知被拒不影响进入 App
        audioGranted = result[audioPermission] == true ||
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    }

    // 从系统设置返回时重查（授权生效后页面不会继续卡在引导页）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                audioGranted = ContextCompat.checkSelfPermission(context, audioPermission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (audioGranted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("需要音乐访问权限", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "PureTone 需要读取本地音乐以构建曲库并播放。通知权限用于播放控制栏，" +
                    "即使不授予也能正常使用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(requestSet.toTypedArray()) }) {
                Text("授予权限")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }
            ) {
                Text("前往系统设置")
            }
        }
    }
}
