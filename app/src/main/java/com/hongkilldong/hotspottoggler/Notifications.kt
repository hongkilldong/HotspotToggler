package com.hongkilldong.hotspottoggler

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import com.hongkilldong.hotspottoggler.activation.ActivationService
import com.hongkilldong.hotspottoggler.service.TogglerService

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_ACTIVATION = "activation"
    const val CH_CODE = "pairing_code"
    const val CH_RESULT = "activation_result"

    const val ID_SERVICE = 10
    const val ID_ACTIVATION = 20
    const val ID_CODE = 21
    const val ID_RESULT = 22

    const val EXTRA_CODE = "pairing_code"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, context.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ACTIVATION, context.getString(R.string.access_channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CODE, context.getString(R.string.access_channel_code), NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, context.getString(R.string.access_channel_result), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
            },
        )
    }

    fun service(context: Context, state: UiState): Notification {
        val app = context.getString(R.string.app_name)
        val text = when {
            state.error != null -> context.getString(R.string.notif_error, state.error)
            state.hotspot.starting -> context.getString(R.string.notif_starting)
            state.hotspot.on -> context.getString(R.string.notif_on, state.hotspot.clients.size)
            state.selected == null -> context.getString(R.string.home_status_no_device_title)
            else -> context.getString(R.string.notif_waiting, state.selected.name)
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 2,
            Intent(context, TogglerService::class.java).setAction(TogglerService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return base(context, CH_SERVICE, app, text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stop), context.getString(R.string.notif_stop), stop).build())
            .build()
    }

    fun activationProgress(context: Context, text: String, cancel: Boolean = true): Notification {
        val builder = base(context, CH_ACTIVATION, context.getString(R.string.notif_activation_title), text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 7,
                    ActivationService.wirelessDebuggingIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_play),
                    context.getString(R.string.notif_back_to_app),
                    backIntent(context, 8),
                ).build(),
            )
        if (cancel) {
            val cancelIntent = PendingIntent.getService(
                context, 3,
                Intent(context, ActivationService::class.java).setAction(ActivationService.ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stop), context.getString(R.string.access_cancel), cancelIntent).build())
        }
        return builder.build()
    }

    fun pairingCode(context: Context, error: String?): Notification {
        val input = RemoteInput.Builder(EXTRA_CODE)
            .setLabel(context.getString(R.string.notif_code_label))
            .build()
        val reply = PendingIntent.getService(
            context, 4,
            Intent(context, ActivationService::class.java).setAction(ActivationService.ACTION_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val settings = PendingIntent.getActivity(
            context, 5, ActivationService.wirelessDebuggingIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (error == null) context.getString(R.string.notif_code_text) else error
        return base(context, CH_CODE, context.getString(R.string.notif_code_title), text)
            .setContentIntent(settings)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_link),
                    context.getString(R.string.notif_code_reply), reply,
                ).addRemoteInput(input).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_play),
                    context.getString(R.string.notif_back_to_app),
                    backIntent(context, 9),
                ).build(),
            )
            .build()
    }

    private fun backIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun result(context: Context, text: String, failed: Boolean) = base(
        context, CH_RESULT,
        context.getString(R.string.app_name),
        if (failed) context.getString(R.string.notif_failed, text) else text,
    ).setAutoCancel(true)
        .setStyle(Notification.BigTextStyle().bigText(if (failed) context.getString(R.string.notif_failed, text) else text))
        .setContentIntent(
            PendingIntent.getActivity(
                context, 6, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun base(context: Context, channel: String, title: String, text: String): Notification.Builder {
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_hotspot)
            .setColor(context.getColor(R.color.pri))
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder
    }

    fun canPost(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
