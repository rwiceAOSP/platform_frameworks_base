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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.BatteryController;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;
import com.google.android.systemui.googlebattery.AdaptiveChargingManager;
import com.google.android.systemui.googlebattery.GoogleBatteryManager;
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;

import dagger.Lazy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

import vendor.google.google_battery.IGoogleBattery;

/**
 * Google implementation of {@link PowerNotificationWarnings}: handles tiered
 * low battery notifications (low, severe, extreme), first-time battery saver
 * confirmation dialog, Adaptive Charging, and 80% Charge Limit.
 */
@SysUISingleton
public class PowerNotificationWarningsGoogleImpl extends PowerNotificationWarnings {
    private static final String TAG = "PowerNotificationWarningsGoogleImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String ACTION_START_FLIPENDO = "systemui.power.action.START_FLIPENDO";
    public static final String ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING =
            "PNW.dismissSevereLowBatteryWarning";
    public static final String ACTION_FLIPENDO_START_SAVER_CONFIRMATION =
            "FLIPENDO.startSaverConfirmation";

    public static final String ACTION_AC_CHARGE_NORMALLY = "PNW.acChargeNormally";
    public static final String ACTION_DISMISS_ADAPTIVE_CHARGING_WARNING =
            "systemui.power.action.dismissAdaptiveChargingWarning";
    public static final String ACTION_ADAPTIVE_CHARGING_DEADLINE_SET =
            "com.google.android.systemui.adaptivecharging.ADAPTIVE_CHARGING_DEADLINE_SET";

    protected final Context mContext;
    protected final UiEventLogger mUiEventLogger;
    protected final Handler mHandler;
    protected final Executor mBgExecutor;
    protected final LowPowerWarningsController mLowPowerWarningsController;
    protected final Provider<BatterySaverConfirmationDialog> mBatterySaverConfirmationDialogProvider;

    private final SecureSettings mSecureSettings;
    private final AdaptiveChargingNotification mAdaptiveChargingNotification;
    private final ChargeLimitDiscoveryNotification mChargeLimitDiscoveryNotification;
    private final ChargeLimitController mChargeLimitController;

