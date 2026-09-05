package com.google.android.systemui.power;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.systemui.res.R;

/** Shared helpers for Google SystemUI power notifications. */
public final class PowerUtils {

    public static final int PULSAR_ENABLED_NOTIFICATION_ID = R.string.pulsar_enabled_notification_title;
    public static final int PULSAR_REMINDER_NOTIFICATION_ID = R.string.pulsar_reminder_notification_title;

    private PowerUtils() {}

    /**
     * Creates a {@link PendingIntent} that broadcasts to this package (as the current user) with
     * the given action and optional extras.
     */
    public static PendingIntent createPendingIntent(Context context, String action, Bundle bundle) {
        Intent intent =
                new Intent(action)
                        .setPackage(context.getPackageName())
                        .setFlags(1342177280); // FLAG_RECEIVER_FOREGROUND | FLAG_IMMUTABLE
        if (bundle != null) {
            intent.putExtras(bundle);
        }
        return PendingIntent.getBroadcastAsUser(
                context, 0, intent, bundle != null ? 335544320 : 67108864, UserHandle.CURRENT);
    }

    /** Whether Flipendo (Extreme Battery Saver) is currently enabled. */
    public static boolean isFlipendoEnabled(ContentResolver contentResolver) {
        try {
            Bundle result =
                    contentResolver.call(
                            "com.google.android.flipendo.api",
                            "get_flipendo_state",
                            null,
                            Bundle.EMPTY);
            return result != null && result.getBoolean("flipendo_state", false);
        } catch (Exception e) {
            Log.e("PowerUtils", "isFlipendoEnabled() failed", e);
            return false;
        }
    }

    /** Whether Flipendo's aggressive (Extreme Battery Saver) mode is selected. */
    public static boolean isFlipendoSelected(ContentResolver contentResolver) {
        try {
            Bundle result =
                    contentResolver.call(
                            "com.google.android.flipendo.api",
                            "get_flipendo_state",
                            null,
                            Bundle.EMPTY);
            return result != null && result.getBoolean("is_flipendo_aggressive", false);
        } catch (Exception e) {
            Log.e("PowerUtils", "isFlipendoSelected() failed", e);
            return false;
        }
    }

    public static PendingIntent createHelpArticlePendingIntentAsUser(int resId, Context context) {
        return PendingIntent.getActivityAsUser(
                context,
                0,
                new Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(resId))),
                67108864, // FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT?
                null,
                UserHandle.CURRENT);
    }

    /** Overrides the app name shown for the notification. */
    public static void overrideNotificationAppName(
            Context context, NotificationCompat.Builder builder) {
        Bundle bundle = new Bundle(1);
        bundle.putString(
                "android.substName",
                context.getString(com.android.internal.R.string.android_system_label));
        builder.addExtras(bundle);
    }
}
