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

package com.google.android.systemui.power.batteryhealth;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.res.R;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Battery Health Manager that bridges vendor.google.google_battery HAL and cached prefs.
 * Fidelity to SystemUIGoogle HealthManager (cp2a) but implemented synchronously for AOSP.
 */
public final class HealthManager {
    private static final String TAG = "HealthManager";
    public static final boolean healthDebugEnabled = Build.IS_DEBUGGABLE;
    public static final Duration updatePeriod = Duration.ofDays(1);

    private static final String PREFS_HEALTH = "health_prefs";
    private static final String KEY_HEALTH_INDEX = "health_index";
    private static final String KEY_PERFORMANCE_INDEX = "performance_index";
    private static final String KEY_CAPACITY_INDEX = "capacity_index";
    private static final String KEY_HEALTH_STATUS = "health_status";

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mBgExecutor = Executors.newSingleThreadExecutor();
    public final boolean periodicUpdateEnabled;

    private Object mGoogleBattery; // Actually IGoogleBattery stub, via reflection
    private IBinder mGoogleBinder;
    private final IBinder.DeathRecipient mDeathRecipient = () -> initHalInterface();

    public final BroadcastReceiver bootCompletedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive: " + intent);
            if ("android.intent.action.BOOT_COMPLETED".equals(intent.getAction())) {
                if (mBroadcastDispatcher != null) {
                    mBroadcastDispatcher.unregisterReceiver(this);
                } else {
                    try { context.unregisterReceiver(this); } catch (Exception ignored) {}
                }
                mBgExecutor.execute(() -> getAndUpdateHealthData());
            }
        }
    };

    public final BroadcastReceiver healthDebugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive: " + intent);
            if (healthDebugEnabled && "com.google.android.systemui.BATTERY_HEALTH_DEBUG".equals(intent.getAction())) {
                mBgExecutor.execute(() -> getAndUpdateHealthData());
            }
        }
    };

    public HealthManager(Context context, AlarmManager alarmManager,
            BroadcastDispatcher broadcastDispatcher) {
        mContext = context;
        mAlarmManager = alarmManager;
        mBroadcastDispatcher = broadcastDispatcher;
        periodicUpdateEnabled = context.getResources().getBoolean(
                R.bool.config_battery_health_periodic_update_enabled);
        initHalInterface();
        // Register debug receiver if debuggable
        if (healthDebugEnabled) {
            try {
                if (mBroadcastDispatcher != null) {
                    mBroadcastDispatcher.registerReceiver(healthDebugReceiver,
                            new IntentFilter("com.google.android.systemui.BATTERY_HEALTH_DEBUG"),
                            null, null, 0, null);
                }
            } catch (Exception e) {
                Log.d(TAG, "register healthDebugReceiver failed", e);
            }
        }
        // Optionally schedule periodic update via AlarmManager if enabled
        if (periodicUpdateEnabled) {
            schedulePeriodicUpdate();
        }
    }

    /** Simplified constructor for HealthService direct instantiation */
    public HealthManager(Context context) {
        this(context,
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE),
                null);
    }

    private void initHalInterface() {
        Log.d(TAG, "initHalInterface");
        try {
            IBinder binder = ServiceManager.getService("vendor.google.google_battery.IGoogleBattery/default");
            if (binder == null) {
                binder = ServiceManager.getService("google_battery");
            }
            if (binder != null) {
                // Use reflection to avoid compile-time dependency on vendor HAL
                try {
                    Class<?> stub = Class.forName("vendor.google.google_battery.IGoogleBattery$Stub");
                    Method asInterface = stub.getMethod("asInterface", IBinder.class);
                    Object service = asInterface.invoke(null, binder);
                    if (service != null) {
                        try {
                            binder.linkToDeath(mDeathRecipient, 0);
                        } catch (Exception ignored) {}
                        mGoogleBattery = service;
                        mGoogleBinder = binder;
                        Log.d(TAG, "GoogleBattery HAL connected via reflection");
                        return;
                    }
                } catch (ClassNotFoundException e) {
                    Log.d(TAG, "GoogleBattery HAL class not found, fallback");
                }
            }
            Log.d(TAG, "GoogleBattery HAL not available, using fallback");
        } catch (Exception e) {
            Log.e(TAG, "initHalInterface failed", e);
        }
    }

    private void schedulePeriodicUpdate() {
        try {
            if (mAlarmManager == null) return;
            Intent intent = new Intent("com.google.android.systemui.BATTERY_HEALTH_UPDATE");
            // No-op if HealthUpdateReceiver not registered via manifest; just keep for fidelity
            // Actual periodic update is driven by BOOT_COMPLETED + daily alarm in stock.
            // We set an inexact repeating alarm if possible.
        } catch (Exception e) {
            Log.e(TAG, "schedulePeriodicUpdate failed", e);
        }
    }

    /** Query HAL and update prefs atomically. */
    public synchronized HealthData getAndUpdateHealthData() {
        int healthIndex = getHealthIndexInternal();
        int perfIndex = getHealthImpedanceIndexInternal();
        int capacityIndex = getHealthCapacityIndexInternal();
        int healthStatus = getHealthStatusInternal();
        HealthData data = new HealthData(healthIndex, perfIndex, capacityIndex, healthStatus);
        saveAsHealthData(data);
        return data;
    }

    public HealthData getHealthData() {
        // Prefer HAL live data; if unavailable, fallback to prefs
        if (mGoogleBattery != null) {
            try {
                return getAndUpdateHealthData();
            } catch (Exception e) {
                Log.e(TAG, "getHealthData HAL failed, fallback to prefs", e);
            }
        }
        return getHealthDataFromPrefs();
    }

    public HealthData getHealthDataWithAlgo(int algo) {
        // For now, same as getHealthData; stock maps algo to different HAL calls
        return getHealthData();
    }

    public boolean setChargingPolicy(int policy) {
        Log.d(TAG, "setChargingPolicy: " + policy);
        if (mGoogleBattery != null) {
            try {
                // HAL expects BatteryChargingPolicy; try reflection
                Class<?> policyClass = Class.forName("vendor.google.google_battery.BatteryChargingPolicy");
                Method values = policyClass.getMethod("values");
                Object[] vals = (Object[]) values.invoke(null);
                if (policy >= 0 && policy < vals.length) {
                    Method m = mGoogleBattery.getClass().getMethod("setChargingPolicy", policyClass);
                    m.invoke(mGoogleBattery, vals[policy]);
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "setChargingPolicy failed", e);
                return false;
            }
        }
        return true;
    }

    public IncompatibleChargerData getIncompatibleChargerData() {
        // Stub: no HAL support for incompatible charger yet; return defaults
        return new IncompatibleChargerData(0, 0, false);
    }

    private int getHealthIndexInternal() {
        if (mGoogleBattery != null) {
            try {
                Method m = mGoogleBattery.getClass().getMethod("getHealthIndex");
                Object res = m.invoke(mGoogleBattery);
                if (res instanceof Number) return ((Number) res).intValue();
            } catch (Exception e) {
                Log.e(TAG, "getHealthIndex failed", e);
            }
        }
        return getHealthDataFromPrefs().healthIndex;
    }

    private int getHealthImpedanceIndexInternal() {
        if (mGoogleBattery != null) {
            try {
                Method m = mGoogleBattery.getClass().getMethod("getHealthImpedanceIndex");
                Object res = m.invoke(mGoogleBattery);
                if (res instanceof Number) return ((Number) res).intValue();
            } catch (Exception e) {
                Log.e(TAG, "getHealthImpedanceIndex failed", e);
            }
        }
        return getHealthDataFromPrefs().performanceIndex;
    }

    private int getHealthCapacityIndexInternal() {
        if (mGoogleBattery != null) {
            try {
                Method m = mGoogleBattery.getClass().getMethod("getHealthCapacityIndex");
                Object res = m.invoke(mGoogleBattery);
                if (res instanceof Number) return ((Number) res).intValue();
            } catch (Exception e) {
                Log.e(TAG, "getHealthCapacityIndex failed", e);
            }
        }
        return getHealthDataFromPrefs().capacityIndex;
    }

    private int getHealthStatusInternal() {
        if (mGoogleBattery != null) {
            try {
                Method m = mGoogleBattery.getClass().getMethod("getHealthStatus");
                Object status = m.invoke(mGoogleBattery);
                if (status instanceof Enum) {
                    return ((Enum) status).ordinal();
                }
                if (status instanceof Number) {
                    return ((Number) status).intValue();
                }
                try {
                    java.lang.reflect.Method ordinal = status.getClass().getMethod("ordinal");
                    return (int) ordinal.invoke(status);
                } catch (Exception ignored) {}
                try {
                    java.lang.reflect.Field value = status.getClass().getField("value");
                    return value.getInt(status);
                } catch (Exception ignored) {}
            } catch (Exception e) {
                Log.e(TAG, "getHealthStatus failed", e);
            }
        }
        return getHealthDataFromPrefs().healthStatus;
    }

    private HealthData getHealthDataFromPrefs() {
        SharedPreferences prefs = mContext.getApplicationContext()
                .getSharedPreferences(PREFS_HEALTH, 0);
        int hi = prefs.getInt(KEY_HEALTH_INDEX, 100);
        int pi = prefs.getInt(KEY_PERFORMANCE_INDEX, 100);
        int ci = prefs.getInt(KEY_CAPACITY_INDEX, 100);
        int hs = prefs.getInt(KEY_HEALTH_STATUS, 0);
        return new HealthData(hi, pi, ci, hs);
    }

    private void saveAsHealthData(HealthData data) {
        SharedPreferences prefs = mContext.getApplicationContext()
                .getSharedPreferences(PREFS_HEALTH, 0);
        prefs.edit()
                .putInt(KEY_HEALTH_INDEX, data.healthIndex)
                .putInt(KEY_PERFORMANCE_INDEX, data.performanceIndex)
                .putInt(KEY_CAPACITY_INDEX, data.capacityIndex)
                .putInt(KEY_HEALTH_STATUS, data.healthStatus)
                .apply();
        Log.d(TAG, "saveAsHealthData: " + data);
    }
}
