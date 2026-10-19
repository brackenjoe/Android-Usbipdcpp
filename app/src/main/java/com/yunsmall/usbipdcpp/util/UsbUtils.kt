package com.yunsmall.usbipdcpp.util

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import com.yunsmall.usbipdcpp.R

/**
 * 设备展示名：优先产品名，取不到时回退到通用文案。
 * 手动绑定、解绑、拔出提示与设备列表共用同一条回退规则。
 */
fun UsbDevice.displayName(context: Context): String =
    productName?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.unknown_device)

/**
 * Fork: a video-class (0x0E) device cannot be shared, because this build has no
 * camera permission (Android requires one to open a USB webcam).
 */
fun isCameraDevice(device: UsbDevice): Boolean =
    isVideoClass(device.deviceClass, (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass })

/**
 * 视频类有两种声明方式：设备级 class 直接声明（部分 UVC 设备），或接口级
 * 声明（复合设备）。只看接口类会在前一种上漏判，两处都要看。
 * 与 Android 类型无关，JVM 单元测试直接驱动
 */
fun isVideoClass(deviceClass: Int, interfaceClasses: List<Int>): Boolean =
    deviceClass == UsbConstants.USB_CLASS_VIDEO || interfaceClasses.any { it == UsbConstants.USB_CLASS_VIDEO }
