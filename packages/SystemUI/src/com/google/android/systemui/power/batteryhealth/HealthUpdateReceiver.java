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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receiver triggered by AlarmManager / BOOT_COMPLETED to refresh battery health metrics.
 * Fidelity to SystemUIGoogle HealthUpdateReceiver.
 */
public final class HealthUpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "HealthUpdateReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Start new BHI update: " + intent);
        try {
            HealthManager manager = new HealthManager(context.getApplicationContext());
            // Run on background thread as stock does with coroutines
            new Thread(() -> {
                try {
                    manager.getAndUpdateHealthData();
                } catch (Exception e) {
                    Log.e(TAG, "Health update failed", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "onReceive failed", e);
        }
    }
}
