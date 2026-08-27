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
package com.google.android.systemui.power.batteryhealth;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

public final class IncompatibleChargerData implements Parcelable {
    public static final Parcelable.Creator<IncompatibleChargerData> CREATOR =
            new Parcelable.Creator<IncompatibleChargerData>() {
                @Override
                public IncompatibleChargerData createFromParcel(Parcel parcel) {
                    return new IncompatibleChargerData(
                            parcel.readLong(),
                            parcel.readLong(),
                            parcel.readBoolean());
                }

                @Override
                public IncompatibleChargerData[] newArray(int size) {
                    return new IncompatibleChargerData[size];
                }
            };

    public final boolean isIncompatibleCharger;
    public final long lastCompatibleChargerTime;
    public final long lastIncompatibleChargerTime;

    public IncompatibleChargerData(
            long lastCompatibleChargerTime,
            long lastIncompatibleChargerTime,
            boolean isIncompatibleCharger) {
        this.isIncompatibleCharger = isIncompatibleCharger;
        this.lastCompatibleChargerTime = lastCompatibleChargerTime;
        this.lastIncompatibleChargerTime = lastIncompatibleChargerTime;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof IncompatibleChargerData)) {
            return false;
        }
        IncompatibleChargerData other = (IncompatibleChargerData) obj;
        return this.isIncompatibleCharger == other.isIncompatibleCharger
                && this.lastCompatibleChargerTime == other.lastCompatibleChargerTime
                && this.lastIncompatibleChargerTime == other.lastIncompatibleChargerTime;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                isIncompatibleCharger, lastCompatibleChargerTime, lastIncompatibleChargerTime);
    }

    @Override
    public String toString() {
        return "isIncompatibleCharger: "
                + isIncompatibleCharger
                + ", lastCompatibleChargerTime: "
                + lastCompatibleChargerTime
                + ", lastIncompatibleChargerTime: "
                + lastIncompatibleChargerTime;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeBoolean(this.isIncompatibleCharger);
        parcel.writeLong(this.lastCompatibleChargerTime);
        parcel.writeLong(this.lastIncompatibleChargerTime);
    }
}
