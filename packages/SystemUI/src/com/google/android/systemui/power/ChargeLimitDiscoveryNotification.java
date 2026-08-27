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

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.util.Utils;
import com.android.systemui.util.settings.SecureSettings;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * One-time per user discovery notification for the 80% charge limit feature.
 */
public class ChargeLimitDiscoveryNotification {
    private static final String TAG = "ChargeLimitDiscoveryNotification";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);

    public static final String ACTION_ENABLE_CHARGE_LIMIT_FEATURE =
            "systemui.power.action.enableChargeLimitFeature";
    public static final String ACTION_DISMISS_CHARGE_LIMIT_NOTIFICATION =
            "systemui.power.action.dismissChargeLimitNotification";
    public static final String ACTION_CLICK_CHARGE_LIMIT_NOTIFICATION =
            "systemui.power.action.clickChargeLimitNotification";

    private final Context mContext;
    private final ActivityStarter mActivityStarter;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final DeviceStateManager mDeviceStateManager;
    private final NotificationManager mNotificationManager;
    private final SecureSettings mSecureSettings;
    private final SubscriptionManager mSubscriptionManager;
    private final UiEventLogger mUiEventLogger;
    private final UserTracker mUserTracker;
    private final ChargeLimitController mChargeLimitController;
    private final Handler mMainHandler;
    private final Executor mBackgroundExecutor;
    private final SharedPreferences mSharedPreferences;

    private boolean mIsPluggedIn = false;

    @Inject
    public ChargeLimitDiscoveryNotification(
            Context context,
            ActivityStarter activityStarter,
            DeviceProvisionedController deviceProvisionedController,
            DeviceStateManager deviceStateManager,
            NotificationManager notificationManager,
            SecureSettings secureSettings,
            SubscriptionManager subscriptionManager,
            UiEventLogger uiEventLogger,
            UserTracker userTracker,
            ChargeLimitController chargeLimitController,
            @Main Looper mainLooper,
            @Background Executor backgroundExecutor) {
        mContext = context;
        mActivityStarter = activityStarter;
        mDeviceProvisionedController = deviceProvisionedController;
        mDeviceStateManager = deviceStateManager;
        mNotificationManager = notificationManager;
        mSecureSettings = secureSettings;
        mSubscriptionManager = subscriptionManager;
        mUiEventLogger = uiEventLogger;
        mUserTracker = userTracker;
        mChargeLimitController = chargeLimitController;
        mMainHandler = new Handler(mainLooper);
        mBackgroundExecutor = backgroundExecutor;
        mSharedPreferences =
                context.getApplicationContext().getSharedPreferences(
                        "charge_limit_shared_prefs", Context.MODE_PRIVATE);
    }

    public void dispatchIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        switch (action) {
            case Intent.ACTION_BATTERY_CHANGED:
                if (!mDeviceProvisionedController.isDeviceProvisioned()) {
                    Log.d(TAG, "[dispatchIntent] skip since device is not provisioned.");
                    return;
                }
                boolean wasPluggedIn = mIsPluggedIn;
                boolean isPluggedIn = BatteryStatus.isPluggedIn(intent.getIntExtra("plugged", 0));
                mIsPluggedIn = isPluggedIn;
                Log.d(TAG, "isPluggedIn = " + isPluggedIn);
                if (isPluggedIn && !wasPluggedIn) {
                    mBackgroundExecutor.execute(this::sendNotificationIfNeeded);
                }
                break;
            case ACTION_ENABLE_CHARGE_LIMIT_FEATURE:
                Log.d(TAG, "Enable charge limit manually.");
                mChargeLimitController.setChargingPolicy(2 /* LONGLIFE */);
                mSecureSettings.putIntForUser(
                        "charge_optimization_mode", 1, mUserTracker.getUserId());
                if (mUiEventLogger != null) {
                    mUiEventLogger.log(BatteryMetricEvent.ENABLE_CHARGE_LIMIT_FEATURE);
                }
                mNotificationManager.cancelAsUser("charge_limit",
                        R.string.charge_limit_discovery_notification_title, UserHandle.CURRENT);
                break;
            case ACTION_DISMISS_CHARGE_LIMIT_NOTIFICATION:
                if (mUiEventLogger != null) {
                    mUiEventLogger.log(
                            BatteryMetricEvent.DISMISS_CHARGE_LIMIT_DISCOVERY_NOTIFICATION);
                }
                break;
            case ACTION_CLICK_CHARGE_LIMIT_NOTIFICATION:
                Intent settingsIntent = new Intent();
                settingsIntent.setComponent(new ComponentName(
                        "com.google.android.settings.intelligence",
                        "com.google.android.settings.intelligence.modules.battery.impl"
                                + ".chargingoptimization.ChargingOptimizationActivity"));
                settingsIntent.setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mActivityStarter.startActivity(settingsIntent, true);
                if (mUiEventLogger != null) {
                    mUiEventLogger.log(
                            BatteryMetricEvent.CLICK_CHARGE_LIMIT_DISCOVERY_NOTIFICATION);
                }
                break;
            default:
                break;
        }
    }

    void sendNotificationIfNeeded() {
        Log.d(TAG, "sendNotification");
        boolean show = false;
        if (mSharedPreferences.getLong(ActivityManager.getCurrentUser()
                        + "|last_charge_limit_notification_time",
                -1L) == -1L) {
            if (Utils.isDeviceFoldable(mContext.getResources(), mDeviceStateManager)
                    && !PowerUtils.isSimInEuCountry(mSubscriptionManager)) {
                putChargeLimitNotificationTimestamp();
            } else {
                show = true;
            }
        }
        Log.d(TAG, "showNotification: " + show);
        if (!show) {
            return;
        }
        putChargeLimitNotificationTimestamp();
        mMainHandler.post(this::sendNotification);
    }

    void sendNotification() {
        String text = mContext.getString(R.string.charge_limit_discovery_notification_text);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mContext, NotificationChannels.BATTERY);
        builder.setSmallIcon(R.drawable.ic_battery_charging);
        builder.setContentTitle(NotificationCompat.Builder.limitCharSequenceLength(
                mContext.getString(R.string.charge_limit_discovery_notification_title)));
        builder.setContentText(NotificationCompat.Builder.limitCharSequenceLength(text));
        builder.setContentIntent(PowerUtils.createPendingIntent(mContext,
                ACTION_CLICK_CHARGE_LIMIT_NOTIFICATION, null));
        builder.setDeleteIntent(PowerUtils.createPendingIntent(mContext,
                ACTION_DISMISS_CHARGE_LIMIT_NOTIFICATION, null));
        builder.setStyle(new NotificationCompat.BigTextStyle()
                .bigText(NotificationCompat.Builder.limitCharSequenceLength(text)));
        builder.setAutoCancel(true);
        builder.setOngoing(true);
        builder.setSilent(true);
        builder.addAction(mContext.getString(R.string.battery_health_notify_learn_more),
                PowerUtils.createHelpArticlePendingIntentAsUser(
                        R.string.charge_limit_discovery_notification_help_url, mContext));
        builder.addAction(mContext.getString(R.string.charge_limit_discovery_notification_enable_button),
                PowerUtils.createPendingIntent(mContext, ACTION_ENABLE_CHARGE_LIMIT_FEATURE, null));
        builder.setLocalOnly(true);
        PowerUtils.overrideNotificationAppName(mContext, builder);
        mNotificationManager.notifyAsUser("charge_limit",
                R.string.charge_limit_discovery_notification_title, builder.build(),
                UserHandle.CURRENT);
        if (mUiEventLogger != null) {
            mUiEventLogger.log(BatteryMetricEvent.SEND_CHARGE_LIMIT_DISCOVERY_NOTIFICATION);
        }
    }

    void putChargeLimitNotificationTimestamp() {
        long timestamp = System.currentTimeMillis();
        String key = ActivityManager.getCurrentUser() + "|last_charge_limit_notification_time";
        Log.d(TAG, "putTimestamp: " + timestamp + ", key: " + key);
        mSharedPreferences.edit().putLong(key, timestamp).apply();
    }
}
