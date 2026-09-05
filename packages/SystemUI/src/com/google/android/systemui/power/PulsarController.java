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

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.res.R;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

/**
 * Controller for Battery Health Assistance (Pulsar / AACP) feature state and notifications.
 * High-fidelity port of SystemUIGoogle PulsarController (cp2a).
 */
@SysUISingleton
public final class PulsarController {
    private static final String TAG = "PulsarController";
    public static final String PROP_OPT_OUT = "persist.vendor.pulsar.opt_out";
    public static final String SETTING_KEY = "pulsar_sysprop_enabled";

    public static final long THREE_DAYS_MILLIS = Duration.ofDays(3).toMillis();
    public static final long THIRTY_DAYS_MILLIS = Duration.ofDays(30).toMillis();

    private static final String PREFS_NAME = "pulsar_shared_prefs";
    private static final String KEY_ENABLED_NOTIF_SHOWN = "pulsar_enabled_notification_shown";
    private static final String KEY_DAY_THREE_SHOWN = "pulsar_day_three_notification_shown";
    private static final String KEY_DAY_THIRTY_SHOWN = "pulsar_day_thirty_notification_shown";
    private static final String KEY_DISABLED_TIMESTAMP = "pulsar_disabled_timestamp";

    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final SharedPreferences mSharedPreferences;
    private final Executor mBgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver mPulsarObserver;

    // Optional dependencies that may be null in AOSP (UiEventLogger)
    private UiEventLogger mUiEventLogger;

