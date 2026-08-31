package com.google.android.systemui.power;

import android.app.NotificationManager;
import android.content.Context;

import com.android.internal.logging.UiEventLogger;

public final class ExtremeLowBatteryNotification {
    public Context mContext;
    NotificationManager mNotificationManager;
    public UiEventLogger mUiEventLogger;
}
