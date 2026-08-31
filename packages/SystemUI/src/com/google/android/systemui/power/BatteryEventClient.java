package com.google.android.systemui.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.android.settingslib.fuelgauge.BatteryStatus;

import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.aidl.SurfaceType;
import com.google.android.systemui.power.batteryevent.common.data.EventData;
import com.google.android.systemui.power.batteryevent.common.data.SystemEventData;
import com.google.android.systemui.power.batteryevent.common.module.BaseBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.ExtremeLowBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.LowBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.SevereLowBatteryEventModule;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Client for the battery event framework. Evaluates the tiered low / severe / extreme battery
 * trigger points from {@link Intent#ACTION_BATTERY_CHANGED} and dispatches the resulting {@link
 * BatteryEventType events} to the subscribed callback.
 *
 * <p>This is the in-process equivalent of the original AIDL-backed {@code BatteryEventClient}; the
 * separate binder service is collapsed here since the tiered notifications live in the same
 * process.
 */
public final class BatteryEventClient {

    /** Receives battery event updates. */
    @FunctionalInterface
    public interface BatteryEventCallback {
        void onBatteryEventUpdate(List<BatteryEventType> events, int batteryLevel);
    }

    private final Context mContext;

    private final List<BaseBatteryEventModule> mModules =
            List.of(
                    new LowBatteryEventModule(),
                    new SevereLowBatteryEventModule(),
                    new ExtremeLowBatteryEventModule());

    private List<BatteryEventType> mSubscribedEvents = List.of();
    private BatteryEventCallback mCallback;

    private final BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null
                            && Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                        BatteryEventClient.this.onBatteryChanged(intent);
                    }
                }
            };

    @Inject
    public BatteryEventClient(Context context) {
        mContext = context;
    }

    /**
     * Subscribes to the given {@code events} for the given {@code surfaceType} and dispatches
     * updates to {@code callback}.
     */
    public void registerBatteryEventCallback(
            SurfaceType surfaceType, List<BatteryEventType> events, BatteryEventCallback callback) {
        mSubscribedEvents = List.copyOf(events);
        mCallback = callback;

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = mContext.registerReceiver(mReceiver, filter);
        if (sticky != null) {
            onBatteryChanged(sticky);
        }
    }

    private void onBatteryChanged(Intent intent) {
        if (mCallback == null) {
            return;
        }

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        SystemEventData systemEventData =
                new SystemEventData(
                        Intent.ACTION_BATTERY_CHANGED,
                        new EventData(plugged),
                        new EventData(scale),
                        new EventData(level));

        List<BatteryEventType> triggeredEvents = new ArrayList<>();
        for (BaseBatteryEventModule module : mModules) {
            if (mSubscribedEvents.contains(module.getModuleType())
                    && module.validate(systemEventData)) {
                triggeredEvents.add(module.getModuleType());
            }
        }

        int batteryLevel = BatteryStatus.getBatteryLevel(level, scale);
        mCallback.onBatteryEventUpdate(triggeredEvents, batteryLevel);
    }
}
