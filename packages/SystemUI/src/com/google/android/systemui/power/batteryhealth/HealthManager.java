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
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.Log;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.res.R;
import com.google.android.systemui.googlebattery.GoogleBatteryManager;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import vendor.google.google_battery.BatteryHealthStats;
import vendor.google.google_battery.IGoogleBattery;

public class HealthManager {
    private static final String TAG = "HealthManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    public static final boolean healthDebugEnabled = Build.IS_DEBUGGABLE;
    public static final Duration updatePeriod = Duration.ofDays(1);

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mBgExecutor;
    private final SharedPreferences mSharedPreferences;
    private final boolean mPeriodicUpdateEnabled;
    private IGoogleBattery mGoogleBattery;

    private final IBinder.DeathRecipient mDeathRecipient =
            new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.w(TAG, "Google Battery HAL died");
                    mGoogleBattery = null;
                }
            };

    private final BroadcastReceiver mBootCompletedReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                        if (DEBUG) {
                            Log.d(TAG, "onReceive: BOOT_COMPLETED");
                        }
                        if (mBroadcastDispatcher != null) {
                            mBroadcastDispatcher.unregisterReceiver(this);
                        }
                        mBgExecutor.execute(HealthManager.this::getAndUpdateHealthData);
                    }
                }
            };

    private final BroadcastReceiver mHealthDebugReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (healthDebugEnabled
                            && "com.google.android.systemui.BATTERY_HEALTH_DEBUG"
                                    .equals(intent.getAction())) {
                        if (DEBUG) {
                            Log.d(TAG, "onReceive: BATTERY_HEALTH_DEBUG");
                        }
                        mBgExecutor.execute(HealthManager.this::getAndUpdateHealthData);
                    }
                }
            };

    public HealthManager(Context context) {
        this(
                context,
                (AlarmManager) context.getSystemService(AlarmManager.class),
                null,
                Executors.newSingleThreadExecutor());
    }

    public HealthManager(
            Context context,
            AlarmManager alarmManager,
            BroadcastDispatcher broadcastDispatcher,
            Executor bgExecutor) {
        mContext = context;
        mAlarmManager = alarmManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mBgExecutor = bgExecutor;
        mSharedPreferences =
                context.getApplicationContext()
                        .getSharedPreferences("battery_health_shared_prefs", Context.MODE_PRIVATE);
        boolean periodicEnabled = true;
        try {
            periodicEnabled =
                    context.getResources()
                            .getBoolean(R.bool.config_battery_health_periodic_update_enabled);
        } catch (Exception ignored) {
        }
        mPeriodicUpdateEnabled = periodicEnabled;

        if (mBroadcastDispatcher != null) {
            IntentFilter bootFilter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
            mBroadcastDispatcher.registerReceiver(mBootCompletedReceiver, bootFilter);
            if (healthDebugEnabled) {
                IntentFilter debugFilter =
                        new IntentFilter("com.google.android.systemui.BATTERY_HEALTH_DEBUG");
                mBroadcastDispatcher.registerReceiver(mHealthDebugReceiver, debugFilter);
            }
        }

        mBgExecutor.execute(this::initHalInterface);
    }

    public synchronized void initHalInterface() {
        if (mGoogleBattery != null) {
            return;
        }
        mGoogleBattery = GoogleBatteryManager.initHalInterface(mDeathRecipient);
    }

    private synchronized IGoogleBattery getGoogleBattery() {
        if (mGoogleBattery == null) {
            initHalInterface();
        }
        return mGoogleBattery;
    }

    public synchronized boolean setChargingPolicy(int policy) {
        IGoogleBattery hal = getGoogleBattery();
        if (hal == null) {
            Log.w(TAG, "setChargingPolicy: Google Battery HAL is null");
            return false;
        }
        try {
            hal.setChargingPolicy(policy);
            Log.i(TAG, "setChargingPolicy: " + policy);
            return true;
        } catch (ServiceSpecificException | RemoteException | IllegalArgumentException e) {
            Log.w(TAG, "setChargingPolicy failed: ", e);
            return false;
        }
    }

    public HealthData getHealthData() {
        HealthData data = getAndUpdateHealthData();
        return data != null ? data : getHealthDataFromPrefs();
    }

    public HealthData getAndUpdateHealthData() {
        IGoogleBattery hal = getGoogleBattery();
        if (hal == null) {
            Log.w(TAG, "getAndUpdateHealthData: Google Battery HAL is null");
            return null;
        }
        try {
            int healthIndex = hal.getHealthIndex();
            int capacityIndex = hal.getHealthCapacityIndex();
            int impedanceIndex = hal.getHealthImpedanceIndex();
            int healthStatus = hal.getHealthStatus();

            HealthData data =
                    new HealthData(healthIndex, impedanceIndex, capacityIndex, healthStatus);
            if (DEBUG) {
                Log.d(TAG, "getAndUpdateHealthData: " + data);
            }
            saveAsHealthData(data);
            return data;
        } catch (ServiceSpecificException | RemoteException | IllegalArgumentException e) {
            Log.w(TAG, "getAndUpdateHealthData failed: ", e);
            return null;
        }
    }

    public HealthData getHealthDataWithAlgo(int algo) {
        IGoogleBattery hal = getGoogleBattery();
        if (hal == null) {
            return getHealthDataFromPrefs();
        }
        try {
            BatteryHealthStats stats = hal.getHealthStats(algo);
            if (stats != null) {
                HealthData data =
                        new HealthData(
                                stats.healthIndex,
                                stats.impedanceIndex,
                                stats.capacityIndex,
                                stats.healthStatus);
                saveAsHealthData(data);
                return data;
            }
        } catch (Exception e) {
            if (DEBUG) {
                Log.d(TAG, "getHealthStats with algo " + algo + " failed: ", e);
            }
        }
        return getHealthData();
    }

    public HealthData getHealthDataFromPrefs() {
        int hi = mSharedPreferences.getInt("health_index", -1);
        int pi = mSharedPreferences.getInt("perf_index", -1);
        int ci = mSharedPreferences.getInt("capacity_index", -1);
        int hs = mSharedPreferences.getInt("health_status", -1);
        return new HealthData(hi, pi, ci, hs);
    }

    private void saveAsHealthData(HealthData data) {
        if (data == null) {
            return;
        }
        mSharedPreferences
                .edit()
                .putInt("health_index", data.healthIndex)
                .putInt("perf_index", data.performanceIndex)
                .putInt("capacity_index", data.capacityIndex)
                .putInt("health_status", data.healthStatus)
                .putLong("health_data_timestamp", System.currentTimeMillis())
                .apply();
    }

    public IncompatibleChargerData getIncompatibleChargerData() {
        boolean isIncompatible =
                mSharedPreferences.getBoolean("is_incompatible_charger", false);
        long lastCompTime =
                mSharedPreferences.getLong("last_compatible_charger_time", 0L);
        long lastIncompTime =
                mSharedPreferences.getLong("last_incompatible_charger_time", 0L);
        return new IncompatibleChargerData(lastCompTime, lastIncompTime, isIncompatible);
    }
}
