package com.google.android.systemui.power.batteryevent.common.data;

/** A single battery-event datum with an optional change flag. */
public final class EventData {
    public boolean isChanged;
    public final Object value;

    public EventData(Object value) {
        this.value = value;
        this.isChanged = true;
    }
}
