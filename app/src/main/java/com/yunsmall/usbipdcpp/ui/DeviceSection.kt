package com.yunsmall.usbipdcpp.ui

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunsmall.usbipdcpp.R
import com.yunsmall.usbipdcpp.util.displayName

@Composable
fun ColumnScope.DeviceListSection(
    devices: Map<String, UsbDevice>,
    // deviceName -> busid：判绑定与取 busid 用同一份数据，不拆两条参数线
    boundDevices: Map<String, String>,
    busyDevices: Set<String>,
    onBindDevice: (UsbDevice) -> Unit,
    onUnbindDevice: (UsbDevice) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // fill = false：设备少时卡片按内容收高，富余空间让给日志区
            //（LogSection 的 weight 用默认 fill = true，接收剩余空间）
            .weight(1.5f, fill = false)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // entries 列表按 devices 缓存：重组（如 busyDevices 进度动画驱动的帧）
            // 不重造；放条件外与 LogSection 的 scrollState 同理，分支切换不丢缓存
            val deviceEntries = remember(devices) { devices.entries.toList() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.usb_devices), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh))
                }
            }

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(deviceEntries, key = { it.value.deviceName }) { entry ->
                        val device = entry.value
                        val isBound = boundDevices.containsKey(device.deviceName)

                        val busid = boundDevices[device.deviceName]
                        DeviceItem(
                            device = device,
                            isBound = isBound,
                            isBusy = busyDevices.contains(device.deviceName),
                            busid = busid,
                            onBind = { onBindDevice(device) },
                            onUnbind = { onUnbindDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: UsbDevice,
    isBound: Boolean,
    isBusy: Boolean,
    busid: String?,
    onBind: () -> Unit,
    onUnbind: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName(LocalContext.current),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "VID: ${device.vendorId.toString(16).uppercase()}, PID: ${device.productId.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (busid != null) {
                Text(
                    text = "BUSID: $busid",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else if (isBound) {
            TextButton(onClick = onUnbind, modifier = Modifier.height(36.dp)) {
                Text(stringResource(R.string.unbind), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        } else {
            // 服务器未运行时按钮保持可点：点击后由 onBindDevice 提示"请先启动
            // 服务器"；置灰按钮点不动，用户不知道差什么
            Button(onClick = onBind, modifier = Modifier.height(36.dp)) {
                Text(stringResource(R.string.bind), fontSize = 12.sp)
            }
        }
    }
}
