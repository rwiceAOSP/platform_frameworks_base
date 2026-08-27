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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.util.Log;

import com.android.systemui.res.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

public final class HealthService extends Service {
    private static final String TAG = "HealthService";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    public static final boolean healthDebugEnabled = Build.IS_DEBUGGABLE;

    public static final Set<String> SUPPORTED_CALLERS =
            new HashSet<>(
                    Arrays.asList(
                            "com.android.settings",
                            "com.android.systemui",
                            "com.google.android.apps.diagnosticstool",
                            "com.google.android.apps.pixel.support",
                            "com.google.android.pixelsystemservice",
                            "com.google.android.settings.intelligence"));

    private HealthManager mHealthManager;
    private boolean mHealthFeatureEnabled;
    private final RemoteCallbackList<IHealthListener> mHealthListeners =
            new RemoteCallbackList<>();

    private final IHealthService.Stub mBinder =
            new IHealthService.Stub() {
                @Override
                public HealthData getHealthData() {
                    ensureSupportedCallers();
                    return mHealthFeatureEnabled && mHealthManager != null
                            ? mHealthManager.getHealthData()
                            : null;
                }

                @Override
                public void registerHealthListener(IHealthListener listener) {
                    ensureSupportedCallers();
                    if (listener != null) {
                        mHealthListeners.register(listener);
                    }
                }

                @Override
                public void unregisterHealthListener(IHealthListener listener) {
                    ensureSupportedCallers();
                    if (listener != null) {
                        mHealthListeners.unregister(listener);
                    }
                }

                @Override
                public IncompatibleChargerData getIncompatibleChargerData() {
                    ensureSupportedCallers();
                    return mHealthManager != null
                            ? mHealthManager.getIncompatibleChargerData()
                            : null;
                }

                @Override
                public HealthData getHealthDataWithAlgo(int algo) {
                    ensureSupportedCallers();
                    return mHealthFeatureEnabled && mHealthManager != null
                            ? mHealthManager.getHealthDataWithAlgo(algo)
                            : null;
                }

                @Override
                public boolean setChargingPolicy(int policy) {
                    ensureSupportedCallers();
                    return mHealthManager != null && mHealthManager.setChargingPolicy(policy);
                }

                @Override
                public boolean isPulsarEnabled() {
                    ensureSupportedCallers();
                    return false;
                }

                @Override
                public void setPulsarEnabled(boolean enabled) {
                    ensureSupportedCallers();
                }
            };

    private void ensureSupportedCallers() {
        int callingUid = Binder.getCallingUid();
        String[] packages = getPackageManager().getPackagesForUid(callingUid);
        if (packages != null) {
            for (String pkg : packages) {
                if (SUPPORTED_CALLERS.contains(pkg)) {
                    return;
                }
            }
        }
        throw new SecurityException("Unsupported caller UID: " + callingUid);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }
        try {
            mHealthFeatureEnabled =
                    getResources().getBoolean(R.bool.config_battery_index_enabled);
        } catch (Exception e) {
            mHealthFeatureEnabled = false;
        }
        mHealthManager =
                new HealthManager(
                        this,
                        (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE),
                        null,
                        Executors.newSingleThreadExecutor());
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onBind: " + intent);
        }
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHealthListeners.kill();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("HealthService state:");
        writer.println("  healthFeatureEnabled=" + mHealthFeatureEnabled);
        if (mHealthManager != null) {
            writer.println("  healthData=" + mHealthManager.getHealthData());
        }
    }
}
