package com.google.android.systemui.power

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.internal.logging.UiEventLogger
import com.android.systemui.res.R
import java.text.NumberFormat

private const val TAG_BATTERY = "low_battery"

class SevereLowBatteryNotification(
    val context: Context,
    val uiEventLogger: UiEventLogger,
    val keyguardManager: KeyguardManager,
) {

    val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    fun logEvent(batteryMetricEvent: BatteryMetricEvent) {
        uiEventLogger.log(batteryMetricEvent)
        Log.d("SevereLowBatteryNotification", "logEvent $batteryMetricEvent")
    }
}

internal fun SevereLowBatteryNotification.cancel() {
    Log.d("SevereLowBatteryNotification", "cancel()")
    notificationManager.cancelAsUser(TAG_BATTERY, 3, UserHandle.ALL)
}

internal fun SevereLowBatteryNotification.show(
    batteryLevel: Int,
    isScheduledSwitch: Boolean,
    isSubsequentEvent: Boolean,
) {
    Log.d(
        "SevereLowBatteryNotification",
        "show() batteryLevel:$batteryLevel, scheduled:$isScheduledSwitch, " +
            "subsequenceEvent:$isSubsequentEvent",
    )

    val title =
        context.getString(
            R.string.severe_battery_notification_title,
            NumberFormat.getPercentInstance().format(batteryLevel * 0.01),
        )
    val text =
        context.getString(
            if (isScheduledSwitch) {
                R.string.severe_battery_notification_switch_text
            } else {
                R.string.severe_battery_notification_text
            }
        )

    val dismissExtras =
        Bundle(1).apply {
            putString(
                "extra_severe_low_battery_notification",
                if (isScheduledSwitch) "low_battery_notification_switch_to_ebs"
                else "low_battery_notification_turn_on_ebs",
            )
        }
    val deleteIntent =
        PowerUtils.createPendingIntent(context, "PNW.dismissSevereLowBatteryWarning", dismissExtras)

    val builder =
        NotificationCompat.Builder(context, "BAT").apply {
            setSmallIcon(R.drawable.ic_power_saver)
            setContentTitle(title)
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setDeleteIntent(deleteIntent)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setLocalOnly(true)

            if (keyguardManager.isDeviceLocked) {
                setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent("android.settings.BATTERY_SAVER_SETTINGS").setFlags(268468224),
                        335544320,
                    )
                )
            } else {
                addAction(
                    0,
                    context.getString(
                        if (isScheduledSwitch) R.string.severe_low_battery_dialog_switch_action_text
                        else R.string.battery_saver_start_action
                    ),
                    PowerUtils.createPendingIntent(
                        context,
                        "systemui.power.action.START_FLIPENDO",
                        dismissExtras,
                    ),
                )
            }

            if (isSubsequentEvent) setOnlyAlertOnce(true)
        }

    PowerUtils.overrideNotificationAppName(context, builder)

    notificationManager.notifyAsUser(TAG_BATTERY, 3, builder.build(), UserHandle.ALL)

    logEvent(
        if (isScheduledSwitch) BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS
        else BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS
    )
}
