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
package com.google.android.systemui.statusbar;

import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.FaceHelpMessageDeferralFactory;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.domain.interactor.BiometricMessageInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryBiometricSettingsInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFingerprintAuthInteractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.KeyguardIndication;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.util.IndicationHelper;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.securelockdevice.domain.interactor.SecureLockDeviceInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.user.domain.interactor.UserLogoutInteractor;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.wakelock.WakeLock;
import com.google.android.systemui.googlebattery.AdaptiveChargingManager;
import com.google.android.systemui.power.PowerUtils;

import dagger.Lazy;

import java.text.NumberFormat;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

/**
 * Google implementation of {@link KeyguardIndicationController} adding Adaptive Charging,
 * 80% charge limit estimation, and reverse wireless charging to the lockscreen charging indication.
 */
@SysUISingleton
public class KeyguardIndicationControllerGoogle extends KeyguardIndicationController {
    private static final String TAG = "KeyguardIndicationControllerGoogle";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ACTION_ADAPTIVE_CHARGING_DEADLINE_SET =
            "com.google.android.systemui.adaptivecharging.ADAPTIVE_CHARGING_DEADLINE_SET";
    private static final String TUNER_KEY_ADAPTIVE_CHARGING_ENABLED =
            "adaptive_charging_enabled";

    private final TunerService mTunerService;
    private final DeviceConfigProxy mDeviceConfig;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final SecureSettings mSecureSettings;
    private final AdaptiveChargingManager mAdaptiveChargingManager;

    private boolean mAdaptiveChargingActive;
    private boolean mAdaptiveChargingEnabledInSettings;
    private long mEstimatedChargeCompletion = -1L;
    private boolean mGoogleListenersRegistered;

