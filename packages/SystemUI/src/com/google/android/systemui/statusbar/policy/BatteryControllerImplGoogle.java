package com.google.android.systemui.statusbar.policy;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BatteryControllerLogger;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.util.settings.SecureSettings;
import com.google.android.systemui.power.PowerUtils;
import com.google.android.systemui.reversecharging.ReverseChargingController;

import java.io.PrintWriter;

public final class BatteryControllerImplGoogle extends BatteryControllerImpl {
    public static final boolean DEBUG = Log.isLoggable("BatteryControllerGoogle", 3);
    public static final Uri IS_EBS_ENABLED_OBSERVABLE_URI =
            new Uri.Builder().scheme("content").authority("com.google.android.flipendo.api").appendPath("get_flipendo_state").build();

    protected final ContentObserver mContentObserver;
    public final UserTracker mContentResolverProvider;
    public boolean mExtremeSaver;
    public String mName;
    public boolean mReverse;
    public final ReverseChargingController mReverseChargingController;
    public int mRtxLevel;
    public final SecureSettings mSecureSettings;
    public final UserTracker mUserTracker;

    public BatteryControllerImplGoogle(
            Context context,
            EnhancedEstimates enhancedEstimates,
            PowerManager powerManager,
            BroadcastDispatcher broadcastDispatcher,
            DemoModeController demoModeController,
            DumpManager dumpManager,
            BatteryControllerLogger batteryControllerLogger,
            Handler mainHandler,
            Handler bgHandler,
            UserTracker contentResolverProvider,
            ReverseChargingController reverseChargingController,
            SecureSettings secureSettings,
            UserTracker userTracker) {
        super(context, enhancedEstimates, powerManager, broadcastDispatcher, demoModeController, dumpManager, batteryControllerLogger, mainHandler, bgHandler);
        this.mReverseChargingController = reverseChargingController;
        this.mContentResolverProvider = contentResolverProvider;
        this.mSecureSettings = secureSettings;
        this.mUserTracker = userTracker;
        this.mContentObserver = new ContentObserver(bgHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (DEBUG) {
                    Log.d("BatteryControllerGoogle", "Change in EBS value " + (uri != null ? uri.toSafeString() : "null"));
                }
                boolean isFlipendo = PowerUtils.isFlipendoEnabled(mContentResolverProvider.getUserContext().getContentResolver());
                if (isFlipendo == mExtremeSaver) {
                    return;
                }
                mExtremeSaver = isFlipendo;
                dispatchSafeChange(callback -> callback.onExtremeBatterySaverChanged(mExtremeSaver));
            }
        };
    }

    @Override
    public void addCallback(BatteryController.BatteryStateChangeCallback cb) {
        super.addCallback(cb);
        cb.onReverseChanged(this.mRtxLevel, this.mName, this.mReverse);
        cb.onExtremeBatterySaverChanged(this.mExtremeSaver);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        super.dump(pw, args);
        pw.print("  mReverse=");
        pw.println(this.mReverse);
        pw.print("  mExtremeSaver=");
        pw.println(this.mExtremeSaver);
    }

    @Override
    public void init() {
        super.init();
        this.mReverse = false;
        this.mRtxLevel = -1;
        this.mName = null;
        this.mReverseChargingController.init(this);
        this.mReverseChargingController.addCallback(this);
        try {
            ContentResolver contentResolver = this.mContentResolverProvider.getUserContext().getContentResolver();
            contentResolver.registerContentObserver(IS_EBS_ENABLED_OBSERVABLE_URI, false, this.mContentObserver, -1);
            this.mContentObserver.onChange(false, IS_EBS_ENABLED_OBSERVABLE_URI);
        } catch (Exception e) {
            Log.w("BatteryControllerGoogle", "Couldn't register to observe provider", e);
        }
    }

    @Override
    public boolean isBatteryDefenderMode(int chargingPolicy) {
        if (chargingPolicy != 4) {
            return false;
        }
        boolean isChargeLimitEnabled = PowerUtils.isChargeLimitEnabledForUser(this.mSecureSettings, this.mUserTracker.getUserId());
        if (isChargeLimitEnabled) {
            return this.mLevel >= 80;
        }
        return true;
    }

    @Override
    public boolean isReverseOn() {
        return this.mReverse;
    }

    @Override
    public boolean isReverseSupported() {
        return this.mReverseChargingController.isReverseSupported();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        this.mReverseChargingController.handleIntentForReverseCharging(intent);
    }

    public void onReverseChargingChanged(int rtxLevel, String name, boolean rtx) {
        this.mReverse = rtx;
        this.mRtxLevel = rtxLevel;
        this.mName = name;
        if (DEBUG) {
            Log.d("BatteryControllerGoogle", "onReverseChargingChanged(): rtx=" + (rtx ? 1 : 0) + " level=" + rtxLevel + " name=" + name);
        }
        dispatchSafeChange(callback -> callback.onReverseChanged(this.mRtxLevel, this.mName, this.mReverse));
    }

    public void setBatteryLevel(int level) {
        this.mLevel = level;
    }

    @Override
    public void setReverseState(boolean isReverse) {
        if (this.mReverseChargingController.isReverseSupported()) {
            if (ReverseChargingController.DEBUG) {
                Log.d("ReverseChargingControl", "setReverseState(): rtx=" + (isReverse ? 1 : 0));
            }
            this.mReverseChargingController.mStopReverseAtAcUnplug = false;
            this.mReverseChargingController.setReverseStateInternal(2, isReverse);
        }
    }
}
