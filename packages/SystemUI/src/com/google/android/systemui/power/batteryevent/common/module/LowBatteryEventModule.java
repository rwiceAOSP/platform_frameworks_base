package com.google.android.systemui.power.batteryevent.common.module;

import com.android.settingslib.fuelgauge.BatteryStatus;

import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.common.data.EventData;
import com.google.android.systemui.power.batteryevent.common.data.SystemEventData;

/** Fires {@link BatteryEventType#LOW_BATTERY} when not plugged in and 11-20% battery remains. */
public final class LowBatteryEventModule extends BaseBatteryEventModule {
    @Override
    public BatteryEventType getModuleType() {
        return BatteryEventType.LOW_BATTERY;
    }

    @Override
    public boolean validate(SystemEventData systemEventData) {
        EventData batteryLevel = systemEventData.batteryLevel;
        EventData batteryScale = systemEventData.batteryScale;
        EventData plugged = systemEventData.plugged;
        if (batteryLevel.isChanged || batteryScale.isChanged || plugged.isChanged) {
            int level =
                    BatteryStatus.getBatteryLevel(
                            ((Number) batteryLevel.value).intValue(),
                            ((Number) batteryScale.value).intValue());
            lastValidation =
                    level <= 20
                            && !BatteryStatus.isPluggedIn(((Number) plugged.value).intValue())
                            && level > 10
                            && level > 3;
        }
        return lastValidation;
    }
}
