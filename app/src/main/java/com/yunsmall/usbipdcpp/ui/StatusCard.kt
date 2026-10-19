package com.yunsmall.usbipdcpp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunsmall.usbipdcpp.R

@Composable
fun StatusCard(
    serverRunning: Boolean,
    boundCount: Int,
    ipAddress: String?,
    port: Int,
    // The listen scope in force (fixed when the server started)
    loopbackOnly: Boolean
) {
    var showCopyMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                // 复制菜单的三项都依赖 IP：无 IP（如纯 USB 共享网络）时不可点，
                // 否则弹出的是空菜单
                .clickable(enabled = serverRunning && ipAddress != null) { showCopyMenu = true },
            colors = CardDefaults.cardColors(
                containerColor = if (serverRunning)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            if (serverRunning) Color.Green else Color.Red,
                            RoundedCornerShape(50)
                        )
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (serverRunning) stringResource(R.string.server_running) else stringResource(R.string.server_stopped),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (serverRunning) {
                        Text(
                            text = stringResource(if (loopbackOnly) R.string.listening_loopback else R.string.listening_all),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (loopbackOnly) Color.Unspecified else MaterialTheme.colorScheme.error
                        )
                        ipAddress?.let {
                            Text(
                                text = stringResource(R.string.address, it, port),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = stringResource(R.string.devices_bound, boundCount),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showCopyMenu,
            onDismissRequest = { showCopyMenu = false }
        ) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            ipAddress?.let { ip ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_ip, ip)) },
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("IP", ip))
                        showCopyMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_port, port)) },
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Port", port.toString()))
                        showCopyMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_address, ip, port)) },
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Address", "$ip:$port"))
                        showCopyMenu = false
                    }
                )
            }
        }
    }
}
