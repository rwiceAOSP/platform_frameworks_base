package com.google.android.systemui.power.batteryevent.common.module;

import com.android.settingslib.fuelgauge.BatteryStatus;

import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.common.data.EventData;
import com.google.android.systemui.power.batteryevent.common.data.SystemEventData;

/** Fires {@link BatteryEventType#EXTREME_LOW_BATTERY} when not plugged in and 0-3% remains. */
public final class ExtremeLowBatteryEventModule extends BaseBatteryEventModule {
    @Override
    public BatteryEventType getModuleType() {
        return BatteryEventType.EXTREME_LOW_BATTERY;
    }

    @Override
    public boolean validate(SystemEventData systemEventData) {
        EventData batteryLevel = systemEventData.batteryLevel;
        EventData batteryScale = systemEventData.batteryScale;
        EventData plugged = systemEventData.plugged;
        if (batteryLevel.isChanged || plugged.isChanged || batteryScale.isChanged) {
            boolean isExtremeLevel =
                    BatteryStatus.getBatteryLevel(
                                    ((Number) batteryLevel.value).intValue(),
                                    ((Number) batteryScale.value).intValue())
                            <= 3;
            boolean isPluggedIn = BatteryStatus.isPluggedIn(((Number) plugged.value).intValue());
            lastValidation = isExtremeLevel && !isPluggedIn;
        }
        return lastValidation;
    }
}
