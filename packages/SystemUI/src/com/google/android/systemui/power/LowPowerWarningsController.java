/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.systemui.power;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.power.BatteryWarningEvents;
import com.android.systemui.res.R;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.util.settings.GlobalSettings;
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;

import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.Executor;

public final class LowPowerWarningsController {
    private static final String TAG = "LowPowerWarningsController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public Context context;
    public Executor executor;
    public GlobalSettings globalSettings;
    public LowBatteryNotification lowBatteryNotification;
    public SevereLowBatteryNotification severeLowBatteryNotification;
    public ExtremeLowBatteryNotification extremeLowNotification;
    public PowerManager powerManager;
    public UiEventLogger uiEventLogger;

    public Integer prevBatteryLevel;
    public List<BatteryEventType> prevBatteryEventTypes;

    public boolean lowBatterySectionEntered;
    public boolean lowBatteryNotificationCancelled;
    public boolean lowBatteryNotificationAlertedForSevereLowBattery;

    public boolean severeLowBatterySectionEntered;
    public boolean severeLowBatteryNotificationCancelled;

    public boolean extremeLowBatterySectionEntered;

    public LowPowerWarningsController(
            Context context,
            GlobalSettings globalSettings,
            UiEventLogger uiEventLogger,
            Executor executor) {
        this.context = context;
        this.globalSettings = globalSettings;
        this.uiEventLogger = uiEventLogger;
        this.executor = executor;
        this.powerManager = context.getSystemService(PowerManager.class);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        KeyguardManager km = context.getSystemService(KeyguardManager.class);

        this.lowBatteryNotification = new LowBatteryNotification(context, km, nm);
        this.severeLowBatteryNotification = new SevereLowBatteryNotification(context, uiEventLogger, km);
        this.extremeLowNotification = new ExtremeLowBatteryNotification(context, nm, uiEventLogger);
    }

    public void cancelNotification() {
        if (lowBatterySectionEntered) {
            if (DEBUG) Log.d(TAG, "cancelNotification->lowBatterySection");
            lowBatteryNotification.mNotificationManager.cancelAsUser("low_battery", 3, UserHandle.ALL);
            lowBatteryNotificationCancelled = true;
        }
        if (severeLowBatterySectionEntered) {
            if (DEBUG) Log.d(TAG, "cancelNotification->severeLowBatterySection");
            severeLowBatteryNotification.cancel();
            severeLowBatteryNotificationCancelled = true;
        }
        if (extremeLowBatterySectionEntered) {
            if (DEBUG) Log.d(TAG, "cancelNotification->extremeLowBatterySection");
            extremeLowNotification.mNotificationManager.cancelAsUser(
                    "low_battery", R.string.extreme_low_battery_notification_title, UserHandle.ALL);
        }
    }

    public boolean isScheduledByPercentage() {
        return globalSettings.getInt("automatic_power_save_mode", 0) == 0
                && globalSettings.getInt("low_power_trigger_level", 0) > 0;
    }

