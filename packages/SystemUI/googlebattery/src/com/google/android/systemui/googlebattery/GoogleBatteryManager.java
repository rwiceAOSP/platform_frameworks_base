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
package com.google.android.systemui.googlebattery;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.NoSuchElementException;

import vendor.google.google_battery.IGoogleBattery;

public abstract class GoogleBatteryManager {
    public static final boolean DEBUG = Log.isLoggable("GoogleBatteryManager", 3);

    public static IGoogleBattery initHalInterface(IBinder.DeathRecipient deathRecipient) {
        if (DEBUG) {
            Log.d("GoogleBatteryManager", "initHalInterface");
        }
        try {
            IBinder iBinderAllowBlocking =
                    Binder.allowBlocking(
                            ServiceManager.waitForDeclaredService(
                                    "vendor.google.google_battery.IGoogleBattery/default"));
            if (iBinderAllowBlocking == null) {
                return null;
            }
            IGoogleBattery iGoogleBatteryAsInterface =
                    IGoogleBattery.Stub.asInterface(iBinderAllowBlocking);
            if (iGoogleBatteryAsInterface == null || deathRecipient == null) {
                return iGoogleBatteryAsInterface;
            }
            iBinderAllowBlocking.linkToDeath(deathRecipient, 0);
            return iGoogleBatteryAsInterface;
        } catch (RemoteException | SecurityException | NoSuchElementException e) {
            Log.e("GoogleBatteryManager", "failed to get Google Battery HAL: ", e);
            return null;
        }
    }

    public static void destroyHalInterface(
            IGoogleBattery iGoogleBattery, IBinder.DeathRecipient deathRecipient) {
        if (DEBUG) {
            Log.d("GoogleBatteryManager", "destroyHalInterface");
        }
        if (deathRecipient == null || iGoogleBattery == null) {
            return;
        }
        iGoogleBattery.asBinder().unlinkToDeath(deathRecipient, 0);
    }
}
