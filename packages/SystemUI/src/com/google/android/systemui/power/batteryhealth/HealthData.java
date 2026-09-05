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

/**
 * Parcelable data class representing battery health metrics from the Battery Management Subsystem.
 */
public final class HealthData implements Parcelable {

    public static final Parcelable.Creator<HealthData> CREATOR =
            new Parcelable.Creator<HealthData>() {
                @Override
                public HealthData createFromParcel(Parcel in) {
                    return new HealthData(in);
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

    public HealthData(int healthIndex, int performanceIndex, int capacityIndex, int healthStatus) {
        this.healthIndex = healthIndex;
        this.performanceIndex = performanceIndex;
        this.capacityIndex = capacityIndex;
        this.healthStatus = healthStatus;
    }

    private HealthData(Parcel in) {
        this.healthIndex = in.readInt();
        this.performanceIndex = in.readInt();
        this.capacityIndex = in.readInt();
        this.healthStatus = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.healthIndex);
        dest.writeInt(this.performanceIndex);
        dest.writeInt(this.capacityIndex);
        dest.writeInt(this.healthStatus);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HealthData)) return false;
        HealthData that = (HealthData) o;
        return this.healthIndex == that.healthIndex
                && this.performanceIndex == that.performanceIndex
                && this.capacityIndex == that.capacityIndex
                && this.healthStatus == that.healthStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(healthIndex, performanceIndex, capacityIndex, healthStatus);
    }

    @Override
    public String toString() {
        return "hi: " + healthIndex
                + ", pi: " + performanceIndex
                + ", ci: " + capacityIndex
                + ", hs: " + healthStatus;
    }
}
