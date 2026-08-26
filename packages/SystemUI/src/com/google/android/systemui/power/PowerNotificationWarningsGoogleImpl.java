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
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;

import dagger.Lazy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Google implementation of {@link PowerNotificationWarnings}: handles tiered
 * low battery notifications (low, severe, extreme), first-time battery saver
 * confirmation dialog, and integration with Flipendo.
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

    protected final Context mContext;
    protected final UiEventLogger mUiEventLogger;
    protected final Handler mHandler;
    protected final Executor mBgExecutor;
    protected final LowPowerWarningsController mLowPowerWarningsController;
    protected final Provider<BatterySaverConfirmationDialog> mBatterySaverConfirmationDialogProvider;

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
            Provider<BatterySaverConfirmationDialog> batterySaverConfirmationDialogProvider,
            @Main Looper mainLooper,
            @Background Executor bgExecutor) {
        super(context, activityStarter, broadcastSender, batteryControllerLazy,
                dialogTransitionAnimator, uiEventLogger, userTracker, systemUIDialogFactory);
        mContext = context;
        mUiEventLogger = uiEventLogger;
        mHandler = new Handler(mainLooper);
        mBgExecutor = bgExecutor;
        mBatterySaverConfirmationDialogProvider = batterySaverConfirmationDialogProvider;

        mLowPowerWarningsController = new LowPowerWarningsController(
                context, globalSettings, uiEventLogger, bgExecutor);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_FLIPENDO);
        filter.addAction(ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING);
        filter.addAction(BatterySaverUtils.ACTION_SHOW_START_SAVER_CONFIRMATION);
        filter.addAction(ACTION_FLIPENDO_START_SAVER_CONFIRMATION);
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
            default:
                break;
        }
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
    }
}
