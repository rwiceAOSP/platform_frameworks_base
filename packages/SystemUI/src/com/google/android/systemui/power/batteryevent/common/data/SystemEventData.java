package com.google.android.systemui.power.batteryevent.common.data;

/**
 * System-sourced battery event data needed to evaluate the tiered low / severe / extreme battery
 * trigger points. Field order matches the original (intentAction, plugged, batteryScale,
 * batteryLevel).
 */
public final class SystemEventData {
    public final String intentAction;
    public final EventData plugged;
    public final EventData batteryScale;
    public final EventData batteryLevel;

    public SystemEventData(
            String intentAction,
            EventData plugged,
            EventData batteryScale,
            EventData batteryLevel) {
        this.intentAction = intentAction;
        this.plugged = plugged;
        this.batteryScale = batteryScale;
        this.batteryLevel = batteryLevel;
    }
}
