package vendor.google.wireless_charger;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IWirelessChargerRtxStatusCallback extends IInterface {
    public static final String DESCRIPTOR = "vendor$google$wireless_charger$IWirelessChargerRtxStatusCallback".replace('$', '.');

    String getInterfaceHash();

    int getInterfaceVersion();

    void rtxStatusInfoChanged(RtxStatusInfo rtxStatusInfo);

    public abstract class Stub extends Binder implements IWirelessChargerRtxStatusCallback {
        @Override
        public IBinder asBinder() {
            return this;
        }

        public Stub() {
            markVintfStability();
            attachInterface(this, IWirelessChargerRtxStatusCallback.DESCRIPTOR);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= 1 && code <= 16777215) {
                data.enforceInterface(IWirelessChargerRtxStatusCallback.DESCRIPTOR);
            }
            if (code == 16777215) {
                reply.writeNoException();
                reply.writeInt(getInterfaceVersion());
                return true;
            }
            if (code == 16777214) {
                reply.writeNoException();
                reply.writeString(getInterfaceHash());
                return true;
            }
            if (code == 1) {
                RtxStatusInfo rtxStatusInfo = (RtxStatusInfo) data.readTypedObject(RtxStatusInfo.CREATOR);
                data.enforceNoDataAvail();
                rtxStatusInfoChanged(rtxStatusInfo);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
