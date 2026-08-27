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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.google.android.systemui.googlebattery.GoogleBatteryManager;

import vendor.google.google_battery.IGoogleBattery;

import javax.inject.Inject;

/**
 * Applies charging policies to the google battery HAL.
 *
 * Policy values (BatteryChargingPolicy):
 * 1 DEFAULT, 2 LONGLIFE ("Limit to 80%"), 3 ADAPTIVE.
 */
public class ChargeLimitController {
    private static final String TAG = "ChargeLimitController";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);

    private final Handler mBackgroundHandler;

    @Inject
    public ChargeLimitController() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mBackgroundHandler = new Handler(thread.getLooper());
    }

    /** Asynchronously pushes {@code policy} to the HAL ("withGoogleBattery" in stock). */
    public void setChargingPolicy(int policy) {
        IBinder.DeathRecipient deathRecipient =
                new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Log.e(TAG, "Service died!!");
                    }
                };
        mBackgroundHandler.post(() -> {
            IGoogleBattery hal = GoogleBatteryManager.initHalInterface(deathRecipient);
            if (hal == null) {
                Log.w(TAG, "withGoogleBattery: googleBattery is null");
                return;
            }
            try {
                hal.setChargingPolicy(policy);
                if (DEBUG) {
                    Log.d(TAG, "setChargingPolicy: " + policy);
                }
            } catch (ServiceSpecificException | RemoteException | IllegalArgumentException e) {
                Log.e(TAG, "withGoogleBattery: failed to run action", e);
            } finally {
                try {
                    GoogleBatteryManager.destroyHalInterface(hal, deathRecipient);
                } catch (Exception e) {
                    Log.w(TAG, "withGoogleBattery: destroyHalInterface failed: ", e);
                }
            }
        });
    }
}
