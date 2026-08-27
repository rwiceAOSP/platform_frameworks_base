package vendor.google.wireless_charger;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;

public class RtxStatusInfo implements Parcelable {
    public static final Parcelable.Creator<RtxStatusInfo> CREATOR = new Parcelable.Creator<RtxStatusInfo>() {
        @Override
        public RtxStatusInfo createFromParcel(Parcel parcel) {
            RtxStatusInfo rtxStatusInfo = new RtxStatusInfo();
            rtxStatusInfo.readFromParcel(parcel);
            return rtxStatusInfo;
        }

        @Override
        public RtxStatusInfo[] newArray(int size) {
            return new RtxStatusInfo[size];
        }
    };

    public byte mode = 0;
    public int acctype = 0;
    public boolean chgConnected = false;
    public int vout = 0;
    public int iout = 0;
    public int level = 0;
    public byte reason = 15;

    @Override
    public int describeContents() {
        return 0;
    }

    public final int getStability() {
        return 1;
    }

    @Override
    public final void writeToParcel(Parcel parcel, int flags) {
        int startPos = parcel.dataPosition();
        parcel.writeInt(0);
        parcel.writeByte(this.mode);
        parcel.writeInt(this.acctype);
        parcel.writeBoolean(this.chgConnected);
        parcel.writeInt(this.vout);
        parcel.writeInt(this.iout);
        parcel.writeInt(this.level);
        parcel.writeByte(this.reason);
        int endPos = parcel.dataPosition();
        parcel.setDataPosition(startPos);
        parcel.writeInt(endPos - startPos);
        parcel.setDataPosition(endPos);
    }

    public final void readFromParcel(Parcel parcel) {
        int startPos = parcel.dataPosition();
        int size = parcel.readInt();
        try {
            if (size < 4) {
                throw new BadParcelableException("Parcelable too small");
            }
            if (parcel.dataPosition() - startPos < size) {
                this.mode = parcel.readByte();
                if (parcel.dataPosition() - startPos < size) {
                    this.acctype = parcel.readInt();
                    if (parcel.dataPosition() - startPos < size) {
                        this.chgConnected = parcel.readBoolean();
                        if (parcel.dataPosition() - startPos < size) {
                            this.vout = parcel.readInt();
                            if (parcel.dataPosition() - startPos < size) {
                                this.iout = parcel.readInt();
                                if (parcel.dataPosition() - startPos < size) {
                                    this.level = parcel.readInt();
                                    if (parcel.dataPosition() - startPos < size) {
                                        this.reason = parcel.readByte();
                                    }
                                }
                            }
                        }
                    }
                }
            }
            parcel.setDataPosition(startPos + size);
        } catch (Throwable th) {
            if (startPos > Integer.MAX_VALUE - size) {
                throw new BadParcelableException("Overflow in the size of parcelable");
            }
            parcel.setDataPosition(startPos + size);
            throw th;
        }
    }

    @Override
    public String toString() {
        return "RtxStatusInfo{mode=" + mode + ", acctype=" + acctype + ", chgConnected=" + chgConnected
                + ", vout=" + vout + ", iout=" + iout + ", level=" + level + ", reason=" + reason + "}";
    }
}
