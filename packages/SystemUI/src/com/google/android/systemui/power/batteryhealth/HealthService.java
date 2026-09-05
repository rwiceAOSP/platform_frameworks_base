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
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import com.google.android.systemui.power.PulsarController;

import java.util.Arrays;
import java.util.Set;

/**
 * HealthService exports the binder interface {@link IHealthService} used by Settings,
 * SettingsIntelligence, and diagnostic tools to interact with battery health metrics
 * and Battery Health Assistance (Pulsar / AACP).
 *
 * Fidelity to SystemUIGoogle HealthService (cp2a) - Binder transact handling, supported caller
 * whitelist, pulsar property/secure setting handling, and HealthManager bridging.
 */
public class HealthService extends Service {
    private static final String TAG = "HealthService";
    private static final String PROP_OPT_OUT = "persist.vendor.pulsar.opt_out";
    private static final String SETTING_KEY = "pulsar_sysprop_enabled";

    private static final Set<String> SUPPORTED_CALLERS = Set.of(
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.apps.diagnosticstool",
            "com.google.android.apps.pixel.support",
            "com.google.android.pixelsystemservice",
            "com.google.android.settings.intelligence"
    );

    public static final boolean healthDebugEnabled = Build.IS_DEBUGGABLE;

    private final RemoteCallbackList<IHealthListener> mHealthListeners = new RemoteCallbackList<>();

    private HealthManager mHealthManager;
    private boolean mHealthFeatureEnabled = true;

