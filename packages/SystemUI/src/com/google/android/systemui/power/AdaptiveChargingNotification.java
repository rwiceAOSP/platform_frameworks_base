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

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.res.R;
import com.android.systemui.util.NotificationChannels;
import com.google.android.systemui.googlebattery.AdaptiveChargingManager;

import java.util.concurrent.TimeUnit;

/**
 * Posts the "Adaptive Charging is on" notification while adaptive charging is
 * actively holding the charge, mirroring SystemUIGoogle behaviour.
 */
public class AdaptiveChargingNotification {
    private static final String TAG = "AdaptiveChargingNotification";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);

    public final AdaptiveChargingManager mAdaptiveChargingManager;
    public final Context mContext;
    public final NotificationManager mNotificationManager;
    public final UiEventLogger mUiEventLogger;
    public final Handler mHandler = new Handler(Looper.getMainLooper());
    boolean mWasActive = false;
    boolean mAdaptiveChargingQueryInBackground = true;

    public AdaptiveChargingNotification(Context context,
            AdaptiveChargingManager adaptiveChargingManager, UiEventLogger uiEventLogger) {
        mContext = context;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mUiEventLogger = uiEventLogger;
        mAdaptiveChargingManager = adaptiveChargingManager;
    }

    public void cancelNotification() {
        if (mWasActive) {
            mNotificationManager.cancelAsUser("adaptive_charging",
                    R.string.adaptive_charging_notify_title, UserHandle.ALL);
            mWasActive = false;
        }
    }

    public void checkAdaptiveChargingStatus(boolean forceUpdate) {
        if (!DeviceConfig.getBoolean("adaptive_charging", "adaptive_charging_notification",
                false)) {
            return;
        }
        AdaptiveChargingManager.AdaptiveChargingStatusReceiver receiver =
                new AdaptiveChargingManager.AdaptiveChargingStatusReceiver() {
                    @Override
                    public void onDestroyInterface() {}

                    @Override
                    public void onReceiveStatus(String stage, int deadlineSecs) {
                        mHandler.post(() -> onStatusReceived(stage, deadlineSecs, forceUpdate));
                    }
                };
        if (!mAdaptiveChargingQueryInBackground) {
            mAdaptiveChargingManager.queryStatus(receiver);
            return;
        }
        AsyncTask.execute(() -> mAdaptiveChargingManager.queryStatus(receiver));
    }

    void onStatusReceived(String stage, int deadlineSecs, boolean forceUpdate) {
        if (DEBUG) {
            Log.d(TAG, "stage=" + stage + ", deadlineSecs=" + deadlineSecs);
        }
        boolean activeOrEnabled =
                "Active".equals(stage) || "Enabled".equals(stage);
        if (!activeOrEnabled || deadlineSecs <= 0) {
            cancelNotification();
            return;
        }
        if (mWasActive && !forceUpdate) {
            return;
        }
        String timeToFull = mAdaptiveChargingManager.formatTimeToFull(
                TimeUnit.SECONDS.toMillis(deadlineSecs + 29) + System.currentTimeMillis());
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mContext, NotificationChannels.BATTERY);
        builder.setShowWhen(false);
        builder.setSilent(true);
        builder.setSmallIcon(R.drawable.ic_battery_charging);
        builder.setContentTitle(NotificationCompat.Builder.limitCharSequenceLength(
                mContext.getString(R.string.adaptive_charging_notify_title)));
        builder.setContentText(NotificationCompat.Builder.limitCharSequenceLength(
                mContext.getString(R.string.adaptive_charging_notify_des, timeToFull)));
        builder.addAction(mContext.getString(R.string.adaptive_charging_notify_turn_off_once),
                PowerUtils.createPendingIntent(mContext, "PNW.acChargeNormally", null));
        builder.setDeleteIntent(PowerUtils.createPendingIntent(mContext,
                "systemui.power.action.dismissAdaptiveChargingWarning", null));
        PowerUtils.overrideNotificationAppName(mContext, builder);
        mNotificationManager.notifyAsUser("adaptive_charging",
                R.string.adaptive_charging_notify_title, builder.build(), UserHandle.ALL);
        mUiEventLogger.log(BatteryMetricEvent.ADAPTIVE_CHARGING_NOTIFICATION);
        mWasActive = true;
    }

    public void resolveBatteryChangedIntent(Intent intent) {
        boolean fullyCharged;
        boolean plugged = intent.getIntExtra("plugged", 0) != 0;
        int status = intent.getIntExtra("status", BatteryManager.BATTERY_STATUS_UNKNOWN);
        int batteryLevel = BatteryStatus.getBatteryLevel(intent);
        fullyCharged = status == BatteryManager.BATTERY_STATUS_FULL || batteryLevel >= 100;
        if (!plugged || fullyCharged) {
            cancelNotification();
        } else {
            checkAdaptiveChargingStatus(false);
        }
    }
}
