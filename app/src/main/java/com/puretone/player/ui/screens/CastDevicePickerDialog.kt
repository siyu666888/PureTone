package com.puretone.player.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puretone.player.R
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastNetworkLog
import com.puretone.player.ui.PlayerViewModel
import kotlinx.coroutines.CancellationException
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
 *
 * ## 列表分两段
 *
 * - **发现的设备**：本次 SSDP 搜到的（不一定可靠——电视刚唤醒时常常不回 M-SEARCH）；
 * - **最近投屏**：用户此前投成功过的设备（[PlayerViewModel.castHistory] 持久化）。
 *
 * 后者存在的意义就是兜住前者空手的情况：不必反复点「重新搜索」，更不必重启 Wi-Fi
 * 去「重置网络」，上次投过的那台电视一直在列表里，点一下就能重投。
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
    val history by vm.castHistory.collectAsStateWithLifecycle()

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
            } catch (e: CancellationException) {
                // 弹窗被关闭导致协程取消：不是错误，别把它显示成「搜索失败」。
                // 但也不能静默吞掉——取消必须继续向上传播，否则协程取消语义被破坏。
                throw e
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

    // 本次没搜到的历史设备单独成段（搜到的已并入上面，避免同一条目出现两次）
    val onlineAddresses = remember(devices) { devices.mapTo(HashSet()) { it.address } }
    val lastUsedByAddress = remember(history) { history.associate { it.address to it.lastUsedAt } }
    val offlineHistory = history.filter { it.address !in onlineAddresses }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pt_player_028)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                // 搜索转圈常驻顶部：即便正在搜，下方历史列表也实时可见（边显示历史边转圈），
                // 不再用 loading 整屏替换列表——这样上次投过的电视在搜索那几秒里就能直接点。
                if (loading && hasPermission) {
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
                if (!hasPermission || permissionDenied) {
                    Text(
                        stringResource(R.string.pt_player_034),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (devices.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.pt_player_037)) }
                            items(devices, key = { "online-${it.address}" }) { d ->
                                CastDeviceRow(
                                    device = d,
                                    lastUsedAt = lastUsedByAddress[d.address],
                                    offline = false,
                                    onClick = { onSelect(d) }
                                )
                            }
                        }
                        // 搜索中 devices 为空，这里就等于全部历史；搜完后已上线的自动上浮到上面。
                        if (offlineHistory.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.pt_player_038)) }
                            items(offlineHistory, key = { "history-${it.address}" }) { h ->
                                CastDeviceRow(
                                    device = h.toDevice(),
                                    lastUsedAt = h.lastUsedAt,
                                    offline = true,
                                    onClick = { onSelect(h.toDevice()) }
                                )
                            }
                        }
                    }
                    // 列表彻底空时（非权限问题）的兜底文案：出错显红字，否则显「未发现」。
                    if (devices.isEmpty() && offlineHistory.isEmpty()) {
                        if (error != null) {
                            Text(
                                stringResource(R.string.pt_player_030, error ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (!loading) {
                            Text(
                                stringResource(R.string.pt_player_031) +
                                    stringResource(R.string.pt_player_032),
                                style = MaterialTheme.typography.bodyMedium
                            )
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

/** 分段小标题（发现列表 / 历史列表），保持低调以免抢走设备行的注意力 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

/**
 * 一行设备。
 *
 * @param lastUsedAt 上次投屏成功的时间戳；从没投过（仅被发现）为 null
 * @param offline 是否属于「本次未发现、只在历史里」的设备——点下去可能失败，必须明说
 */
@Composable
private fun CastDeviceRow(
    device: CastDevice,
    lastUsedAt: Long?,
    offline: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.material3.Icon(
            if (device.isTV) Icons.Filled.CastConnected else Icons.Outlined.Cast,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle(device.address, lastUsedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (offline) {
                Text(
                    stringResource(R.string.pt_player_040),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 副标题：IP +（若投过）上次投屏时间的相对描述 */
@Composable
private fun subtitle(address: String, lastUsedAt: Long?): String {
    if (lastUsedAt == null || lastUsedAt <= 0L) return address
    val relative = DateUtils.getRelativeTimeSpanString(
        lastUsedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
    return "$address · " + stringResource(R.string.pt_player_039, relative)
}