    private final AdaptiveChargingManager.AdaptiveChargingStatusReceiver
            mAdaptiveChargingStatusReceiver =
                    new AdaptiveChargingManager.AdaptiveChargingStatusReceiver() {
                        @Override
                        public void onDestroyInterface() {}

                        @Override
                        public void onReceiveStatus(int seconds, String stage) {
                            boolean wasActive = mAdaptiveChargingActive;
                            mAdaptiveChargingActive =
                                    AdaptiveChargingManager.isActive(stage, seconds);
                            if (mAdaptiveChargingActive) {
                                mEstimatedChargeCompletion =
                                        TimeUnit.SECONDS.toMillis(seconds + 29)
                                                + System.currentTimeMillis();
                            } else {
                                mEstimatedChargeCompletion = -1L;
                            }
                            if (DEBUG) {
                                Log.d(TAG, "Adaptive charging active=" + mAdaptiveChargingActive
                                        + ", estimated=" + mEstimatedChargeCompletion
                                        + ", wasActive=" + wasActive);
                            }
                            if (wasActive != mAdaptiveChargingActive) {
                                updateDeviceEntryIndication(true);
                            }
                        }
                    };

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_ADAPTIVE_CHARGING_DEADLINE_SET.equals(intent.getAction())) {
                        triggerAdaptiveChargingStatusUpdate();
                    }
                }
            };

    @Inject
    public KeyguardIndicationControllerGoogle(
            Context context,
            @Main Looper mainLooper,
            WakeLock.Builder wakeLockBuilder,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DockManager dockManager,
            BroadcastDispatcher broadcastDispatcher,
            DevicePolicyManager devicePolicyManager,
            IBatteryStats iBatteryStats,
            UserManager userManager,
            TunerService tunerService,
            DeviceConfigProxy deviceConfigProxy,
            SecureSettings secureSettings,
            @Main DelayableExecutor mainExecutor,
            @Background DelayableExecutor backgroundExecutor,
            FalsingManager falsingManager,
            AuthController authController,
            LockPatternUtils lockPatternUtils,
            ScreenLifecycle screenLifecycle,
            KeyguardBypassController keyguardBypassController,
            AccessibilityManager accessibilityManager,
            FaceHelpMessageDeferralFactory faceHelpMessageDeferralFactory,
            KeyguardLogger keyguardLogger,
            AlternateBouncerInteractor alternateBouncerInteractor,
            BouncerInteractor bouncerInteractor,
            AlarmManager alarmManager,
            UserTracker userTracker,
            BouncerMessageInteractor bouncerMessageInteractor,
            IndicationHelper indicationHelper,
            DeviceEntryBiometricSettingsInteractor deviceEntryBiometricSettingsInteractor,
            KeyguardInteractor keyguardInteractor,
            BiometricMessageInteractor biometricMessageInteractor,
            DeviceEntryFingerprintAuthInteractor deviceEntryFingerprintAuthInteractor,
            DeviceEntryFaceAuthInteractor deviceEntryFaceAuthInteractor,
            UserLogoutInteractor userLogoutInteractor,
            Lazy<SecureLockDeviceInteractor> secureLockDeviceInteractor,
            AdaptiveChargingManager adaptiveChargingManager) {
        super(
                context,
                mainLooper,
                wakeLockBuilder,
                keyguardStateController,
                statusBarStateController,
                keyguardUpdateMonitor,
                dockManager,
                broadcastDispatcher,
                devicePolicyManager,
                iBatteryStats,
                userManager,
                tunerService,
                deviceConfigProxy,
                secureSettings,
                mainExecutor,
                backgroundExecutor,
                falsingManager,
                authController,
                lockPatternUtils,
                screenLifecycle,
                keyguardBypassController,
                accessibilityManager,
                faceHelpMessageDeferralFactory,
                keyguardLogger,
                alternateBouncerInteractor,
                bouncerInteractor,
                alarmManager,
                userTracker,
                bouncerMessageInteractor,
                indicationHelper,
                deviceEntryBiometricSettingsInteractor,
                keyguardInteractor,
                biometricMessageInteractor,
                deviceEntryFingerprintAuthInteractor,
                deviceEntryFaceAuthInteractor,
                userLogoutInteractor,
                secureLockDeviceInteractor);
        mTunerService = tunerService;
        mDeviceConfig = deviceConfigProxy;
        mBroadcastDispatcher = broadcastDispatcher;
        mSecureSettings = secureSettings;
        mAdaptiveChargingManager = adaptiveChargingManager;
    }

    @Override
    public void init() {
        super.init();
        if (mGoogleListenersRegistered) {
            return;
        }
        mGoogleListenersRegistered = true;

        mTunerService.addTunable(
                (key, newValue) -> {
                    if (TUNER_KEY_ADAPTIVE_CHARGING_ENABLED.equals(key)) {
                        refreshAdaptiveChargingEnabled();
                    }
                },
                TUNER_KEY_ADAPTIVE_CHARGING_ENABLED);

        mDeviceConfig.addOnPropertiesChangedListener(
                "adaptive_charging",
                mMainExecutor,
                (properties) -> {
                    if (properties.getKeyset().contains("adaptive_charging_enabled")) {
                        refreshAdaptiveChargingEnabled();
                    }
                });

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_ADAPTIVE_CHARGING_DEADLINE_SET);
        mBroadcastDispatcher.registerReceiver(
                mBroadcastReceiver, filter, null, null, Context.RECEIVER_EXPORTED);
    }

    private void refreshAdaptiveChargingEnabled() {
        boolean available = mAdaptiveChargingManager.isAvailable();
        boolean enabled = mAdaptiveChargingManager.isEnabled();
        mAdaptiveChargingEnabledInSettings = available && enabled;
        if (DEBUG) {
            Log.d(TAG, "refreshAdaptiveChargingEnabled: available=" + available
                    + ", enabled=" + enabled);
        }
        if (mPowerPluggedIn) {
            triggerAdaptiveChargingStatusUpdate();
        } else {
            mAdaptiveChargingActive = false;
        }
    }

    @VisibleForTesting
    public void triggerAdaptiveChargingStatusUpdate() {
        if (!mAdaptiveChargingEnabledInSettings) {
            mAdaptiveChargingActive = false;
            return;
        }
        mBackgroundExecutor.execute(
                () -> mAdaptiveChargingManager.queryStatus(mAdaptiveChargingStatusReceiver));
    }

    @Override
    protected String computePowerIndication() {
        if (mPowerPluggedIn) {
            triggerAdaptiveChargingStatusUpdate();
        } else {
            mAdaptiveChargingActive = false;
        }

        if (mPowerPluggedIn && mAdaptiveChargingEnabledInSettings && mAdaptiveChargingActive && mEstimatedChargeCompletion > 0) {
            return mContext.getString(
                    R.string.adaptive_charging_time_estimate,
                    NumberFormat.getPercentInstance().format(mBatteryLevel / 100.0f),
                    mAdaptiveChargingManager.formatTimeToFull(mEstimatedChargeCompletion));
        }
        if (mBatteryDefender) {
            return mContext.getString(
                    R.string.keyguard_plugged_in_charging_limited,
                    NumberFormat.getPercentInstance().format(mBatteryLevel / 100.0f));
        }
        return computePowerChargingStringIndication();
    }

    @Override
    protected String computePowerChargingStringIndication() {
        String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100.0f);
        boolean hasChargingTime = mChargingTimeRemaining > 0;
        if (mChargingStatus == 4) {
            if (PowerUtils.isChargeLimitEnabledForUser(mSecureSettings, mUserTracker.getUserId())) {
                if (hasChargingTime && mBatteryLevel < 80) {
                    boolean isV2Enabled = isChargingStringV2Enabled();
                    Context context = mContext;
                    long chargingTimeRemaining = mChargingTimeRemaining;
                    return isV2Enabled
                            ? context.getString(
                                    R.string.keyguard_indication_charging_time_charge_limit,
                                    getChargingTimeFormatted(context, chargingTimeRemaining),
                                    percentage)
                            : context.getString(
                                    R.string.keyguard_indication_charging_time_charge_limit_v1,
                                    Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                                            context, chargingTimeRemaining),
                                    percentage);
                }
                if (mBatteryLevel >= 80) {
                    return mContext.getString(
                            R.string.keyguard_indication_charging_time_reach_charge_limit, percentage);
                }
            }
        }
        return super.computePowerChargingStringIndication();
    }

    public boolean isChargingStringV2Enabled() {
        return SystemProperties.getBoolean("charging_string.apply_v2", false);
    }

    public String getChargingTimeFormatted(Context context, long chargingTimeRemainingMillis) {
        if (!isChargingStringV2Enabled()) {
            return Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                    context, chargingTimeRemainingMillis);
        }
        long estimatedTargetTimeMillis = System.currentTimeMillis() + chargingTimeRemainingMillis;
        if (chargingTimeRemainingMillis >= 900000) {
            long targetTimeAbs = Math.abs(estimatedTargetTimeMillis);
            long quarterHourMillis = 900000L;
            estimatedTargetTimeMillis =
                    quarterHourMillis * (((targetTimeAbs + quarterHourMillis) - 1) / quarterHourMillis);
        }
        return android.icu.text.DateFormat.getInstanceForSkeleton(
                android.text.format.DateFormat.getTimeFormatString(context))
                .format(java.util.Date.from(java.time.Instant.ofEpochMilli(estimatedTargetTimeMillis)));
    }

    public void setReverseChargingMessage(CharSequence message) {
        if (mStatusBarStateController.isDozing()) {
            return;
        }
        if (TextUtils.isEmpty(message)) {
            mRotateTextViewController.hideIndication(10);
            return;
        }
        Drawable drawable = mContext.getDrawable(R.anim.reverse_charging_animation);
        mRotateTextViewController.updateIndication(
                10,
                new KeyguardIndication.Builder()
                        .setMessage(message)
                        .setIcon(drawable)
                        .setTextColor(mInitialTextColorState)
                        .build(),
                true);
    }
}
