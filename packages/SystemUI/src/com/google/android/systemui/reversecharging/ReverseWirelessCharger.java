package com.google.android.systemui.reversecharging;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import vendor.google.wireless_charger.IWirelessCharger;
import vendor.google.wireless_charger.IWirelessChargerRtxStatusCallback;
import vendor.google.wireless_charger.RtxStatusInfo;

public class ReverseWirelessCharger extends IWirelessChargerRtxStatusCallback.Stub implements IBinder.DeathRecipient {
    public static final boolean DEBUG = Log.isLoggable("ReverseWirelessCharger", 3);
    private final Context mContext;
    private final Object mLock = new Object();
    private final ArrayList<ReverseChargingController.RtxStatusCallback> mRtxStatusCallbacks = new ArrayList<>();
    private IWirelessCharger mWirelessCharger;

    public ReverseWirelessCharger(Context context) {
        this.mContext = context;
    }

    @Override
    public final int getInterfaceVersion() {
        return 7;
    }

    @Override
    public final String getInterfaceHash() {
        return "6d608c411c1bed1f68aa1d89a77f8ec0b275375e";
    }

    @Override
    public void binderDied() {
        Log.i("ReverseWirelessCharger", "serviceDied");
        this.mWirelessCharger = null;
    }

    @Override
    protected void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] args) {
        printWriter.printf("rtx callback in [%d]%s\n", Integer.valueOf(Process.myPid()), this.mContext.getPackageName());
    }

    public boolean initHALInterface() {
        if (this.mWirelessCharger != null) {
            return true;
        }
        IBinder service = ServiceManager.getService("vendor.google.wireless_charger.IWirelessCharger/default");
        if (service != null) {
            this.mWirelessCharger = IWirelessCharger.Stub.asInterface(service);
            try {
                service.linkToDeath(this, 0);
                Log.i("ReverseWirelessCharger", "mWirelessCharger service connected!!!!");
            } catch (RemoteException unused) {
                Log.w("ReverseWirelessCharger", "Can't link death recipient to HAL");
                this.mWirelessCharger = null;
            }
        }
        if (this.mWirelessCharger == null) {
            return false;
        }
        try {
            this.mWirelessCharger.registerRtxCallback(this);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == 5) {
                Log.d("ReverseWirelessCharger", "RtxCallback is already registered...");
            } else {
                Log.w("ReverseWirelessCharger", "RtxCallback registration error: " + e.errorCode);
            }
        } catch (Exception e2) {
            Log.w("ReverseWirelessCharger", "registerRtxCallback fail: ", e2);
        }
        return this.mWirelessCharger != null;
    }

    public void addRtxStatusCallback(ReverseChargingController.RtxStatusCallback callback) {
        synchronized (this.mLock) {
            this.mRtxStatusCallbacks.add(callback);
        }
    }

    public void setRtxMode(boolean enable) {
        if (initHALInterface()) {
            try {
                this.mWirelessCharger.setRtxMode(enable);
            } catch (Exception e) {
                Log.w("ReverseWirelessCharger", "setRtxMode fail: ", e);
            }
        }
    }

    public boolean isRtxSupported() {
        if (!initHALInterface()) {
            return false;
        }
        try {
            return this.mWirelessCharger.isRtxSupported();
        } catch (Exception e) {
            Log.w("ReverseWirelessCharger", "isRtxSupported fail: ", e);
            return false;
        }
    }

    public boolean isRtxModeOn() {
        if (!initHALInterface()) {
            return false;
        }
        try {
            return this.mWirelessCharger.isRtxModeOn();
        } catch (Exception e) {
            Log.w("ReverseWirelessCharger", "isRtxModeOn fail: ", e);
            return false;
        }
    }

    @Override
    public void rtxStatusInfoChanged(RtxStatusInfo rtxStatusInfo) {
        if (DEBUG) {
            Log.d("ReverseWirelessCharger", "onRtxStatusChanged() RtxStatusInfo : " + rtxStatusInfo);
        }
        Bundle bundle = new Bundle();
        bundle.putInt("key_rtx_mode", rtxStatusInfo.mode);
        bundle.putInt("key_accessory_type", rtxStatusInfo.acctype);
        bundle.putBoolean("key_rtx_connection", rtxStatusInfo.chgConnected);
        bundle.putInt("key_rtx_iout", rtxStatusInfo.iout);
        bundle.putInt("key_rtx_vout", rtxStatusInfo.vout);
        bundle.putInt("key_rtx_level", rtxStatusInfo.level);
        bundle.putInt("key_reason_type", rtxStatusInfo.reason);

        ArrayList<ReverseChargingController.RtxStatusCallback> callbacks;
        synchronized (this.mLock) {
            callbacks = new ArrayList<>(this.mRtxStatusCallbacks);
        }
        for (ReverseChargingController.RtxStatusCallback cb : callbacks) {
            cb.onRtxStatusChanged(bundle);
        }
    }
}
