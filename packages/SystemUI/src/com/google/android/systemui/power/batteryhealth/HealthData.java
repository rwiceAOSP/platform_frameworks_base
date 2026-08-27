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

public final class HealthData implements Parcelable {
    public static final Parcelable.Creator<HealthData> CREATOR =
            new Parcelable.Creator<HealthData>() {
                @Override
                public HealthData createFromParcel(Parcel parcel) {
                    return new HealthData(
                            parcel.readInt(),
                            parcel.readInt(),
                            parcel.readInt(),
                            parcel.readInt());
                }

                @Override
                public HealthData[] newArray(int size) {
                    return new HealthData[size];
                }
            };

    public final int healthIndex;
    public final int performanceIndex;
    public final int capacityIndex;
    public final int healthStatus;

    public HealthData(
            int healthIndex, int performanceIndex, int capacityIndex, int healthStatus) {
        this.healthIndex = healthIndex;
        this.performanceIndex = performanceIndex;
        this.capacityIndex = capacityIndex;
        this.healthStatus = healthStatus;
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
        if (!(obj instanceof HealthData)) {
            return false;
        }
        HealthData other = (HealthData) obj;
        return this.healthIndex == other.healthIndex
                && this.performanceIndex == other.performanceIndex
                && this.capacityIndex == other.capacityIndex
                && this.healthStatus == other.healthStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(healthIndex, performanceIndex, capacityIndex, healthStatus);
    }

    @Override
    public String toString() {
        return "hi: "
                + healthIndex
                + ", pi: "
                + performanceIndex
                + ", ci: "
                + capacityIndex
                + ", hs: "
                + healthStatus;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(this.healthIndex);
        parcel.writeInt(this.performanceIndex);
        parcel.writeInt(this.capacityIndex);
        parcel.writeInt(this.healthStatus);
    }
}