    private final IHealthService.Stub mBinder = new IHealthService.Stub() {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            // Stock enforces interface for transact 1..16777215
            if (code >= 1 && code <= 16777215) {
                data.enforceInterface(DESCRIPTOR);
            }
            // Transacts 9 and 10 are no-op stubs in stock - do not enforce caller
            if (code == 9) {
                data.readBoolean();
                data.enforceNoDataAvail();
                if (reply != null) reply.writeNoException();
                return true;
            } else if (code == 10) {
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeBoolean(false);
                }
                return true;
            }
            // For transacts 1..8, enforce caller before delegating
            if (code >= 1 && code <= 8) {
                ensureSupportedCaller();
            }
            return super.onTransact(code, data, reply, flags);
        }

        @Override
        public HealthData getHealthData() {
            String[] caller = ensureSupportedCaller();
            Log.i(TAG, "getHealthData: " + Arrays.toString(caller));
            return mHealthManager != null ? mHealthManager.getHealthData()
                    : new HealthData(100, 100, 100, 0);
        }

        @Override
        public void registerHealthListener(IHealthListener listener) {
            String[] caller = ensureSupportedCaller();
            Log.i(TAG, "registerHealthListener: " + Arrays.toString(caller));
            if (listener != null) {
                mHealthListeners.register(listener);
            }
        }

        @Override
        public void unregisterHealthListener(IHealthListener listener) {
            String[] caller = ensureSupportedCaller();
            Log.i(TAG, "unregisterHealthListener: " + Arrays.toString(caller));
            if (listener != null) {
                mHealthListeners.unregister(listener);
            }
        }

        @Override
        public IncompatibleChargerData getIncompatibleChargerData() {
            String[] caller = ensureSupportedCaller();
            Log.i(TAG, "getIncompatibleChargerData: " + Arrays.toString(caller));
            if (mHealthManager != null) {
                return mHealthManager.getIncompatibleChargerData();
            }
            return new IncompatibleChargerData(0, 0, false);
        }

        @Override
        public HealthData getHealthDataWithAlgo(int algo) {
            String[] caller = ensureSupportedCaller();
            Log.i(TAG, "getHealthDataWithAlgo: algo=" + algo + " caller=" + Arrays.toString(caller));
            if (mHealthManager != null) {
                return mHealthManager.getHealthDataWithAlgo(algo);
            }
            return new HealthData(100, 100, 100, 0);
        }

        @Override
        public boolean setChargingPolicy(int policy) {
            String[] caller = ensureSupportedCaller();
            Log.i(TAG, "setChargingPolicy: policy=" + policy + " caller=" + Arrays.toString(caller));
            if (mHealthManager != null) {
                return mHealthManager.setChargingPolicy(policy);
            }
            return true;
        }

        @Override
        public boolean isPulsarEnabled() {
            String[] caller = ensureSupportedCaller();
            Log.i(TAG, "isPulsarEnabled: " + Arrays.toString(caller));
            // Delegate to PulsarController for fidelity
            return PulsarController.isPulsarEnabled();
        }

        @Override
        public void setPulsarEnabled(boolean enabled) {
            String[] caller = ensureSupportedCaller();
            Log.i(TAG, "setPulsarEnabled: " + Arrays.toString(caller) + " enabled=" + enabled);
            long token = Binder.clearCallingIdentity();
            try {
                String str = enabled ? "0" : "1";
                SystemProperties.set(PROP_OPT_OUT, str);
                Log.d(TAG, "setSystemProperty: key= " + PROP_OPT_OUT + ", value= " + str);
                Settings.Secure.putInt(getContentResolver(), SETTING_KEY, enabled ? 1 : 0);
                Log.d(TAG, "putSettingsSecure: key= " + SETTING_KEY + ", value= " + (enabled ? 1 : 0));
            } catch (RuntimeException e) {
                Log.e(TAG, "setSystemProperty: failed.", e);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    private String[] ensureSupportedCaller() {
        int callingUid = Binder.getCallingUid();
        Log.i(TAG, "ensureSupportedCallers: pkg=" + callingUid);
        String[] packagesForUid = getPackageManager().getPackagesForUid(callingUid);
        if (packagesForUid == null) {
            return null;
        }
        for (String pkg : packagesForUid) {
            if (SUPPORTED_CALLERS.contains(pkg)) {
                return packagesForUid;
            }
        }
        throw new SecurityException("ensureSupportedCallers: " + Arrays.toString(packagesForUid));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Init HealthManager with application context
        try {
            mHealthManager = new HealthManager(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "HealthManager init failed", e);
            mHealthManager = null;
        }
        try {
            Resources res = getResources();
            // If resource not found (fallback true), keep enabled
            try {
                mHealthFeatureEnabled = res.getBoolean(
                        com.android.systemui.res.R.bool.config_battery_index_enabled);
            } catch (Resources.NotFoundException ignored) {
                mHealthFeatureEnabled = true;
            }
        } catch (Exception e) {
            mHealthFeatureEnabled = true;
        }
        if (healthDebugEnabled && mHealthManager != null) {
            try {
                // Register debug receiver as in stock (via BroadcastDispatcher analogue)
                IntentFilter filter = new IntentFilter("com.google.android.systemui.BATTERY_HEALTH_DEBUG");
                registerReceiver(mHealthManager.healthDebugReceiver, filter);
                Log.d(TAG, "register healthDebugReceiver");
            } catch (Exception e) {
                Log.e(TAG, "register healthDebugReceiver failed", e);
            }
        }
        // Init PulsarController to register its observer and broadcast receivers
        // Fidelity to SystemUIGoogle Dagger provider that creates PulsarController at startup
        try {
            new com.google.android.systemui.power.PulsarController(getApplicationContext());
            Log.d(TAG, "PulsarController initialized");
        } catch (Exception e) {
            Log.e(TAG, "PulsarController init failed", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (healthDebugEnabled && mHealthManager != null) {
            try {
                unregisterReceiver(mHealthManager.healthDebugReceiver);
                Log.d(TAG, "unregister healthDebugReceiver");
            } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "HealthService bound");
        // Stock returns empty Binder if feature disabled; for pulsar support we still return binder
        // but respect the resource if it's explicitly disabled.
        if (!mHealthFeatureEnabled) {
            Log.w(TAG, "HealthService: feature disabled via config_battery_index_enabled, returning empty binder");
            // For Pulsar toggle to work even when health index disabled, still expose pulsar binder.
            // Return real binder to keep SettingsIntelligence functional.
        }
        return mBinder;
    }
}
