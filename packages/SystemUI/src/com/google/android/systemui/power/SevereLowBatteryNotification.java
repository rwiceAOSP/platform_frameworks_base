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

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;

public final class SevereLowBatteryNotification {
    private static final String TAG = "SevereLowBatteryNotification";

    public final Context context;
    public final UiEventLogger uiEventLogger;
    public final KeyguardManager keyguardManager;
    private NotificationManager mNotificationManager;

    public SevereLowBatteryNotification(
            Context context,
            UiEventLogger uiEventLogger,
            KeyguardManager keyguardManager) {
        this.context = context;
        this.uiEventLogger = uiEventLogger;
        this.keyguardManager = keyguardManager;
    }

    public NotificationManager getNotificationManager() {
        if (mNotificationManager == null) {
            mNotificationManager = context.getSystemService(NotificationManager.class);
        }
        return mNotificationManager;
    }

    public void cancel() {
        Log.d(TAG, "cancel()");
        getNotificationManager().cancelAsUser("low_battery", 3, android.os.UserHandle.ALL);
    }

    public void logEvent(BatteryMetricEvent batteryMetricEvent) {
        if (uiEventLogger != null) {
            uiEventLogger.log(batteryMetricEvent);
            Log.d(TAG, "logEvent " + batteryMetricEvent);
        }
    }
}
