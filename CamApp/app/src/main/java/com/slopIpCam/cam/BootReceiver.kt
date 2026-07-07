package com.slopIpCam.cam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Android 14+ forbids starting a camera/microphone foreground service from
 * BOOT_COMPLETED (and while-in-use rules would deny camera access anyway).
 * Instead, post a notification; the tap brings MainActivity to the
 * foreground, which may then legally start the camera service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SlopCam autostart", NotificationManager.IMPORTANCE_HIGH)
        )

        val tap = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_AUTO_START, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 0, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("SlopCam")
            .setContentText("Tap to resume camera service after reboot")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    companion object {
        const val CHANNEL_ID = "slopIpCamBoot"
        const val NOTIF_ID = 2
    }
}
