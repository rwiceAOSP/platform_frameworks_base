package com.google.android.systemui.power.batteryevent.common.module;

import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.common.data.SystemEventData;

/** Base class for a battery-event module that validates whether its event type is active. */
public abstract class BaseBatteryEventModule {
    public boolean lastValidation;

    public abstract BatteryEventType getModuleType();

    public abstract boolean validate(SystemEventData systemEventData);
}
