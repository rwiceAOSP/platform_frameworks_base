package com.google.android.systemui.reversecharging;

import android.frameworks.stats.IStats;
import android.frameworks.stats.VendorAtom;
import android.os.ServiceManager;
import android.util.Log;

import java.util.Optional;

public abstract class ReverseChargingMetrics {
    public static final boolean DEBUG = Log.isLoggable("ReverseChargingMetrics", 3);

    public static void reportVendorAtom(VendorAtom vendorAtom) {
        Optional<IStats> optionalOfNullable;
        try {
            String strM = IStats.DESCRIPTOR + "/default";
            if (ServiceManager.isDeclared(strM)) {
                optionalOfNullable = Optional.ofNullable(IStats.Stub.asInterface(ServiceManager.waitForDeclaredService(strM)));
            } else {
                Log.e("ReverseChargingMetrics", "IStats is not registered");
                optionalOfNullable = Optional.empty();
            }
            if (optionalOfNullable.isPresent()) {
                optionalOfNullable.get().reportVendorAtom(vendorAtom);
                if (DEBUG) {
                    Log.i("ReverseChargingMetrics", "Report vendor atom OK, " + vendorAtom);
                }
            }
        } catch (Exception e) {
            Log.e("ReverseChargingMetrics", "Failed to log atom to IStats service, " + e);
        }
    }
}
