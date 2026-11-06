package com.puretone.player.ui.cast

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.puretone.player.R
import com.puretone.player.playback.cast.CastState

/**
 * 投屏设备详情二级页：状态胶囊点开后展示完整设备名与 IP。
 *
 * 胶囊为了简洁只显示清洗后的短名，原始 friendlyName / IP 收在这里，既不丢信息，
 * 也不会让主遥控页出现「623(192.168.101.20)」这种对普通用户无意义的串。
 *
 * 投屏态右上角的投屏入口按新设计隐藏，因此这里保留「切换设备」入口——
 * 否则用户想换设备只能先断开再重投，是一次功能回归。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSheet(
    castState: CastState,
    onDismiss: () -> Unit,
    onPickDevice: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.pt_player_048),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            CastDeviceInfoRow(
                label = stringResource(R.string.pt_player_050),
                value = castState.deviceName ?: stringResource(R.string.pt_player_004)
            )
            CastDeviceInfoRow(
                label = stringResource(R.string.pt_player_049),
                value = castState.deviceAddress ?: stringResource(R.string.pt_player_052)
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(role = Role.Button, onClick = onPickDevice),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.pt_player_003),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CastDeviceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
