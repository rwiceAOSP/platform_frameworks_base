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
package com.google.android.systemui.power.batteryevent.aidl;

import android.os.Parcel;
import android.os.Parcelable;

public enum BatteryEventType implements Parcelable {
    UNKNOWN("unknown"),
    FULL_CHARGED("full_charged"),
    LOW_BATTERY("low_battery"),
    EXTREME_LOW_BATTERY("extreme_low_battery"),
    SEVERE_LOW_BATTERY("severe_low_battery"),
    REGULAR_CHARGING("regular_charging"),
    FAST_CHARGING("fast_charging"),
    SLOW_CHARGING("slow_charging"),
    NOT_CHARGING("not_charging"),
    TEMP_DEFEND_BATTERY("temp_defend_battery"),
    DWELL_DEFEND_BATTERY("dwell_defend_battery"),
    DOCK_DEFEND_BATTERY("dock_defend_battery"),
    WIRED_INCOMPATIBLE_CHARGING("wired_incompatible_charging"),
    CHARGING_LIMIT("charging_limit"),
    SCREEN_ON("screen_on"),
    AIRPLANE_ON("airplane_on"),
    DND_ON("dnd_on");

    public static final Parcelable.Creator<BatteryEventType> CREATOR =
            new Parcelable.Creator<BatteryEventType>() {
                @Override
                public BatteryEventType createFromParcel(Parcel parcel) {
                    if (parcel == null) {
                        return BatteryEventType.UNKNOWN;
                    }
                    String typeName = parcel.readString();
                    if (typeName == null) {
                        return BatteryEventType.UNKNOWN;
                    }
                    for (BatteryEventType type : BatteryEventType.values()) {
                        if (type.getTypeName().equals(typeName)) {
                            return type;
                        }
                    }
                    return BatteryEventType.UNKNOWN;
                }

                @Override
                public BatteryEventType[] newArray(int size) {
                    return new BatteryEventType[size];
                }
            };

    private final String mTypeName;

    BatteryEventType(String typeName) {
        mTypeName = typeName;
    }

    public String getTypeName() {
        return mTypeName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mTypeName);
    }
}
