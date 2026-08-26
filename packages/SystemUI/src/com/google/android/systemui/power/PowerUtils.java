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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.UserHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.res.R;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public abstract class PowerUtils {
    public static final List<String> NON_EU_COUNTRY_CODES = Arrays.asList("us", "in", "sg", "my");

    public static PendingIntent createBatterySettingsPendingIntentAsUser(Context context) {
        return PendingIntent.getActivityAsUser(
                context, 0, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
    }

    public static PendingIntent createHelpArticlePendingIntentAsUser(int urlResId, Context context) {
        return PendingIntent.getActivityAsUser(
                context, 0, new Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(urlResId))),
                PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
    }

    public static PendingIntent createPendingIntent(Context context, String action, Bundle bundle) {
        Intent intent = new Intent(action).setPackage(context.getPackageName()).setFlags(
                Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        if (bundle != null) {
            intent.putExtras(bundle);
        }
        return PendingIntent.getBroadcastAsUser(
                context, 0, intent,
                bundle != null ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE)
                               : PendingIntent.FLAG_IMMUTABLE,
                UserHandle.CURRENT);
    }

    public static Locale getLocale(Context context) {
        LocaleList locales = context.getResources().getConfiguration().getLocales();
        return (locales == null || locales.isEmpty()) ? Locale.getDefault() : locales.get(0);
    }

    public static boolean isChargeLimitEnabledForUser(SecureSettings secureSettings, int userId) {
        return secureSettings.getIntForUser("charge_optimization_mode", 0, userId) == 1;
    }

    public static boolean isFlipendoEnabled(ContentResolver contentResolver) {
        try {
            Bundle result = contentResolver.call("com.google.android.flipendo.api", "get_flipendo_state", null, Bundle.EMPTY);
            return result != null && result.getBoolean("flipendo_state", false);
        } catch (Exception e) {
            Log.e("PowerUtils", "isFlipendoEnabled() failed", e);
            return false;
        }
    }

    public static void applyExtremeSaverMode(Context context) {
        try {
            Bundle bundle = new Bundle(1);
            bundle.putInt("update_flipendo_mode", 1);
            context.getContentResolver().call("com.google.android.flipendo.api",
                    "update_flipendo_mode_method", null, bundle);
        } catch (Exception e) {
            Log.e("PowerUtils", "applyExtremeSaverMode() failed", e);
        }
    }

    public static boolean isSimInEuCountry(SubscriptionManager subscriptionManager) {
        if (subscriptionManager == null) {
            return false;
        }
        List<SubscriptionInfo> activeSubscriptionInfoList =
                subscriptionManager.getActiveSubscriptionInfoList();
        if (activeSubscriptionInfoList == null || activeSubscriptionInfoList.isEmpty()) {
            return true;
        }
        for (SubscriptionInfo info : activeSubscriptionInfoList) {
            String countryIso = info.getCountryIso();
            if (!TextUtils.isEmpty(countryIso)
                    && !NON_EU_COUNTRY_CODES.contains(countryIso.toLowerCase(Locale.ENGLISH))) {
                return true;
            }
        }
        return false;
    }

    public static void overrideNotificationAppName(Context context, NotificationCompat.Builder builder) {
        Bundle bundle = new Bundle(1);
        bundle.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                context.getString(com.android.internal.R.string.android_system_label));
        builder.addExtras(bundle);
    }
}
