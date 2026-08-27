package com.google.android.systemui.googlebattery;

import android.content.Context;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Locale;

import vendor.google.google_battery.ChargingStage;
import vendor.google.google_battery.IGoogleBattery;

public class AdaptiveChargingManager {
    private static final String TAG = "AdaptiveChargingManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final String FEATURE_ADAPTIVE_CHARGING =
            "com.google.android.feature.ADAPTIVE_CHARGING";

    Context mContext;

    public interface AdaptiveChargingStatusReceiver {
        default void onDestroyInterface() {}

        void onReceiveStatus(String stage, int seconds);
    }

    public AdaptiveChargingManager(Context context) {
        mContext = context;
    }

    public boolean hasAdaptiveChargingFeature() {
        return mContext.getPackageManager().hasSystemFeature(FEATURE_ADAPTIVE_CHARGING);
    }

    public boolean isAvailable() {
        return hasAdaptiveChargingFeature()
                && DeviceConfig.getBoolean(
                        "adaptive_charging", "adaptive_charging_enabled", true);
    }

    public boolean isEnabled() {
        return Settings.Secure.getInt(
                        mContext.getContentResolver(), "adaptive_charging_enabled", 1)
                == 1;
    }

    public void setEnabled(boolean enabled) {
        Settings.Secure.putInt(
                mContext.getContentResolver(), "adaptive_charging_enabled", enabled ? 1 : 0);
    }

    public static boolean isStageActive(String stage) {
        return "Active".equals(stage);
    }

    public static boolean isStageEnabled(String stage) {
        return "Enabled".equals(stage);
    }

    public static boolean isStageActiveOrEnabled(String stage) {
        return isStageActive(stage) || isStageEnabled(stage);
    }

    public static boolean isActive(String stage, int seconds) {
        return isStageActiveOrEnabled(stage) && seconds > 0;
    }

    private Locale getLocale() {
        LocaleList locales = mContext.getResources().getConfiguration().getLocales();
        return (locales == null || locales.isEmpty()) ? Locale.getDefault() : locales.get(0);
    }

    public String formatTimeToFull(long completionTime) {
        return DateFormat.format(
                        DateFormat.getBestDateTimePattern(getLocale(), DateFormat.is24HourFormat(this.mContext) ? "Hm" : "hma"), completionTime)
                .toString();
    }

    public boolean setAdaptiveChargingDeadline(int seconds) {
        IGoogleBattery hal = GoogleBatteryManager.initHalInterface(null);
        boolean success = false;
        if (hal == null) {
            return false;
        }
        try {
            hal.setChargingDeadline(seconds);
            success = true;
        } catch (ServiceSpecificException | RemoteException | IllegalArgumentException e) {
            Log.e(TAG, "setChargingDeadline failed: ", e);
        }
        GoogleBatteryManager.destroyHalInterface(hal, null);
        return success;
    }

    public boolean setDefaultChargingPolicy() {
        IGoogleBattery hal = GoogleBatteryManager.initHalInterface(null);
        boolean success = false;
        if (hal == null) {
            return false;
        }
        try {
            hal.setChargingPolicy(1);
            success = true;
        } catch (ServiceSpecificException | RemoteException | IllegalArgumentException e) {
            Log.e(TAG, "setChargingPolicy failed: ", e);
        }
        GoogleBatteryManager.destroyHalInterface(hal, null);
        return success;
    }

    private void queryStatusReceived(
            AdaptiveChargingStatusReceiver receiver, String stage, int seconds) {
        if (DEBUG) {
            Log.d(
                    TAG,
                    "getChargingStageDeadlineCallback stage: \""
                            + stage
                            + "\", seconds: "
                            + seconds);
        }
        receiver.onReceiveStatus(stage, seconds);
    }

    public void queryStatus(final AdaptiveChargingStatusReceiver receiver) {
        IBinder.DeathRecipient deathRecipient =
                new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        if (DEBUG) {
                            Log.d(TAG, "serviceDied");
                        }
                        receiver.onDestroyInterface();
                    }
                };
        IGoogleBattery hal = GoogleBatteryManager.initHalInterface(deathRecipient);
        if (hal == null) {
            receiver.onDestroyInterface();
            return;
        }
        try {
            ChargingStage stage = hal.getChargingStageAndDeadline();
            queryStatusReceived(receiver, stage.stage, stage.deadlineSecs);
        } catch (ServiceSpecificException | RemoteException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to get Adaptive Charging status: ", e);
        }
        GoogleBatteryManager.destroyHalInterface(hal, deathRecipient);
        receiver.onDestroyInterface();
    }
}