    public void onBatteryEventUpdate(int batteryLevel, List<BatteryEventType> events) {
        this.prevBatteryLevel = batteryLevel;
        this.prevBatteryEventTypes = events;

        if ((lowBatterySectionEntered || lowBatteryNotificationCancelled
                || severeLowBatterySectionEntered || severeLowBatteryNotificationCancelled)
                && batteryLevel >= 30) {
            if (DEBUG) {
                Log.d(TAG, "reset section guard for low/severe low. batteryLevel: " + batteryLevel);
            }
            lowBatterySectionEntered = false;
            lowBatteryNotificationCancelled = false;
            severeLowBatterySectionEntered = false;
            severeLowBatteryNotificationCancelled = false;
            lowBatteryNotificationAlertedForSevereLowBattery = false;
        }

        if (extremeLowBatterySectionEntered && batteryLevel >= 4) {
            if (DEBUG) {
                Log.d(TAG, "reset section guard for extreme low. batteryLevel: " + batteryLevel);
            }
            extremeLowBatterySectionEntered = false;
            extremeLowNotification.mNotificationManager.cancelAsUser(
                    "low_battery", R.string.extreme_low_battery_notification_title, UserHandle.ALL);
        }

        if (events == null || events.isEmpty()) {
            return;
        }

        if (events.contains(BatteryEventType.LOW_BATTERY)) {
            onLowBatteryEvent(batteryLevel, false);
            return;
        }

        if (!events.contains(BatteryEventType.SEVERE_LOW_BATTERY)) {
            if (events.contains(BatteryEventType.EXTREME_LOW_BATTERY)) {
                if (globalSettings.getInt("extreme_low_power_mode_reminder_enabled", 1) == 0) {
                    if (DEBUG) Log.d(TAG, "onExtremeLowBatteryEvent: reminder is disabled");
                    return;
                }
                if (extremeLowBatterySectionEntered) {
                    return;
                }
                extremeLowBatterySectionEntered = true;
                lowBatteryNotification.mNotificationManager.cancelAsUser("low_battery", 3, UserHandle.ALL);
                lowBatteryNotificationCancelled = true;
                severeLowBatteryNotification.cancel();
                severeLowBatteryNotificationCancelled = true;

                String title = extremeLowNotification.mContext.getString(R.string.extreme_low_battery_notification_title);
                String text = extremeLowNotification.mContext.getString(R.string.extreme_low_battery_notification_text);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(extremeLowNotification.mContext, NotificationChannels.BATTERY);
                builder.setSmallIcon(R.drawable.ic_battery_extreme_low);
                builder.setContentTitle(NotificationCompat.Builder.limitCharSequenceLength(title));
                builder.setContentText(NotificationCompat.Builder.limitCharSequenceLength(text));
                builder.setStyle(new NotificationCompat.BigTextStyle().bigText(NotificationCompat.Builder.limitCharSequenceLength(text)));
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                PowerUtils.overrideNotificationAppName(extremeLowNotification.mContext, builder);

                extremeLowNotification.mNotificationManager.notifyAsUser(
                        "low_battery", R.string.extreme_low_battery_notification_title, builder.build(), UserHandle.ALL);
                if (extremeLowNotification.mUiEventLogger != null) {
                    extremeLowNotification.mUiEventLogger.log(BatteryMetricEvent.EXTREME_LOW_BATTERY_NOTIFICATION);
                }
            }
            return;
        }

        if (!context.getResources().getBoolean(R.bool.config_show_extreme_battery_saver_reminder)) {
            onLowBatteryEvent(batteryLevel, true);
            return;
        }

        if (severeLowBatteryNotificationCancelled) {
            if (DEBUG) Log.d(TAG, "notification has been canceled, skip showing notification");
            return;
        }

        if (globalSettings.getInt("low_power_mode_reminder_enabled", 1) == 0) {
            if (DEBUG) Log.d(TAG, "battery saver reminder has been disabled, skip showing notification");
            return;
        }

        if (PowerUtils.isFlipendoEnabled(context.getContentResolver())) {
            if (DEBUG) Log.d(TAG, "EBS has been enabled, skip showing notification");
            return;
        }

        boolean subsequentEvent;
        if (severeLowBatterySectionEntered) {
            subsequentEvent = true;
        } else {
            severeLowBatterySectionEntered = true;
            lowBatteryNotification.mNotificationManager.cancelAsUser("low_battery", 3, UserHandle.ALL);
            lowBatteryNotificationCancelled = true;
            subsequentEvent = false;
        }

        boolean scheduled = isScheduledByPercentage() || (powerManager != null && powerManager.isPowerSaveMode());

        if (DEBUG) {
            Log.d("SevereLowBatteryNotification", "show() batteryLevel:" + batteryLevel
                    + ", scheduled:" + scheduled + ", subsequentEvent:" + subsequentEvent);
        }

        String title = severeLowBatteryNotification.context.getString(
                R.string.severe_battery_notification_title,
                NumberFormat.getPercentInstance().format(batteryLevel * 0.01d));
        String text = severeLowBatteryNotification.context.getString(
                scheduled ? R.string.severe_battery_notification_switch_text
                          : R.string.severe_battery_notification_text);

        Bundle bundle = new Bundle(1);
        bundle.putString("extra_severe_low_battery_notification",
                scheduled ? "low_battery_notification_switch_to_ebs" : "low_battery_notification_turn_on_ebs");

        PendingIntent deleteIntent = PowerUtils.createPendingIntent(
                severeLowBatteryNotification.context, "PNW.dismissSevereLowBatteryWarning", bundle);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(severeLowBatteryNotification.context, NotificationChannels.BATTERY);
        builder.setSmallIcon(R.drawable.ic_power_saver);
        builder.setContentTitle(NotificationCompat.Builder.limitCharSequenceLength(title));
        builder.setContentText(NotificationCompat.Builder.limitCharSequenceLength(text));
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(NotificationCompat.Builder.limitCharSequenceLength(text)));
        builder.setDeleteIntent(deleteIntent);
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setLocalOnly(true);

