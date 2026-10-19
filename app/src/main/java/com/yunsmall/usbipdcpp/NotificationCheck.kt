package com.yunsmall.usbipdcpp

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Channel of the "Fingerprint needed to reconnect" notification. Shared so the
 * channel the service creates and the one the app checks are the same string
 */
const val AUTH_CHANNEL_ID = "usbipd_auth"

/**
 * Fork: whether the "Fingerprint needed" notification would be swallowed.
 * Pure, so the combinations can be tested on the JVM.
 *
 * Since API 33 POST_NOTIFICATIONS is a runtime permission; the user can also
 * switch the app's notifications off, or just the auth channel, in Settings
 */
fun notificationsBlocked(
    sdkInt: Int,
    permissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
    authChannelBlocked: Boolean
): Boolean = (sdkInt >= 33 && !permissionGranted) || !appNotificationsEnabled || authChannelBlocked

/** Whether POST_NOTIFICATIONS is held (always true below API 33, where it does not exist) */
fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** [notificationsBlocked] for the running app: gathers the four inputs from the system */
fun notificationsBlocked(context: Context): Boolean {
    val sdk = Build.VERSION.SDK_INT
    val authChannelBlocked = sdk >= Build.VERSION_CODES.O &&
        // A channel not created yet (the service has not started) counts as not blocked
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(AUTH_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
    return notificationsBlocked(
        sdkInt = sdk,
        permissionGranted = notificationPermissionGranted(context),
        appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        authChannelBlocked = authChannelBlocked
    )
}