    @Inject
    public PulsarController(Context context) {
        mContext = context.getApplicationContext();
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
        mSharedPreferences = mContext.getSharedPreferences(PREFS_NAME, 0);

        mPulsarObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (selfChange) return;
                boolean enabled = isPulsarEnabled();
                int secureVal = 0;
                try {
                    secureVal = Settings.Secure.getInt(mContext.getContentResolver(), SETTING_KEY, enabled ? 1 : 0);
                } catch (Exception ignored) {}
                boolean pulsarSysPropEnabled = secureVal == 1;
                Log.d(TAG, "pulsarSysPropEnabled: " + pulsarSysPropEnabled);
                if (pulsarSysPropEnabled && mSharedPreferences.getBoolean(KEY_DAY_THREE_SHOWN, false)) {
                    mSharedPreferences.edit().putBoolean(KEY_DAY_THIRTY_SHOWN, true).apply();
                    try {
                        mContext.getContentResolver().unregisterContentObserver(this);
                    } catch (Exception ignored) {}
                    Log.d(TAG, "Unregister pulsar observer since user reactivates the feature.");
                    return;
                }
                mBgExecutor.execute(() -> updatePulsarDisabledTimestamp(pulsarSysPropEnabled));
            }
        };

        // Register observer for pulsar_sysprop_enabled as stock does, if not already day_thirty shown
        if (!mSharedPreferences.getBoolean(KEY_DAY_THIRTY_SHOWN, false)) {
            try {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(SETTING_KEY), false, mPulsarObserver);
                Log.d(TAG, "Registered pulsar observer");
            } catch (Exception e) {
                Log.e(TAG, "Failed to register pulsar observer", e);
            }
        }

        // Register broadcast receiver for pulsar intents and power connected
        IntentFilter filter = new IntentFilter();
        filter.addAction("systemui.power.action.clickPulsarEnabledNotification");
        filter.addAction("systemui.power.action.dismissPulsarEnabledNotification");
        filter.addAction("systemui.power.action.clickPulsarReminderNotification");
        filter.addAction("systemui.power.action.dismissPulsarReminderNotification");
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        try {
            // Use Context receiver (not BroadcastDispatcher) for simplicity
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    dispatchIntent(intent);
                }
            };
            mContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register pulsar broadcast receiver", e);
        }
    }

    /**
     * Checks if Battery Health Assistance (Pulsar) is currently enabled.
     * Default state is enabled (opt_out = "0").
     * Fidelity to PulsarController.Companion.isPulsarEnabled()
     */
    public static boolean isPulsarEnabled() {
        try {
            String val = SystemProperties.get(PROP_OPT_OUT, "0");
            Log.d(TAG, "getSystemProperty: key= " + PROP_OPT_OUT + ", value= " + val);
            return "0".equals(val);
        } catch (RuntimeException e) {
            Log.e(TAG, "getSystemProperty: failed.", e);
            return true;
        }
    }

    public SharedPreferences getSharedPreferences() {
        return mSharedPreferences;
    }

    public void onClickPulsarNotification(BatteryMetricEvent event) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.google.android.settings.intelligence",
                "com.google.android.settings.intelligence.modules.battery.impl.pulsar.PulsarActivity"));
        intent.setFlags(268468224); // 0x10008000
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch PulsarActivity", e);
            // Fallback to battery settings
            try {
                Intent fallback = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(fallback, UserHandle.CURRENT);
            } catch (Exception e2) {
                Log.e(TAG, "Fallback launch failed", e2);
            }
        }
        if (mUiEventLogger != null && event != null) {
            mUiEventLogger.log(event);
        }
    }

    void dispatchIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.d(TAG, "dispatchIntent: " + action);
        switch (action) {
            case "systemui.power.action.clickPulsarEnabledNotification":
                onClickPulsarNotification(BatteryMetricEvent.CLICK_PULSAR_ENABLED_NOTIFICATION);
                break;
            case "systemui.power.action.dismissPulsarEnabledNotification":
                if (mUiEventLogger != null) {
                    mUiEventLogger.log(BatteryMetricEvent.DISMISS_PULSAR_ENABLED_NOTIFICATION);
                }
                break;
            case "systemui.power.action.clickPulsarReminderNotification":
                onClickPulsarNotification(BatteryMetricEvent.CLICK_PULSAR_REMINDER_NOTIFICATION);
                break;
            case "systemui.power.action.dismissPulsarReminderNotification":
                if (mUiEventLogger != null) {
                    mUiEventLogger.log(BatteryMetricEvent.DISMISS_PULSAR_REMINDER_NOTIFICATION);
                }
                break;
            case Intent.ACTION_POWER_CONNECTED:
                mBgExecutor.execute(this::checkAndSendNotifications);
                break;
            case Intent.ACTION_BOOT_COMPLETED:
                // Stock also checks on boot via PowerNotificationWarnings; we trigger same check
                mBgExecutor.execute(this::checkAndSendNotifications);
                break;
        }
    }

    /** Entry point called on boot or power connect to maybe show notifications */
    public void checkAndSendNotifications() {
        // Step 1: verify Pulsar activity is enabled via PackageManager
        boolean pulsarAvailable = false;
        try {
            Intent probe = new Intent("com.google.android.settings.intelligence.action.PULSAR");
            probe.addCategory(Intent.CATEGORY_DEFAULT);
            ComponentName resolved = probe.resolveActivity(mContext.getPackageManager());
            if (resolved != null) {
                int enabled = mContext.getPackageManager().getComponentEnabledSetting(
                        new ComponentName(resolved.getPackageName(), resolved.getClassName()));
                // 1 = COMPONENT_ENABLED_STATE_ENABLED, 0 = DEFAULT (considered enabled if activity exists)
                // Stock checks ==1, but treat DEFAULT as available as well if activity exists
                pulsarAvailable = (enabled == 1 || enabled == 0);
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Pulsar is not available.", e);
        }
        if (!pulsarAvailable) {
            Log.d(TAG, "Pulsar activity is not enabled. Skip the notifications.");
            return;
        }

        boolean enabled = isPulsarEnabled();
        // Enabled notification
        Log.d(TAG, "sendPulsarEnabledNotificationIfNeeded");
        if (!enabled) {
            Log.d(TAG, "Skip PulsarEnabledNotification since pulsar is disabled.");
        } else {
            if (!mSharedPreferences.getBoolean(KEY_ENABLED_NOTIF_SHOWN, false)) {
                mSharedPreferences.edit().putBoolean(KEY_ENABLED_NOTIF_SHOWN, true).apply();
                mMainHandler.post(() -> showNotification("pulsar_enabled"));
            } else {
                Log.d(TAG, "Skip PulsarEnabledNotification since it's shown before.");
            }
        }

        // Reminder notification
        sendPulsarReminderNotificationIfNeeded(enabled);
    }

    void sendPulsarReminderNotificationIfNeeded(boolean isPulsarEnabled) {
        Log.d(TAG, "sendPulsarReminderNotificationIfNeeded");
        if (isPulsarEnabled) {
            // If pulsar is enabled, no reminder needed
            return;
        }
        if (mSharedPreferences.getBoolean(KEY_DAY_THIRTY_SHOWN, false)) {
            return;
        }
        long now = System.currentTimeMillis();
        long disabledTs = mSharedPreferences.getLong(KEY_DISABLED_TIMESTAMP, Long.MAX_VALUE);
        long elapsed = now - disabledTs;
        boolean dayThreeShown = mSharedPreferences.getBoolean(KEY_DAY_THREE_SHOWN, false);

        if (!dayThreeShown && elapsed >= THREE_DAYS_MILLIS) {
            mMainHandler.post(() -> showNotification("pulsar_reminder"));
            Log.d(TAG, "Show day 3 reminder notification.");
            mSharedPreferences.edit().putBoolean(KEY_DAY_THREE_SHOWN, true).apply();
            return;
        }
        if (elapsed >= THIRTY_DAYS_MILLIS) {
            mMainHandler.post(() -> showNotification("pulsar_reminder"));
            Log.d(TAG, "Show day 30 reminder notification and unregister the observer.");
            mSharedPreferences.edit().putBoolean(KEY_DAY_THIRTY_SHOWN, true).apply();
            try {
                mContext.getContentResolver().unregisterContentObserver(mPulsarObserver);
            } catch (Exception ignored) {}
        }
    }

    void updatePulsarDisabledTimestamp(boolean enabled) {
        long ts = enabled ? Long.MAX_VALUE : System.currentTimeMillis();
        mSharedPreferences.edit().putLong(KEY_DISABLED_TIMESTAMP, ts).apply();
        Log.d(TAG, "updatePulsarDisabledTimestamp: " + ts);
    }

    void showNotification(String tag) {
        if ("pulsar_enabled".equals(tag)) {
            showPulsarEnabledNotification();
        } else if ("pulsar_reminder".equals(tag)) {
            showPulsarReminderNotification();
        } else {
            Log.w(TAG, "Unknown notification tag: " + tag);
        }
    }

    private void showPulsarEnabledNotification() {
        try {
            String text = mContext.getString(R.string.pulsar_enabled_notification_text);
            String title = mContext.getString(R.string.pulsar_enabled_notification_title);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, "BAT")
                    .setSmallIcon(R.drawable.ic_power_saver)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(PowerUtils.createPendingIntent(mContext,
                            "systemui.power.action.clickPulsarEnabledNotification", null))
                    .setDeleteIntent(PowerUtils.createPendingIntent(mContext,
                            "systemui.power.action.dismissPulsarEnabledNotification", null))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setLocalOnly(true);
            // Flags as stock: 16 and 2
            builder.setFlag(16, true);
            builder.setFlag(2, true);
            // Learn more action
            String learnMore = mContext.getString(R.string.pulsar_enabled_notification_help_url);
            if (learnMore != null && !learnMore.isEmpty()) {
                builder.addAction(0, mContext.getString(R.string.learn_more),
                        PowerUtils.createHelpArticlePendingIntentAsUser(
                                R.string.pulsar_enabled_notification_help_url, mContext));
            }
            PowerUtils.overrideNotificationAppName(mContext, builder);
            Notification n = builder.build();
            mNotificationManager.notifyAsUser("pulsar_enabled",
                    PowerUtils.PULSAR_ENABLED_NOTIFICATION_ID, n, UserHandle.CURRENT);
            if (mUiEventLogger != null) {
                mUiEventLogger.log(BatteryMetricEvent.SEND_PULSAR_ENABLED_NOTIFICATION);
            }
            Log.d(TAG, "PulsarEnabledNotification shown");
        } catch (Exception e) {
            Log.e(TAG, "showPulsarEnabledNotification failed", e);
        }
    }

    private void showPulsarReminderNotification() {
        try {
            String text = mContext.getString(R.string.pulsar_reminder_notification_text);
            String title = mContext.getString(R.string.pulsar_reminder_notification_title);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, "BAT")
                    .setSmallIcon(R.drawable.ic_power_saver)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(PowerUtils.createPendingIntent(mContext,
                            "systemui.power.action.clickPulsarReminderNotification", null))
                    .setDeleteIntent(PowerUtils.createPendingIntent(mContext,
                            "systemui.power.action.dismissPulsarReminderNotification", null))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setLocalOnly(true);
            builder.setFlag(16, true);
            builder.setFlag(2, true);
            String learnMore = mContext.getString(R.string.pulsar_enabled_notification_help_url);
            if (learnMore != null && !learnMore.isEmpty()) {
                builder.addAction(0, mContext.getString(R.string.learn_more),
                        PowerUtils.createHelpArticlePendingIntentAsUser(
                                R.string.pulsar_enabled_notification_help_url, mContext));
            }
            PowerUtils.overrideNotificationAppName(mContext, builder);
            Notification n = builder.build();
            mNotificationManager.notifyAsUser("pulsar_reminder",
                    PowerUtils.PULSAR_REMINDER_NOTIFICATION_ID, n, UserHandle.CURRENT);
            if (mUiEventLogger != null) {
                mUiEventLogger.log(BatteryMetricEvent.SEND_PULSAR_REMINDER_NOTIFICATION);
            }
            Log.d(TAG, "PulsarReminderNotification shown");
        } catch (Exception e) {
            Log.e(TAG, "showPulsarReminderNotification failed", e);
        }
    }
}