        if (severeLowBatteryNotification.keyguardManager.isDeviceLocked()) {
            builder.setContentIntent(PendingIntent.getActivity(
                    severeLowBatteryNotification.context, 0,
                    new Intent("android.settings.BATTERY_SAVER_SETTINGS").setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE));
        } else {
            builder.addAction(
                    severeLowBatteryNotification.context.getString(
                            scheduled ? R.string.severe_low_battery_dialog_switch_action_text
                                      : R.string.battery_saver_start_action),
                    PowerUtils.createPendingIntent(
                            severeLowBatteryNotification.context, "systemui.power.action.START_FLIPENDO", bundle));
        }

        if (subsequentEvent) {
            builder.setOnlyAlertOnce(true);
        }

        PowerUtils.overrideNotificationAppName(severeLowBatteryNotification.context, builder);
        severeLowBatteryNotification.getNotificationManager().notifyAsUser(
                "low_battery", 3, builder.build(), UserHandle.ALL);
        severeLowBatteryNotification.logEvent(
                scheduled ? BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS
                          : BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS);
    }

    public void onLowBatteryEvent(int batteryLevel, boolean isSevere) {
        if (lowBatteryNotificationCancelled) {
            if (DEBUG) Log.d(TAG, "not showing notification -> notificationCancelled: true");
            return;
        }
        if (globalSettings.getInt("low_power_mode_reminder_enabled", 1) == 0) {
            if (DEBUG) Log.d(TAG, "not showing notification -> isBatterySaverReminderDisabled: true");
            return;
        }
        if (isScheduledByPercentage()) {
            if (DEBUG) Log.d(TAG, "not showing notification -> isScheduledByPercentage: true");
            return;
        }
        if (powerManager != null && powerManager.isPowerSaveMode()) {
            if (DEBUG) Log.d(TAG, "not showing notification -> isPowerSaveMode: true");
            return;
        }

        boolean alert;
        if (lowBatterySectionEntered) {
            alert = false;
        } else {
            lowBatterySectionEntered = true;
            uiEventLogger.log(BatteryWarningEvents.LowBatteryWarningEvent.LOW_BATTERY_NOTIFICATION);
            alert = true;
        }

        if (!lowBatteryNotificationAlertedForSevereLowBattery && isSevere) {
            lowBatteryNotificationAlertedForSevereLowBattery = true;
            alert = true;
        }

        boolean isFlipendoAggressive = false;
        try {
            Bundle bundle = context.getApplicationContext().getContentResolver().call(
                    "com.google.android.flipendo.api", "get_flipendo_state", null, null);
            if (bundle != null) {
                isFlipendoAggressive = bundle.getBoolean("is_flipendo_aggressive", false);
            }
        } catch (Exception e) {
            if (DEBUG) Log.e(TAG, "flipendo not found", e);
        }

        String title = lowBatteryNotification.mContext.getString(
                R.string.low_battery_notification_title,
                NumberFormat.getPercentInstance().format(batteryLevel * 0.01d));
        String text = lowBatteryNotification.mContext.getString(
                isFlipendoAggressive ? R.string.low_battery_notification_text_ebs
                                     : R.string.low_battery_notification_text);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                lowBatteryNotification.mContext, NotificationChannels.BATTERY);
        builder.setSmallIcon(R.drawable.ic_power_saver);
        builder.setContentTitle(NotificationCompat.Builder.limitCharSequenceLength(title));
        builder.setContentText(NotificationCompat.Builder.limitCharSequenceLength(text));
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(NotificationCompat.Builder.limitCharSequenceLength(text)));
        builder.setOnlyAlertOnce(!alert);
        builder.setDeleteIntent(PowerUtils.createPendingIntent(
                lowBatteryNotification.mContext, "PNW.dismissedWarning", null));
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setLocalOnly(true);

        if (isFlipendoAggressive && lowBatteryNotification.mKeyguardManager.isDeviceLocked()) {
            builder.setContentIntent(PendingIntent.getActivity(
                    lowBatteryNotification.mContext, 0,
                    new Intent("android.settings.BATTERY_SAVER_SETTINGS").setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE));
        } else {
            builder.addAction(
                    lowBatteryNotification.mContext.getString(R.string.battery_saver_start_action),
                    PowerUtils.createPendingIntent(lowBatteryNotification.mContext, "PNW.startSaver", null));
        }

        PowerUtils.overrideNotificationAppName(lowBatteryNotification.mContext, builder);
        lowBatteryNotification.mNotificationManager.notifyAsUser(
                "low_battery", 3, builder.build(), UserHandle.ALL);
    }
}
