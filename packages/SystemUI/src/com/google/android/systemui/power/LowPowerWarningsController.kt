package com.google.android.systemui.power

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.fuelgauge.BatterySaverLogging
import com.android.systemui.power.BatteryWarningEvents.LowBatteryWarningEvent
import com.android.systemui.res.R
import com.android.systemui.util.settings.GlobalSettings
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType
import java.text.NumberFormat
import java.util.concurrent.Executor

private const val TAG = "LowPowerWarningsController"
private const val TAG_BATTERY = "low_battery"

private const val LOW_BATTERY_TRIGGER_LEVEL = 30
private const val EXTREME_LOW_BATTERY_TRIGGER_LEVEL = 4

/**
 * Drives the low / severe-low / extreme-low battery notification lifecycle and reacts to
 * power-related broadcasts (power connected, power-save mode changes, manual battery-saver
 * toggles).
 *
 * Battery events arrive as a [BatteryEventType] list via [onBatteryEventUpdate]. The three tiers
 * are mutually exclusive in a single update: LOW_BATTERY is preferred over SEVERE_LOW_BATTERY,
 * which in turn is preferred over EXTREME_LOW_BATTERY.
 */
class LowPowerWarningsController(
    val context: Context,
    val executor: Executor,
    val globalSettings: GlobalSettings,
    val powerManager: PowerManager?,
    val uiEventLogger: UiEventLogger,
    val lowBatteryNotification: LowBatteryNotification,
    val severeLowBatteryNotification: SevereLowBatteryNotification,
    val extremeLowNotification: ExtremeLowBatteryNotification,
) {
    var lowBatterySectionEntered = false
    var lowBatteryNotificationCancelled = false
    var lowBatteryNotificationAlertedForSevereLowBattery = false

    var severeLowBatterySectionEntered = false
    var severeLowBatteryNotificationCancelled = false

    var extremeLowBatterySectionEntered = false

    var prevBatteryLevel: Int? = null
    var prevBatteryEventTypes: List<BatteryEventType>? = null
    var prevPowerSaveEnabledAsync: Boolean? = null

    /** Posts handling of [intent] onto [executor]. */
    fun dispatchIntent(intent: Intent) = executor.execute {
        val action = intent.action ?: return@execute
        when (action) {
            Intent.ACTION_POWER_CONNECTED -> cancelNotification()

            BatterySaverLogging.ACTION_SAVER_STATE_MANUAL_UPDATE -> {
                val manuallyEnabled =
                    intent.getBooleanExtra(
                        BatterySaverLogging.EXTRA_POWER_SAVE_MODE_MANUAL_ENABLED,
                        false,
                    )
                val reason =
                    intent.getIntExtra(
                        BatterySaverLogging.EXTRA_POWER_SAVE_MODE_MANUAL_ENABLED_REASON,
                        0,
                    )
                val event =
                    if (manuallyEnabled) {
                        BatteryMetricEvent.BATTERY_SAVER_ENABLED_REASON
                    } else {
                        BatteryMetricEvent.BATTERY_SAVER_DISABLED_REASON
                    }
                uiEventLogger.logWithPosition(event, /* uid= */ 0, /* packageName= */ null, reason)
            }

            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val isPowerSaveMode = powerManager?.isPowerSaveMode
                if (prevPowerSaveEnabledAsync != isPowerSaveMode) {
                    uiEventLogger.log(
                        if (isPowerSaveMode == true) {
                            BatteryMetricEvent.BATTERY_SAVER_ENABLED
                        } else {
                            BatteryMetricEvent.BATTERY_SAVER_DISABLED
                        }
                    )
                    prevPowerSaveEnabledAsync = isPowerSaveMode
                }
            }
        }
    }

    fun cancelNotification() {
        if (lowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->lowBatterySection")
            lowBatteryNotification.mNotificationManager.cancelAsUser(TAG_BATTERY, 3, UserHandle.ALL)
            lowBatteryNotificationCancelled = true
        }

        if (severeLowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->severeLowBatterySection")
            severeLowBatteryNotification.cancel()
            severeLowBatteryNotificationCancelled = true
        }

        if (extremeLowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->extremeLowBatterySection")
            extremeLowNotification.mNotificationManager.cancelAsUser(
                TAG_BATTERY,
                R.string.extreme_low_battery_notification_title,
                UserHandle.ALL,
            )
        }
    }

    fun isScheduledByPercentage(): Boolean =
        globalSettings.getInt(Settings.Global.AUTOMATIC_POWER_SAVE_MODE, 0) == 0 &&
            globalSettings.getInt(Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0) > 0

    fun onBatteryEventUpdate(batteryLevel: Int, events: List<BatteryEventType>) {
        prevBatteryLevel = batteryLevel
        prevBatteryEventTypes = events

        resetLowSeverityGuardsIfRecovered(batteryLevel)
        resetExtremeSeverityGuardIfRecovered(batteryLevel)

        if (events.isEmpty()) return

        when {
            BatteryEventType.LOW_BATTERY in events -> onLowBatteryEvent(batteryLevel, false)
            BatteryEventType.SEVERE_LOW_BATTERY in events -> onSevereLowBatteryEvent(batteryLevel)
            BatteryEventType.EXTREME_LOW_BATTERY in events -> onExtremeLowBatteryEvent(batteryLevel)
        }
    }

    private fun resetLowSeverityGuardsIfRecovered(batteryLevel: Int) {
        val shouldReset =
            lowBatterySectionEntered ||
                lowBatteryNotificationCancelled ||
                severeLowBatterySectionEntered ||
                severeLowBatteryNotificationCancelled

        if (!shouldReset || batteryLevel < LOW_BATTERY_TRIGGER_LEVEL) return

        Log.d(
            TAG,
            "reset section guard for low/severe low. batteryLevel:$batteryLevel" +
                " | lowBatterySectionEntered:$lowBatterySectionEntered -> false" +
                ", lowBatteryNotificationCancelled:$lowBatteryNotificationCancelled -> false" +
                ", severeLowBatterySectionEntered:$severeLowBatterySectionEntered -> false" +
                ", severeLowBatteryNotificationCancelled:" +
                "$severeLowBatteryNotificationCancelled -> false",
        )

        lowBatterySectionEntered = false
        lowBatteryNotificationCancelled = false
        severeLowBatterySectionEntered = false
        severeLowBatteryNotificationCancelled = false
        lowBatteryNotificationAlertedForSevereLowBattery = false
    }

    private fun resetExtremeSeverityGuardIfRecovered(batteryLevel: Int) {
        if (!extremeLowBatterySectionEntered) return
        if (batteryLevel < EXTREME_LOW_BATTERY_TRIGGER_LEVEL) return

        Log.d(TAG, "reset section guard for extreme low. batteryLevel:$batteryLevel")
        extremeLowBatterySectionEntered = false
        extremeLowNotification.mNotificationManager.cancelAsUser(
            TAG_BATTERY,
            R.string.extreme_low_battery_notification_title,
            UserHandle.ALL,
        )
    }

    private fun onSevereLowBatteryEvent(batteryLevel: Int) {
        if (!context.resources.getBoolean(R.bool.config_show_extreme_battery_saver_reminder)) {
            onLowBatteryEvent(batteryLevel, true)
            return
        }

        if (severeLowBatteryNotificationCancelled) {
            Log.d(TAG, "notification has been canceled, skip showing notification")
            return
        }

        if (globalSettings.getInt(Settings.Global.LOW_POWER_MODE_REMINDER_ENABLED, 1) == 0) {
            Log.d(TAG, "battery saver reminder has been disabled, skip showing notification")
            return
        }

        if (PowerUtils.isFlipendoEnabled(context.contentResolver)) {
            Log.d(TAG, "EBS has been enabled, skip showing notification")
            return
        }

        val isSubsequentEvent =
            if (severeLowBatterySectionEntered) {
                true
            } else {
                severeLowBatterySectionEntered = true
                lowBatteryNotification.mNotificationManager.cancelAsUser(
                    TAG_BATTERY,
                    3,
                    UserHandle.ALL,
                )
                lowBatteryNotificationCancelled = true
                false
            }

        val isScheduledSwitch = isScheduledByPercentage() || (powerManager?.isPowerSaveMode == true)

        severeLowBatteryNotification.show(batteryLevel, isScheduledSwitch, isSubsequentEvent)
    }

    private fun onExtremeLowBatteryEvent(batteryLevel: Int) {
        if (globalSettings.getInt("extreme_low_power_mode_reminder_enabled", 1) == 0) {
            Log.d(TAG, "onExtremeLowBatteryEvent: reminder is disable")
            return
        }

        if (extremeLowBatterySectionEntered) return
        extremeLowBatterySectionEntered = true

        lowBatteryNotification.mNotificationManager.cancelAsUser(TAG_BATTERY, 3, UserHandle.ALL)
        lowBatteryNotificationCancelled = true

        severeLowBatteryNotification.cancel()
        severeLowBatteryNotificationCancelled = true

        showExtremeLowBatteryNotification()

        extremeLowNotification.mUiEventLogger?.log(
            BatteryMetricEvent.EXTREME_LOW_BATTERY_NOTIFICATION
        )
    }

    private fun showExtremeLowBatteryNotification() {
        val notificationContext = extremeLowNotification.mContext

        val title = notificationContext.getString(R.string.extreme_low_battery_notification_title)
        val text = notificationContext.getString(R.string.extreme_low_battery_notification_text)

        val builder =
            NotificationCompat.Builder(notificationContext, "BAT").apply {
                setSmallIcon(R.drawable.ic_battery_extreme_low)
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
                setContentText(text)
                setContentTitle(title)
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }

        PowerUtils.overrideNotificationAppName(notificationContext, builder)

        extremeLowNotification.mNotificationManager.notifyAsUser(
            TAG_BATTERY,
            R.string.extreme_low_battery_notification_title,
            builder.build(),
            UserHandle.ALL,
        )
    }

    fun onLowBatteryEvent(batteryLevel: Int, alsoAlertForSevere: Boolean) {
        if (lowBatteryNotificationCancelled) {
            Log.d(TAG, "not showing notification -> notificationCanceled: true")
            return
        }

        if (globalSettings.getInt(Settings.Global.LOW_POWER_MODE_REMINDER_ENABLED, 1) == 0) {
            Log.d(TAG, "not showing notification -> isBatterySaverReminderDisabled: true")
            return
        }

        if (isScheduledByPercentage()) {
            Log.d(TAG, "not showing notification -> isScheduledByPercentage: true")
            return
        }

        if (powerManager?.isPowerSaveMode == true) {
            Log.d(TAG, "not showing notification -> isPowerSaveMode: true")
            return
        }

        var shouldAlert =
            if (lowBatterySectionEntered) {
                false
            } else {
                lowBatterySectionEntered = true
                uiEventLogger.log(LowBatteryWarningEvent.LOW_BATTERY_NOTIFICATION)
                true
            }

        if (!lowBatteryNotificationAlertedForSevereLowBattery && alsoAlertForSevere) {
            lowBatteryNotificationAlertedForSevereLowBattery = true
            shouldAlert = true
        }

        showLowBatteryNotification(batteryLevel, isFlipendoAggressive(), shouldAlert)
    }

    private fun isFlipendoAggressive(): Boolean =
        try {
            val result =
                context.applicationContext.contentResolver.call(
                    "com.google.android.flipendo.api",
                    "get_flipendo_state",
                    null,
                    null,
                )
            if (result == null) {
                Log.w(TAG, "contentResolver call Flipendo FLIPENDO_STATE_METHOD failed")
                false
            } else {
                result.getBoolean("is_flipendo_aggressive", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "flipendo not found", e)
            false
        }

    private fun showLowBatteryNotification(
        batteryLevel: Int,
        isFlipendoAggressive: Boolean,
        shouldAlert: Boolean,
    ) {
        val notificationContext = lowBatteryNotification.mContext

        val title =
            notificationContext.getString(
                R.string.low_battery_notification_title,
                NumberFormat.getPercentInstance().format(batteryLevel * 0.01),
            )
        val text =
            notificationContext.getString(
                if (isFlipendoAggressive) {
                    R.string.low_battery_notification_text_ebs
                } else {
                    R.string.low_battery_notification_text
                }
            )

        val builder =
            NotificationCompat.Builder(notificationContext, "BAT").apply {
                setSmallIcon(R.drawable.ic_power_saver)
                setContentText(text)
                setContentTitle(title)
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
                setOnlyAlertOnce(!shouldAlert)
                setDeleteIntent(
                    PowerUtils.createPendingIntent(
                        notificationContext,
                        "PNW.dismissedWarning",
                        null,
                    )
                )
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setLocalOnly(true)

                if (
                    isFlipendoAggressive && lowBatteryNotification.mKeyguardManager.isDeviceLocked
                ) {
                    setContentIntent(
                        PendingIntent.getActivity(
                            notificationContext,
                            0,
                            Intent("android.settings.BATTERY_SAVER_SETTINGS").setFlags(268468224),
                            335544320,
                        )
                    )
                } else {
                    addAction(
                        0,
                        notificationContext.getString(R.string.battery_saver_start_action),
                        PowerUtils.createPendingIntent(notificationContext, "PNW.startSaver", null),
                    )
                }
            }

        PowerUtils.overrideNotificationAppName(notificationContext, builder)

        lowBatteryNotification.mNotificationManager.notifyAsUser(
            TAG_BATTERY,
            3,
            builder.build(),
            UserHandle.ALL,
        )
    }
}
