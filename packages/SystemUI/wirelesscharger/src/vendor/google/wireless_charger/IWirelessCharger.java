package vendor.google.wireless_charger;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IWirelessCharger extends IInterface {
    String DESCRIPTOR = "vendor$google$wireless_charger$IWirelessCharger".replace('$', '.');

    RtxStatusInfo getRtxInformation() throws RemoteException;

    boolean isRtxModeOn() throws RemoteException;

    boolean isRtxSupported() throws RemoteException;

    void registerRtxCallback(IWirelessChargerRtxStatusCallback callback) throws RemoteException;

    void setRtxMode(boolean enable) throws RemoteException;

    abstract class Stub extends Binder implements IWirelessCharger {
        public static IWirelessCharger asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface localInterface = binder.queryLocalInterface(IWirelessCharger.DESCRIPTOR);
            if (localInterface instanceof IWirelessCharger) {
                return (IWirelessCharger) localInterface;
            }
            return new Proxy(binder);
        }

        public static final class Proxy implements IWirelessCharger {
            private final IBinder mRemote;

            public Proxy(IBinder remote) {
                this.mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return this.mRemote;
            }

            @Override
            public RtxStatusInfo getRtxInformation() throws RemoteException {
                Parcel data = Parcel.obtain(asBinder());
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(IWirelessCharger.DESCRIPTOR);
                    if (!this.mRemote.transact(7, data, reply, 0)) {
                        throw new RemoteException("Unimplemented");
                    }
                    reply.readException();
                    return reply.readTypedObject(RtxStatusInfo.CREATOR);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean isRtxModeOn() throws RemoteException {
                Parcel data = Parcel.obtain(asBinder());
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(IWirelessCharger.DESCRIPTOR);
                    if (!this.mRemote.transact(12, data, reply, 0)) {
                        throw new RemoteException("Unimplemented");
                    }
                    reply.readException();
                    return reply.readBoolean();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean isRtxSupported() throws RemoteException {
                Parcel data = Parcel.obtain(asBinder());
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(IWirelessCharger.DESCRIPTOR);
                    if (!this.mRemote.transact(13, data, reply, 0)) {
                        throw new RemoteException("Unimplemented");
                    }
                    reply.readException();
                    return reply.readBoolean();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void registerRtxCallback(IWirelessChargerRtxStatusCallback callback) throws RemoteException {
                Parcel data = Parcel.obtain(asBinder());
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(IWirelessCharger.DESCRIPTOR);
                    data.writeStrongInterface(callback);
                    if (!this.mRemote.transact(16, data, reply, 0)) {
                        throw new RemoteException("Unimplemented");
                    }
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void setRtxMode(boolean enable) throws RemoteException {
                Parcel data = Parcel.obtain(asBinder());
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(IWirelessCharger.DESCRIPTOR);
                    data.writeBoolean(enable);
                    if (!this.mRemote.transact(20, data, reply, 0)) {
                        throw new RemoteException("Unimplemented");
                    }
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
