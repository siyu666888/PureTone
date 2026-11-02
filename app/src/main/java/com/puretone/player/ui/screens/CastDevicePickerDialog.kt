package com.puretone.player.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastNetworkLog
import com.puretone.player.ui.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * 投屏设备选择对话框（M1 + 修复：Android 10+ 设备发现需要权限）。
 *
 * 打开即检查「局域网设备发现」所需权限：
 * - Android 13+：`NEARBY_WIFI_DEVICES`（清单以 `neverForLocation` 声明，不触碰定位）；
 * - Android 12 及以下：`ACCESS_FINE_LOCATION`（系统扫描 Wi-Fi / mDNS 的硬性要求）。
 *
 * 未授权时 `DLNACast.search()` 在 targetSdk≥29 上会**静默返回空列表**，UI 即显示
 * 「未发现可投屏设备」——这正是此前投屏失败的根因。故在发起 SSDP 发现前必须先拿到权限。
 *
 * 打开即发起一次发现（授权后），展示局域网内渲染器列表；点击设备即选中并发起当前歌曲投屏。
 * 空列表/失败/未授权给出明确引导，「重新搜索」可再次尝试（含重新申请权限）。
 */
@Composable
fun CastDevicePickerDialog(
    vm: PlayerViewModel,
    currentSong: SongEntity?,
    onDismiss: () -> Unit,
    onSelect: (CastDevice) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    // Android 13+ 用 NEARBY_WIFI_DEVICES；低版本仍依赖 ACCESS_FINE_LOCATION（系统 Wi-Fi 发现要求）
    val neededPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, neededPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    fun doSearch() {
        scope.launch {
            loading = true
            error = null
            try {
                devices = vm.searchCastDevices()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        CastNetworkLog.logPermission(context, neededPermission, granted)
        if (granted) doSearch() else permissionDenied = true
    }

    LaunchedEffect(Unit) {
        CastNetworkLog.logPermission(context, neededPermission, hasPermission)
        if (hasPermission) doSearch() else permissionLauncher.launch(neededPermission)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pt_player_028)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                when {
                    loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                            )
                            Text(stringResource(R.string.pt_player_029), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    !hasPermission || permissionDenied -> {
                        Text(
                            stringResource(R.string.pt_player_034),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    error != null -> {
                        Text(
                            stringResource(R.string.pt_player_030, error ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    devices.isEmpty() -> {
                        Text(
                            stringResource(R.string.pt_player_031) +
                                stringResource(R.string.pt_player_032),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(devices) { d ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(d) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    androidx.compose.material3.Icon(
                                        if (d.isTV) Icons.Filled.CastConnected else Icons.Outlined.Cast,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            d.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            d.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (hasPermission) doSearch() else permissionLauncher.launch(neededPermission)
                }
            ) { Text(stringResource(R.string.pt_player_033)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pt_player_010)) }
        }
    )
}
