package com.google.android.systemui.reversecharging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.TextUtils;
import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.settingslib.Utils;
import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.google.android.systemui.keyguard.domain.interactor.AmbientIndicationInteractor;
import com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle;

import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public final class ReverseChargingViewController extends BroadcastReceiver implements CoreStartable, LifecycleOwner, BatteryController.BatteryStateChangeCallback {
    public static final boolean DEBUG = Log.isLoggable("ReverseChargingViewCtrl", 3);

    public final AmbientIndicationInteractor mAmbientIndicationInteractor;
    public final BatteryControllerImpl mBatteryController;
    public final BroadcastDispatcher mBroadcastDispatcher;
    public String mContentDescription;
    public final Context mContext;
    public final KeyguardIndicationControllerGoogle mKeyguardIndicationController;
    public int mLevel;
    public final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);
    public final Executor mMainExecutor;
    public String mName;
    public boolean mProvidingBattery;
    public boolean mReverse;
    public String mReverseCharging;
    public String mSlotReverseCharging;
    public final StatusBarIconController mStatusBarIconController;

    @Inject
    public ReverseChargingViewController(
            Context context,
            BatteryControllerImpl batteryControllerImpl,
            StatusBarIconController statusBarIconController,
            BroadcastDispatcher broadcastDispatcher,
            @Main Executor executor,
            KeyguardIndicationControllerGoogle keyguardIndicationControllerGoogle,
            AmbientIndicationInteractor ambientIndicationInteractor) {
        this.mBatteryController = batteryControllerImpl;
        this.mStatusBarIconController = statusBarIconController;
        this.mContext = context;
        this.mBroadcastDispatcher = broadcastDispatcher;
        this.mMainExecutor = executor;
        this.mKeyguardIndicationController = keyguardIndicationControllerGoogle;
        this.mAmbientIndicationInteractor = ambientIndicationInteractor;
        this.mReverseCharging = context.getString(R.string.charging_reverse_text);
        this.mSlotReverseCharging = context.getString(R.string.status_bar_google_reverse_charging);
        this.mContentDescription = context.getString(R.string.reverse_charging_on_notification_title);
    }

    @Override
    public void start() {
        this.mBatteryController.observe(this.mLifecycle, this);
        this.mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        this.mBroadcastDispatcher.registerReceiver(this, intentFilter);
    }

    @Override
    public Lifecycle getLifecycle() {
        return this.mLifecycle;
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        this.mReverse = this.mBatteryController.isReverseOn();
        if (DEBUG) {
            Log.d("ReverseChargingViewCtrl", "onBatteryLevelChanged(): rtx=" + (this.mReverse ? 1 : 0) + " level=" + this.mLevel + " name=" + this.mName);
        }
        this.mMainExecutor.execute(this::updateMessage);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
            this.mReverseCharging = this.mContext.getString(R.string.charging_reverse_text);
            this.mSlotReverseCharging = this.mContext.getString(R.string.status_bar_google_reverse_charging);
            this.mContentDescription = this.mContext.getString(R.string.reverse_charging_on_notification_title);
            if (DEBUG) {
                Log.d("ReverseChargingViewCtrl", "onReceive(): ACTION_LOCALE_CHANGED");
            }
            this.mMainExecutor.execute(this::updateMessage);
        }
    }

    @Override
    public void onReverseChanged(int rtxLevel, String name, boolean rtx) {
        this.mReverse = rtx;
        this.mLevel = rtxLevel;
        this.mName = name;
        this.mProvidingBattery = rtx && rtxLevel >= 0;
        if (DEBUG) {
            Log.d("ReverseChargingViewCtrl", "onReverseChanged(): rtx=" + (rtx ? 1 : 0) + " level=" + rtxLevel + " name=" + name);
        }
        this.mMainExecutor.execute(this::updateMessage);
    }

    private void updateMessage() {
        if (this.mReverse || !this.mBatteryController.isWirelessCharging() || TextUtils.isEmpty(this.mName)) {
            if (!this.mBatteryController.isWirelessCharging() && !this.mProvidingBattery) {
                this.mAmbientIndicationInteractor.getAmbientIndicationRepository().getWirelessChargingMessage().setValue("");
                if (DEBUG) {
                    Log.d("ReverseChargingViewCtrl", "updateMessage(): reset wlcString");
                }
            }
            String str = this.mProvidingBattery ? this.mReverseCharging : "";
            this.mAmbientIndicationInteractor.getAmbientIndicationRepository().getReverseChargingMessage().setValue(str);
            this.mKeyguardIndicationController.setReverseChargingMessage(str);
            if (DEBUG) {
                Log.d("ReverseChargingViewCtrl", "updateMessage(): rtxString=" + str);
            }
        } else {
            String string = this.mContext.getResources().getString(
                    R.string.reverse_charging_device_providing_charge_text, this.mName,
                    Utils.formatPercentage(this.mLevel));
            if (DEBUG) {
                Log.d("ReverseChargingViewCtrl", "updateMessage(): wlcString=" + string);
            }
            this.mAmbientIndicationInteractor.getAmbientIndicationRepository().getWirelessChargingMessage().setValue(string);
            this.mKeyguardIndicationController.setReverseChargingMessage(string);
        }
        this.mStatusBarIconController.setIcon(this.mSlotReverseCharging, R.drawable.ic_qs_reverse_charging, this.mContentDescription);
        this.mStatusBarIconController.setIconVisibility(this.mSlotReverseCharging, this.mProvidingBattery);
    }
}
