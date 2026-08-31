package com.google.android.systemui.power.batteryevent.common.module;

import com.android.settingslib.fuelgauge.BatteryStatus;

import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.common.data.EventData;
import com.google.android.systemui.power.batteryevent.common.data.SystemEventData;

/** Fires {@link BatteryEventType#SEVERE_LOW_BATTERY} when not plugged in and 4-10% remains. */
public final class SevereLowBatteryEventModule extends BaseBatteryEventModule {
    @Override
    public BatteryEventType getModuleType() {
        return BatteryEventType.SEVERE_LOW_BATTERY;
    }

    @Override
    public boolean validate(SystemEventData systemEventData) {
        EventData plugged = systemEventData.plugged;
        EventData batteryLevel = systemEventData.batteryLevel;
        EventData batteryScale = systemEventData.batteryScale;
        if (plugged.isChanged || batteryLevel.isChanged || batteryScale.isChanged) {
            int level =
                    BatteryStatus.getBatteryLevel(
                            ((Number) batteryLevel.value).intValue(),
                            ((Number) batteryScale.value).intValue());
            lastValidation =
                    level <= 10
                            && level > 3
                            && !BatteryStatus.isPluggedIn(((Number) plugged.value).intValue());
        }
        return lastValidation;
    }
}