    protected final BroadcastReceiver mGoogleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleGoogleIntent(intent);
        }
    };

    @Inject
    public PowerNotificationWarningsGoogleImpl(
            Context context,
            ActivityStarter activityStarter,
            BroadcastSender broadcastSender,
            Lazy<BatteryController> batteryControllerLazy,
            DialogTransitionAnimator dialogTransitionAnimator,
            UiEventLogger uiEventLogger,
            UserTracker userTracker,
            SystemUIDialog.Factory systemUIDialogFactory,
            GlobalSettings globalSettings,
            SecureSettings secureSettings,
            AdaptiveChargingManager adaptiveChargingManager,
            ChargeLimitController chargeLimitController,
            ChargeLimitDiscoveryNotification chargeLimitDiscoveryNotification,
            Provider<BatterySaverConfirmationDialog> batterySaverConfirmationDialogProvider,
            @Main Looper mainLooper,
            @Background Executor bgExecutor) {
        super(context, activityStarter, broadcastSender, batteryControllerLazy,
                dialogTransitionAnimator, uiEventLogger, userTracker, systemUIDialogFactory);
        mContext = context;
        mUiEventLogger = uiEventLogger;
        mHandler = new Handler(mainLooper);
        mBgExecutor = bgExecutor;
        mSecureSettings = secureSettings;
        mBatterySaverConfirmationDialogProvider = batterySaverConfirmationDialogProvider;
        mChargeLimitController = chargeLimitController;
        mChargeLimitDiscoveryNotification = chargeLimitDiscoveryNotification;
        mAdaptiveChargingNotification =
                new AdaptiveChargingNotification(context, adaptiveChargingManager, uiEventLogger);

        mLowPowerWarningsController = new LowPowerWarningsController(
                context, globalSettings, uiEventLogger, bgExecutor);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_FLIPENDO);
        filter.addAction(ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING);
        filter.addAction(BatterySaverUtils.ACTION_SHOW_START_SAVER_CONFIRMATION);
        filter.addAction(ACTION_FLIPENDO_START_SAVER_CONFIRMATION);

        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        filter.addAction(ACTION_AC_CHARGE_NORMALLY);
        filter.addAction(ACTION_DISMISS_ADAPTIVE_CHARGING_WARNING);
        filter.addAction(ACTION_ADAPTIVE_CHARGING_DEADLINE_SET);
        filter.addAction(ChargeLimitDiscoveryNotification.ACTION_ENABLE_CHARGE_LIMIT_FEATURE);
        filter.addAction(
                ChargeLimitDiscoveryNotification.ACTION_DISMISS_CHARGE_LIMIT_NOTIFICATION);
        filter.addAction(ChargeLimitDiscoveryNotification.ACTION_CLICK_CHARGE_LIMIT_NOTIFICATION);

        mContext.registerReceiver(mGoogleReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    protected void handleGoogleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (DEBUG) {
            Log.d(TAG, "onReceive: " + action);
        }
        switch (action) {
            case ACTION_START_FLIPENDO:
                ThreadUtils.postOnBackgroundThread(() -> PowerUtils.applyExtremeSaverMode(mContext));
                if (mLowPowerWarningsController != null
                        && mLowPowerWarningsController.severeLowBatteryNotification != null) {
                    mLowPowerWarningsController.severeLowBatteryNotification.cancel();
                }
                break;
            case ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING:
                if (mLowPowerWarningsController != null) {
                    if (mLowPowerWarningsController.severeLowBatteryNotification != null) {
                        mLowPowerWarningsController.severeLowBatteryNotification.cancel();
                    }
                    mLowPowerWarningsController.severeLowBatteryNotificationCancelled = true;
                }
                break;
            case BatterySaverUtils.ACTION_SHOW_START_SAVER_CONFIRMATION:
            case ACTION_FLIPENDO_START_SAVER_CONFIRMATION:
                if (mContext.getResources().getBoolean(R.bool.config_extra_battery_saver_confirmation)) {
                    if (mBatterySaverConfirmationDialogProvider != null) {
                        BatterySaverConfirmationDialog dialog =
                                mBatterySaverConfirmationDialogProvider.get();
                        Expandable expandable = null;
                        if (mBatteryControllerLazy != null && mBatteryControllerLazy.get() != null) {
                            WeakReference<Expandable> ref =
                                    mBatteryControllerLazy.get().getLastPowerSaverStartExpandable();
                            if (ref != null) {
                                expandable = ref.get();
                            }
                        }
                        dialog.show(expandable);
                    }
                }
                break;
            case Intent.ACTION_BATTERY_CHANGED:
                if (mAdaptiveChargingNotification != null) {
                    mAdaptiveChargingNotification.resolveBatteryChangedIntent(intent);
                }
                if (mChargeLimitDiscoveryNotification != null) {
                    mChargeLimitDiscoveryNotification.dispatchIntent(intent);
                }
                maybeReapplyChargeLimitPolicy(intent);
                break;
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
                maybeReapplyChargeLimitPolicy(intent);
                break;
            case ACTION_ADAPTIVE_CHARGING_DEADLINE_SET:
                if (mAdaptiveChargingNotification != null) {
                    mAdaptiveChargingNotification.checkAdaptiveChargingStatus(true);
                }
                break;
            case ACTION_AC_CHARGE_NORMALLY:
                if (mUiEventLogger != null) {
                    mUiEventLogger.log(BatteryMetricEvent.ADAPTIVE_CHARGING_NOTIFICATION_BYPASS);
                }
                mBgExecutor.execute(() -> {
                    IGoogleBattery hal = GoogleBatteryManager.initHalInterface(null);
                    if (hal != null) {
                        try {
                            hal.setChargingDeadline(-3);
                        } catch (ServiceSpecificException | RemoteException
                                | IllegalArgumentException e) {
                            Log.e(TAG, "setChargingDeadline failed: ", e);
                        }
                        GoogleBatteryManager.destroyHalInterface(hal, null);
                    }
                    mHandler.post(() -> {
                        if (mAdaptiveChargingNotification != null) {
                            mAdaptiveChargingNotification.cancelNotification();
                        }
                        Intent deadlineChanged =
                                new Intent(ACTION_ADAPTIVE_CHARGING_DEADLINE_SET)
                                        .setPackage(mContext.getPackageName())
                                        .setFlags(Intent.FLAG_RECEIVER_FOREGROUND
                                                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                        mContext.sendBroadcastAsUser(deadlineChanged, UserHandle.ALL);
                    });
                });
                break;
            case ACTION_DISMISS_ADAPTIVE_CHARGING_WARNING:
                if (mUiEventLogger != null) {
                    mUiEventLogger.log(BatteryMetricEvent.DELETE_ADAPTIVE_CHARGING_NOTIFICATION);
                }
                break;
            default:
                if (mChargeLimitDiscoveryNotification != null) {
                    mChargeLimitDiscoveryNotification.dispatchIntent(intent);
                }
                break;
        }
    }

    private void maybeReapplyChargeLimitPolicy(Intent intent) {
        if (mChargeLimitController == null || mSecureSettings == null || mUserTracker == null) {
            return;
        }
        int userId = mUserTracker.getUserId();
        if (!PowerUtils.isChargeLimitEnabledForUser(mSecureSettings, userId)) {
            return;
        }
        Log.d(TAG, "Enable charge limit upon boot/battery event.");
        mChargeLimitController.setChargingPolicy(2 /* LONGLIFE */);
    }

    @Override
    public void showLowBatteryWarning(boolean playSound) {
        if (mLowPowerWarningsController != null) {
            BatteryEventType eventType;
            if (mBatteryLevel <= 3) {
                eventType = BatteryEventType.EXTREME_LOW_BATTERY;
            } else if (mCurrentBatterySnapshot != null
                    && mBatteryLevel <= mCurrentBatterySnapshot.getSevereLevelThreshold()) {
                eventType = BatteryEventType.SEVERE_LOW_BATTERY;
            } else {
                eventType = BatteryEventType.LOW_BATTERY;
            }
            mLowPowerWarningsController.onBatteryEventUpdate(
                    mBatteryLevel, Collections.singletonList(eventType));
        } else {
            super.showLowBatteryWarning(playSound);
        }
    }

    @Override
    public void dismissLowBatteryWarning() {
        if (mLowPowerWarningsController != null) {
            mLowPowerWarningsController.cancelNotification();
        }
        super.dismissLowBatteryWarning();
    }

    @Override
    public void userSwitched() {
        super.userSwitched();
        if (mLowPowerWarningsController != null && mLowPowerWarningsController.prevBatteryLevel != null) {
            mLowPowerWarningsController.onBatteryEventUpdate(
                    mLowPowerWarningsController.prevBatteryLevel,
                    mLowPowerWarningsController.prevBatteryEventTypes);
        }
        if (mChargeLimitController != null && mSecureSettings != null && mUserTracker != null) {
            int userId = mUserTracker.getUserId();
            boolean limitEnabled = PowerUtils.isChargeLimitEnabledForUser(mSecureSettings, userId);
            mChargeLimitController.setChargingPolicy(limitEnabled ? 2 : 1);
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        if (mLowPowerWarningsController != null) {
            pw.println("\tdump LowPowerWarningsController states");
            pw.println("\t\tprevBatteryLevel: " + mLowPowerWarningsController.prevBatteryLevel);
            pw.println("\t\tprevBatteryEventType: " + mLowPowerWarningsController.prevBatteryEventTypes);
            pw.println("\t\tisScheduledByPercentage: " + mLowPowerWarningsController.isScheduledByPercentage());
            pw.println("\t\tlowBatteryNotificationCancelled: " + mLowPowerWarningsController.lowBatteryNotificationCancelled);
            pw.println("\t\tsevereLowBatteryNotificationCancelled: " + mLowPowerWarningsController.severeLowBatteryNotificationCancelled);
        }
        if (mAdaptiveChargingNotification != null) {
            pw.print("mAdaptiveChargingWasActive=");
            pw.println(mAdaptiveChargingNotification.mWasActive);
        }
    }
}
