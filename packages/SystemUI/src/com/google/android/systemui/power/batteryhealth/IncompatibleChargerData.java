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
 * Parcelable data class representing charger compatibility data.
 */
public final class IncompatibleChargerData implements Parcelable {

    public static final Parcelable.Creator<IncompatibleChargerData> CREATOR =
            new Parcelable.Creator<IncompatibleChargerData>() {
                @Override
                public IncompatibleChargerData createFromParcel(Parcel in) {
                    return new IncompatibleChargerData(in);
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
        this.lastCompatibleChargerTime = lastCompatibleChargerTime;
        this.lastIncompatibleChargerTime = lastIncompatibleChargerTime;
        this.isIncompatibleCharger = isIncompatibleCharger;
    }

    private IncompatibleChargerData(Parcel in) {
        // Order matches stock SystemUIGoogle: boolean first, then two longs.
        // CREATOR reads bool, long, long and constructs as (long, long, boolean).
        this.isIncompatibleCharger = in.readBoolean();
        this.lastCompatibleChargerTime = in.readLong();
        this.lastIncompatibleChargerTime = in.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // Stock writes boolean first, then longs.
        dest.writeBoolean(this.isIncompatibleCharger);
        dest.writeLong(this.lastCompatibleChargerTime);
        dest.writeLong(this.lastIncompatibleChargerTime);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IncompatibleChargerData)) return false;
        IncompatibleChargerData that = (IncompatibleChargerData) o;
        return this.isIncompatibleCharger == that.isIncompatibleCharger
                && this.lastCompatibleChargerTime == that.lastCompatibleChargerTime
                && this.lastIncompatibleChargerTime == that.lastIncompatibleChargerTime;
    }

    @Override
    public int hashCode() {
        return Objects.hash(isIncompatibleCharger, lastCompatibleChargerTime, lastIncompatibleChargerTime);
    }

    @Override
    public String toString() {
        return "isIncompatibleCharger: " + isIncompatibleCharger
                + ", lastCompatibleChargerTime: " + lastCompatibleChargerTime
                + ", lastIncompatibleChargerTime: " + lastIncompatibleChargerTime;
    }
}
