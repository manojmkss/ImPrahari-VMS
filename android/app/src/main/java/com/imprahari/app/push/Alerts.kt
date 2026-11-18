package com.imprahari.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.imprahari.app.ImPrahariApplication
import com.imprahari.app.MainActivity
import com.imprahari.app.R
import com.imprahari.app.net.AuthExpiredException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Alert notifications: shown at once, then updated with the snapshot when it's in. */
object Alerts {
    private const val CHANNEL = "alerts"
    private const val CHANNEL_URGENT = "urgent"

    const val EXTRA_CAMERA = "alert_camera_id"
    const val EXTRA_AT = "alert_at"
    const val EXTRA_EVENT = "alert_event_id"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_alerts_about) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_URGENT, context.getString(R.string.channel_urgent), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.channel_urgent_about)
                    setBypassDnd(false)
                }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, app: ImPrahariApplication, alert: AlertPayload) {
        if (!canNotify(context)) return
        createChannels(context)
        // Same tag (camera + kind) replaces the previous one, as in the browser.
        val id = (alert.tag ?: alert.eventId?.toString() ?: alert.title).hashCode()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(id, build(context, alert, picture = null))
        val path = alert.image ?: return
        app.appScope.launch {
            val bitmap = withTimeoutOrNull(15_000) {
                try {
                    app.api.imageBytes(path, signed = true)
                } catch (e: AuthExpiredException) {
                    null
                }
            }?.let { decode(it) } ?: return@launch
            nm.notify(id, build(context, alert, bitmap))
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun build(context: Context, alert: AlertPayload, picture: Bitmap?): android.app.Notification {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        alert.cameraId?.let { open.putExtra(EXTRA_CAMERA, it) }
        alert.at?.let { open.putExtra(EXTRA_AT, it) }
        alert.eventId?.let { open.putExtra(EXTRA_EVENT, it) }
        val requestCode = alert.eventId ?: alert.tag.hashCode()
        val pi = PendingIntent.getActivity(
            context, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = android.app.Notification.Builder(context, if (alert.urgent) CHANNEL_URGENT else CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(alert.title)
            .setContentText(alert.body.lineSequence().firstOrNull().orEmpty())
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(if (alert.urgent) android.app.Notification.CATEGORY_ALARM else android.app.Notification.CATEGORY_EVENT)
            .setWhen(alert.at?.let { (it * 1000).toLong() } ?: System.currentTimeMillis())
            .setShowWhen(true)
        if (picture != null) {
            builder.setLargeIcon(picture)
                .setStyle(android.app.Notification.BigPictureStyle().bigPicture(picture).setSummaryText(alert.body))
        } else if (alert.body.isNotEmpty()) {
            builder.setStyle(android.app.Notification.BigTextStyle().bigText(alert.body))
        }
        return builder.build()
    }
}
