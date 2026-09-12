package com.hpu.transview.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hpu.transview.model.ServerMode
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import com.hpu.transview.ui.common.tvFocus
import com.hpu.transview.ui.theme.OnDarkDim

/** 服务器保活策略选择（极速 / 智能 / 省电），修改立即生效并持久化 */
@Composable
fun ServerModeDialog(onDismiss: () -> Unit) {
    val mode by ServerBus.mode.collectAsState()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .padding(28.dp)
                    .width(460.dp)
            ) {
                Text(
                    "服务器保活策略",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "修改立即生效；当前运行状态见右上角指示灯",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkDim
                )
                Spacer(Modifier.height(14.dp))
                ServerMode.entries.forEach { m ->
                    Column(
                        Modifier
                            .tvFocus()
                            .clickable {
                                ServerController.setMode(m)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            (if (m == mode) "● " else "○ ") + m.label +
                                (if (m == ServerMode.SMART) "（默认）" else ""),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (m == mode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            m.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkDim
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
