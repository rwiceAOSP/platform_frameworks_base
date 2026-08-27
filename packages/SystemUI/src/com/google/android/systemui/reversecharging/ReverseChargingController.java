package com.google.android.systemui.reversecharging;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.frameworks.stats.VendorAtom;
import android.frameworks.stats.VendorAtomValue;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Temperature;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.BootCompleteCache;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.CallbackController;
import com.google.android.systemui.statusbar.policy.BatteryControllerImplGoogle;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public final class ReverseChargingController extends BroadcastReceiver implements CallbackController<BatteryControllerImplGoogle> {
    public static final boolean DEBUG = Log.isLoggable("ReverseChargingControl", 3);
    public static final long DURATION_TO_REVERSE_TIME_OUT = 60000;
    public static final long DURATION_TO_REVERSE_AC_TIME_OUT = 60000;
    public static final long DURATION_TO_REVERSE_RX_REMOVAL_TIME_OUT = 30000;
    public static final long DURATION_TO_ADVANCED_ACCESSORY_DEVICE_RECONNECTED_TIME_OUT = 120000;
    public static final long DURATION_TO_ADVANCED_PHONE_RECONNECTED_TIME_OUT = 120000;
    public static final long DURATION_TO_ADVANCED_PLUS_ACCESSORY_DEVICE_RECONNECTED_TIME_OUT = 120000;
    public static final long DURATION_WAIT_NFC_SERVICE = 10000;

    public interface RtxStatusCallback {
        void onRtxStatusChanged(Bundle bundle);
    }

    public final AlarmManager.OnAlarmListener mAccessoryDeviceRemovedTimeoutAlarmAction;
    public final AlarmManager mAlarmManager;
    final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback;
    public final Executor mBgExecutor;
    public final BootCompleteCache mBootCompleteCache;
    public final BootCompleteCache.BootCompleteListener mBootCompleteListener;
    boolean mBootCompleted;
    public final BroadcastDispatcher mBroadcastDispatcher;
    public boolean mCacheIsReverseSupported;
    public final AlarmManager.OnAlarmListener mCheckNfcConflictWithUsbAudioAlarmAction;
    public final Context mContext;
    final boolean mDoesNfcConflictWithUsbAudio;
    public final boolean mDoesNfcConflictWithWlc;
    public boolean mIsReverseSupported;
    int mLevel;
    public final Executor mMainExecutor;
    public String mName;
    final int[] mNfcUsbProductIds;
    final int[] mNfcUsbVendorIds;
    public boolean mPluggedAc;
    public boolean mPowerSave;
    public final AlarmManager.OnAlarmListener mReconnectedTimeoutAlarmAction;
    boolean mRestoreUsbNfcPollingMode;
    public boolean mRestoreWlcNfcPollingMode;
    boolean mReverseChargingEnabled;
    public final Optional<ReverseWirelessCharger> mRtxChargerManagerOptional;
    public final AlarmManager.OnAlarmListener mRtxFinishAlarmAction;
    public final AlarmManager.OnAlarmListener mRtxFinishRxFullAlarmAction;
    public int mRtxLevel;
    IThermalEventListener mSkinThermalEventListener;
    public boolean mStartReconnected;
    public boolean mStopReverseAtAcUnplug;
    public final IThermalService mThermalService;
    public final Optional<UsbManager> mUsbManagerOptional;
    public boolean mUseRxRemovalTimeOut;
    public boolean mWirelessCharging;

    public final ArrayList<BatteryControllerImplGoogle> mChangeCallbacks = new ArrayList<>();
    int mCurrentRtxMode = 0;
    boolean mIsUsbPlugIn = false;
    public int mCurrentRtxReceiverType = 0;
    public boolean mProvidingBattery = false;
    public long mReverseStartTime = 0;

    final class SkinThermalEventListener extends IThermalEventListener.Stub {
        @Override
        public void notifyThrottling(Temperature temperature) {
            int status = temperature.getStatus();
            Log.i("ReverseChargingControl", "notifyThrottling(): thermal status=" + status);
            if (!mReverseChargingEnabled || status < 4) {
                return;
            }
            setReverseStateInternal(3, false);
        }
    }

    @Inject
    public ReverseChargingController(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            Optional<ReverseWirelessCharger> rtxChargerManagerOptional,
            AlarmManager alarmManager,
            Optional<UsbManager> usbManagerOptional,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor,
            BootCompleteCache bootCompleteCache,
            IThermalService thermalService) {
        this.mContext = context;
        this.mBroadcastDispatcher = broadcastDispatcher;
        this.mRtxChargerManagerOptional = rtxChargerManagerOptional;
        this.mAlarmManager = alarmManager;
        this.mUsbManagerOptional = usbManagerOptional;
        this.mMainExecutor = mainExecutor;
        this.mBgExecutor = bgExecutor;
        this.mBootCompleteCache = bootCompleteCache;
        this.mThermalService = thermalService;

        this.mBootCompleteListener = () -> mBootCompleted = true;
        this.mRtxFinishAlarmAction = () -> onAlarmRtxFinish(5);
        this.mRtxFinishRxFullAlarmAction = () -> onAlarmRtxFinish(103);
        this.mCheckNfcConflictWithUsbAudioAlarmAction = () -> {
            if (mUsbManagerOptional.isPresent()) {
                for (UsbDevice device : mUsbManagerOptional.get().getDeviceList().values()) {
                    checkAndChangeNfcPollingAgainstUsbAudioDevice(false, device);
                }
            }
        };
        this.mReconnectedTimeoutAlarmAction = () -> {
            if (DEBUG) {
                Log.w("ReverseChargingControl", "mReConnectedTimeoutAlarmAction() timeout");
            }
            mStartReconnected = false;
            onAlarmRtxFinish(6);
        };
        this.mAccessoryDeviceRemovedTimeoutAlarmAction = () -> {
            if (DEBUG) {
                Log.w("ReverseChargingControl", "mAccessoryDeviceRemovedTimeoutAlarmAction() timeout");
            }
            onAlarmRtxFinish(6);
        };

        this.mBatteryStateChangeCallback = new BatteryController.BatteryStateChangeCallback() {
            @Override
            public void onPowerSaveChanged(boolean isPowerSave) {
                mPowerSave = isPowerSave;
            }

            @Override
            public void onWirelessChargingChanged(boolean isWirelessCharging) {
                mWirelessCharging = isWirelessCharging;
            }
        };

        this.mDoesNfcConflictWithWlc = context.getResources().getBoolean(R.bool.config_nfc_conflict_with_wlc);
        int[] vendorIds = context.getResources().getIntArray(R.array.config_nfc_conflict_with_usb_audio_vendorid);
        int[] productIds = context.getResources().getIntArray(R.array.config_nfc_conflict_with_usb_audio_productid);
        this.mNfcUsbVendorIds = vendorIds;
        this.mNfcUsbProductIds = productIds;
        if (vendorIds.length == productIds.length) {
            this.mDoesNfcConflictWithUsbAudio = context.getResources().getBoolean(R.bool.config_nfc_conflict_with_usb_audio);
        } else {
            throw new IllegalStateException("VendorIds and ProductIds must be the same length");
        }
    }

    @Override
    public void addCallback(BatteryControllerImplGoogle batteryController) {
        synchronized (this.mChangeCallbacks) {
            this.mChangeCallbacks.add(batteryController);
        }
        batteryController.onReverseChargingChanged(this.mRtxLevel, this.mName, this.mReverseChargingEnabled);
    }

    @Override
    public void removeCallback(BatteryControllerImplGoogle batteryController) {
        synchronized (this.mChangeCallbacks) {
            this.mChangeCallbacks.remove(batteryController);
        }
    }

    public void cancelRtxTimer(int reason) {
        if (reason == 0) {
            this.mAlarmManager.cancel(this.mRtxFinishAlarmAction);
        } else if (reason == 1) {
            this.mAlarmManager.cancel(this.mRtxFinishRxFullAlarmAction);
        } else if (reason == 3) {
            this.mAlarmManager.cancel(this.mReconnectedTimeoutAlarmAction);
        } else if (reason == 4) {
            this.mAlarmManager.cancel(this.mAccessoryDeviceRemovedTimeoutAlarmAction);
        }
    }

    public void setRtxTimer(int reason, long duration) {
        if (reason == 0) {
            this.mAlarmManager.setExact(2, SystemClock.elapsedRealtime() + duration, "ReverseChargingControl", this.mRtxFinishAlarmAction, null);
        } else if (reason == 1) {
            this.mAlarmManager.setExact(2, SystemClock.elapsedRealtime() + duration, "ReverseChargingControl", this.mRtxFinishRxFullAlarmAction, null);
        } else if (reason == 2) {
            this.mAlarmManager.setExact(2, SystemClock.elapsedRealtime() + duration, "ReverseChargingControl", this.mCheckNfcConflictWithUsbAudioAlarmAction, null);
        } else if (reason == 3) {
            this.mAlarmManager.setExact(2, SystemClock.elapsedRealtime() + duration, "ReverseChargingControl", this.mReconnectedTimeoutAlarmAction, null);
        } else if (reason == 4) {
            this.mAlarmManager.setExact(2, SystemClock.elapsedRealtime() + duration, "ReverseChargingControl", this.mAccessoryDeviceRemovedTimeoutAlarmAction, null);
        }
    }

    public void checkAndChangeNfcPollingAgainstUsbAudioDevice(boolean enable, UsbDevice usbDevice) {
        for (int i = 0; i < this.mNfcUsbVendorIds.length; i++) {
            if (usbDevice.getVendorId() == this.mNfcUsbVendorIds[i] && usbDevice.getProductId() == this.mNfcUsbProductIds[i]) {
                this.mRestoreUsbNfcPollingMode = !enable;
                boolean enablePolling = !this.mRestoreWlcNfcPollingMode && enable;
                enableNfcPollingMode(enablePolling);
                return;
            }
        }
    }

    public void enableNfcPollingMode(boolean enable) {
        if (DEBUG) {
            Log.d("ReverseChargingControl", "Change NFC reader mode to flags: " + (enable ? 0 : 4096));
        }
        this.mBgExecutor.execute(() -> {
            try {
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
                if (adapter != null) {
                    adapter.setReaderModePollingEnabled(enable);
                }
            } catch (Exception e) {
                Log.e("ReverseChargingControl", "Could not change NFC reader mode, exception: " + e);
            }
        });
    }

    public void fireReverseChanged() {
        synchronized (this.mChangeCallbacks) {
            this.mMainExecutor.execute(() -> {
                ArrayList<BatteryControllerImplGoogle> list = new ArrayList<>(mChangeCallbacks);
                for (BatteryControllerImplGoogle cb : list) {
                    cb.onReverseChargingChanged(mRtxLevel, mName, mReverseChargingEnabled);
                }
            });
        }
    }

    public void handleIntentForReverseCharging(Intent intent) {
        if (!isReverseSupported() || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            boolean prevPluggedAc = this.mPluggedAc;
            this.mLevel = (int) ((intent.getIntExtra("level", 0) * 100.0f) / intent.getIntExtra("scale", 100));
            int plugged = intent.getIntExtra("plugged", 0);
            this.mPluggedAc = plugged == 1;
            if (DEBUG) {
                Log.i("ReverseChargingControl", "handleIntentForReverseCharging(): rtx=" + (mReverseChargingEnabled ? 1 : 0)
                        + " wlc=" + (mWirelessCharging ? 1 : 0) + " plgac=" + (prevPluggedAc ? 1 : 0)
                        + " ac=" + (mPluggedAc ? 1 : 0) + " acrtx=" + (mStopReverseAtAcUnplug ? 1 : 0)
                        + " extra=" + plugged);
            }
            if (mReverseChargingEnabled && mWirelessCharging) {
                if (DEBUG) Log.d("ReverseChargingControl", "handleIntentForReverseCharging(): wireless charging, stop");
                setReverseStateInternal(102, false);
                return;
            }
            if (mReverseChargingEnabled && prevPluggedAc && !mPluggedAc && mStopReverseAtAcUnplug) {
                if (DEBUG) Log.d("ReverseChargingControl", "handleIntentForReverseCharging(): wired charging, stop");
                this.mStopReverseAtAcUnplug = false;
                setReverseStateInternal(106, false);
                return;
            }
            if (mReverseChargingEnabled && isLowBattery()) {
                if (DEBUG) Log.d("ReverseChargingControl", "handleIntentForReverseCharging(): lower then battery threshold, stop");
                setReverseStateInternal(4, false);
                return;
            }
            if (this.mReverseChargingEnabled || prevPluggedAc || !this.mPluggedAc) {
                return;
            }
            if (this.mCurrentRtxMode == 0) {
                Log.d("ReverseChargingControl", "RTX is disabled");
                return;
            }
            if (Settings.Global.getInt(this.mContext.getContentResolver(), "settings_key_reverse_charging_auto_turn_on", 0) != 1) {
                Log.d("ReverseChargingControl", "auto turn on is disabled");
                return;
            }
            if (!this.mBootCompleted) {
                Log.i("ReverseChargingControl", "skip auto turn on");
                return;
            }
            if (DEBUG) Log.d("ReverseChargingControl", "handleIntentForReverseCharging(): wired charging, start");
            this.mStopReverseAtAcUnplug = true;
            setReverseStateInternal(3, true);
        } else if ("android.os.action.POWER_SAVE_MODE_CHANGED".equals(action)) {
            if (this.mReverseChargingEnabled && this.mPowerSave) {
                Log.i("ReverseChargingControl", "handleIntentForReverseCharging(): power save, stop");
                setReverseStateInternal(105, false);
            }
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            if (usbDevice == null) {
                Log.w("ReverseChargingControl", "handleIntentForReverseCharging() UsbDevice is null!");
                this.mIsUsbPlugIn = false;
                return;
            }
            if (this.mDoesNfcConflictWithUsbAudio) {
                checkAndChangeNfcPollingAgainstUsbAudioDevice(false, usbDevice);
            }
            boolean isAudio = false;
            for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                if (usbDevice.getInterface(i).getInterfaceClass() == 1) {
                    isAudio = true;
                    break;
                }
            }
            boolean isLowPower = false;
            for (int i = 0; i < usbDevice.getConfigurationCount(); i++) {
                if (usbDevice.getConfiguration(i).getMaxPower() < 100) {
                    isLowPower = true;
                    break;
                }
            }
            boolean isUsbPlugIn = !(isAudio && isLowPower);
            this.mIsUsbPlugIn = isUsbPlugIn;
            if (this.mReverseChargingEnabled && isUsbPlugIn) {
                setReverseStateInternal(108, false);
                Log.d("ReverseChargingControl", "handleIntentForReverseCharging(): stop reverse charging because USB-C plugin!");
            }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            if (this.mDoesNfcConflictWithUsbAudio && usbDevice != null) {
                checkAndChangeNfcPollingAgainstUsbAudioDevice(true, usbDevice);
            }
            this.mIsUsbPlugIn = false;
        }
    }

    public void init(BatteryControllerImplGoogle batteryController) {
        UserManager userManager = this.mContext.getSystemService(UserManager.class);
        if (userManager != null && !userManager.isSystemUser()) {
            Log.i("ReverseChargingControl", "Skip initialization for non system user");
            this.mCacheIsReverseSupported = true;
            this.mIsReverseSupported = false;
            return;
        }
        batteryController.addCallback(this.mBatteryStateChangeCallback);
        this.mCacheIsReverseSupported = false;
        this.mReverseChargingEnabled = false;
        this.mRtxLevel = -1;
        this.mName = null;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        this.mBroadcastDispatcher.registerReceiver(this, filter);
        this.mBootCompleteCache.addListener(this.mBootCompleteListener);
        if (this.mRtxChargerManagerOptional.isPresent()) {
            setRtxMode(false);
            ReverseWirelessCharger rtx = this.mRtxChargerManagerOptional.get();
            rtx.addRtxStatusCallback(this::onReverseStateChanged);
            try {
                if (this.mSkinThermalEventListener == null) {
                    this.mSkinThermalEventListener = new SkinThermalEventListener();
                }
                if (this.mThermalService != null) {
                    this.mThermalService.registerThermalEventListenerWithType(this.mSkinThermalEventListener, 3);
                }
            } catch (RemoteException e) {
                Log.e("ReverseChargingControl", "Could not register thermal event listener, exception: " + e);
            }
        }
    }

    public boolean isLowBattery() {
        int threshold = Settings.Global.getInt(this.mContext.getContentResolver(), "advanced_battery_usage_amount", 2) * 5;
        if (this.mLevel > threshold) {
            return false;
        }
        Log.w("ReverseChargingControl", "The battery is lower than threshold turn off reverse charging ! level : " + this.mLevel + ", threshold : " + threshold);
        return true;
    }

    public boolean isReverseSupported() {
        if (this.mCacheIsReverseSupported) {
            return this.mIsReverseSupported;
        }
        if (!this.mRtxChargerManagerOptional.isPresent()) {
            if (DEBUG) {
                Log.d("ReverseChargingControl", "isReverseSupported(): mRtxChargerManagerOptional is not present!");
            }
            return false;
        }
        boolean supported = this.mRtxChargerManagerOptional.get().isRtxSupported();
        this.mIsReverseSupported = supported;
        this.mCacheIsReverseSupported = true;
        return supported;
    }

    public void logReverseStartEvent(int reason) {
        if (DEBUG) {
            Log.d("ReverseChargingControl", "logReverseStartEvent: " + reason);
        }
        this.mReverseStartTime = SystemClock.uptimeMillis();
        VendorAtom vendorAtom = new VendorAtom();
        vendorAtom.reverseDomainName = "";
        VendorAtomValue[] values = new VendorAtomValue[2];
        vendorAtom.atomId = 100037;
        values[0] = VendorAtomValue.intValue(reason);
        values[1] = VendorAtomValue.intValue(this.mLevel);
        vendorAtom.values = values;
        ReverseChargingMetrics.reportVendorAtom(vendorAtom);
    }

    public void logReverseStopEvent(int reason) {
        if (DEBUG) {
            Log.d("ReverseChargingControl", "logReverseStopEvent: " + reason);
        }
        long duration = (SystemClock.uptimeMillis() - this.mReverseStartTime) / 1000;
        VendorAtom vendorAtom = new VendorAtom();
        vendorAtom.reverseDomainName = "";
        VendorAtomValue[] values = new VendorAtomValue[3];
        vendorAtom.atomId = 100038;
        values[0] = VendorAtomValue.intValue(reason);
        values[1] = VendorAtomValue.intValue(this.mLevel);
        values[2] = new VendorAtomValue(1, Long.valueOf(duration));
        vendorAtom.values = values;
        ReverseChargingMetrics.reportVendorAtom(vendorAtom);
    }

    public void onAlarmRtxFinish(int reason) {
        Log.i("ReverseChargingControl", "onAlarmRtxFinish(): rtx=0, reason: " + reason);
        setReverseStateInternal(reason, false);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        handleIntentForReverseCharging(intent);
    }

    public void onReverseStateChanged(Bundle bundle) {
        if (DEBUG) {
            Log.i("ReverseChargingControl", "onReverseStateChanged(): rtx=" + (bundle.getInt("key_rtx_mode") == 1 ? 1 : 0) + " bundle=" + bundle);
        }
        this.mBgExecutor.execute(() -> onReverseStateChangedOnBackgroundThread(bundle));
    }

    private void onReverseStateChangedOnBackgroundThread(Bundle bundle) {
        int rtxMode = bundle.getInt("key_rtx_mode");
        int reason = bundle.getInt("key_reason_type");
        boolean rtxConnection = bundle.getBoolean("key_rtx_connection");
        int accType = bundle.getInt("key_accessory_type");
        int rtxLevel = bundle.getInt("key_rtx_level");

        if (!this.mReverseChargingEnabled && this.mWirelessCharging && rtxMode == 0 && rtxLevel > 0) {
            this.mRtxLevel = rtxLevel;
            if (TextUtils.isEmpty(this.mName)) {
                this.mName = this.mContext.getString(R.string.reverse_charging_device_name_text);
            }
            fireReverseChanged();
            return;
        }

        if (!isReverseSupported()) {
            this.mReverseChargingEnabled = false;
            this.mRtxLevel = -1;
            this.mName = null;
            fireReverseChanged();
            return;
        }

        if (this.mCurrentRtxMode == 1 && rtxMode != 1 && this.mReverseChargingEnabled) {
            if (reason == 1) {
                logReverseStopEvent(4);
            } else if (reason == 2) {
                logReverseStopEvent(3);
            } else if (reason == 3) {
                logReverseStopEvent(102);
            } else if (reason == 4) {
                logReverseStopEvent(110);
            } else if (reason == 15) {
                logReverseStopEvent(8);
            } else if (rtxMode != 2 || this.mCurrentRtxReceiverType == 0) {
                logReverseStopEvent(1);
            } else {
                logReverseStopEvent(8);
            }
        } else if (this.mCurrentRtxMode != 1 && rtxMode == 1 && !this.mReverseChargingEnabled) {
            logReverseStartEvent(1);
        }

        if (this.mCurrentRtxMode != 1 && rtxMode == 1 && !this.mReverseChargingEnabled && this.mDoesNfcConflictWithWlc && !this.mRestoreWlcNfcPollingMode) {
            enableNfcPollingMode(false);
            this.mRestoreWlcNfcPollingMode = true;
        }

        this.mCurrentRtxMode = rtxMode;
        this.mReverseChargingEnabled = false;
        this.mRtxLevel = -1;
        this.mName = null;

        if (rtxMode == 1) {
            String soundPath = null;
            if (this.mProvidingBattery || !rtxConnection) {
                if (this.mProvidingBattery && !rtxConnection) {
                    if (!this.mStartReconnected && (accType == 16 || accType == 90 || accType == 114)) {
                        this.mStartReconnected = true;
                    }
                }
            } else {
                soundPath = (this.mStartReconnected && (accType == 16 || accType == 90 || accType == 114))
                        ? null : this.mContext.getString(R.string.reverse_charging_started_sound);
                this.mStartReconnected = false;
            }

            if (!TextUtils.isEmpty(soundPath)) {
                playSound(RingtoneManager.getRingtone(this.mContext, new Uri.Builder().scheme("file").appendPath(soundPath).build()));
            }

            this.mProvidingBattery = rtxConnection;
            this.mReverseChargingEnabled = true;

            if (rtxConnection) {
                this.mStopReverseAtAcUnplug = false;
                this.mRtxLevel = rtxLevel;
                this.mUseRxRemovalTimeOut = true;
                if (this.mCurrentRtxReceiverType != accType) {
                    if (accType != 0) {
                        VendorAtom vendorAtom = new VendorAtom();
                        vendorAtom.reverseDomainName = "";
                        VendorAtomValue[] values = new VendorAtomValue[1];
                        vendorAtom.atomId = 100040;
                        values[0] = VendorAtomValue.intValue((accType == 16 || accType == 114) ? 1 : 0);
                        vendorAtom.values = values;
                        ReverseChargingMetrics.reportVendorAtom(vendorAtom);
                    }
                    this.mCurrentRtxReceiverType = accType;
                }
            } else {
                this.mRtxLevel = -1;
                this.mCurrentRtxReceiverType = 0;
            }
        } else {
            this.mStopReverseAtAcUnplug = false;
            this.mProvidingBattery = false;
            this.mUseRxRemovalTimeOut = false;
            this.mStartReconnected = false;
            if (this.mDoesNfcConflictWithWlc && this.mRestoreWlcNfcPollingMode) {
                this.mRestoreWlcNfcPollingMode = false;
                enableNfcPollingMode(!this.mRestoreUsbNfcPollingMode);
            }
        }

        if (rtxMode == 0 && (reason == 4 || reason == 5)) {
            Log.i("ReverseChargingControl", "disable RTX by reason: " + reason);
            this.mRtxLevel = -100;
        }

        fireReverseChanged();
        cancelRtxTimer(0);
        cancelRtxTimer(1);
        cancelRtxTimer(4);
        if (!this.mStartReconnected) {
            cancelRtxTimer(3);
        }

        if (!this.mReverseChargingEnabled || this.mRtxLevel != -1) {
            if (!this.mReverseChargingEnabled || this.mRtxLevel < 100) {
                return;
            }
            setRtxTimer(1, 0L);
            return;
        }

        long timeout;
        if (this.mStartReconnected) {
            if (accType == 16) {
                timeout = DURATION_TO_ADVANCED_ACCESSORY_DEVICE_RECONNECTED_TIME_OUT;
            } else {
                timeout = accType == 114 ? DURATION_TO_ADVANCED_PHONE_RECONNECTED_TIME_OUT : DURATION_TO_ADVANCED_PLUS_ACCESSORY_DEVICE_RECONNECTED_TIME_OUT;
            }
        } else if (this.mStopReverseAtAcUnplug) {
            timeout = DURATION_TO_REVERSE_AC_TIME_OUT;
        } else {
            timeout = this.mUseRxRemovalTimeOut ? DURATION_TO_REVERSE_RX_REMOVAL_TIME_OUT : DURATION_TO_REVERSE_TIME_OUT;
        }

        String str = SystemProperties.get(this.mStopReverseAtAcUnplug ? "rtx.ac.timeout" : "rtx.timeout");
        if (!TextUtils.isEmpty(str)) {
            try {
                timeout = Long.parseLong(str);
            } catch (NumberFormatException e) {
                Log.w("ReverseChargingControl", "getRtxTimeOut(): invalid timeout, " + e);
            }
        }

        int timerId = 0;
        if (this.mStartReconnected) {
            timerId = 3;
        } else if (this.mUseRxRemovalTimeOut && !this.mStopReverseAtAcUnplug) {
            timerId = 4;
        }
        setRtxTimer(timerId, timeout);
    }

    public void playSound(Ringtone ringtone) {
        if (ringtone != null) {
            ringtone.setStreamType(1);
            ringtone.play();
        }
    }

    public void setReverseStateInternal(int reason, boolean enable) {
        if (!isReverseSupported()) {
            return;
        }
        Log.i("ReverseChargingControl", "setReverseStateInternal(): rtx=" + (enable ? 1 : 0) + ",reason=" + reason);
        if (!enable || this.mReverseChargingEnabled) {
            logReverseStopEvent(reason);
        } else {
            logReverseStartEvent(reason);
            if (this.mPowerSave) {
                logReverseStopEvent(104);
                return;
            } else if (isLowBattery()) {
                logReverseStopEvent(100);
                return;
            } else if (this.mIsUsbPlugIn) {
                logReverseStopEvent(107);
                return;
            }
        }
        if (enable != this.mReverseChargingEnabled) {
            if (enable && this.mDoesNfcConflictWithWlc && !this.mRestoreWlcNfcPollingMode) {
                enableNfcPollingMode(false);
                this.mRestoreWlcNfcPollingMode = true;
            }
            this.mReverseChargingEnabled = enable;
            if (enable) {
                setRtxTimer(0, DURATION_TO_REVERSE_TIME_OUT);
            }
            setRtxMode(enable);
        }
    }

    public void setRtxMode(boolean enable) {
        if (!this.mRtxChargerManagerOptional.isPresent()) {
            Log.i("ReverseChargingControl", "setRtxMode(): rtx not available");
            return;
        }
        this.mBgExecutor.execute(() -> {
            Log.i("ReverseChargingControl", "setRtxMode(): rtx=" + (enable ? 1 : 0));
            mRtxChargerManagerOptional.get().setRtxMode(enable);
        });
    }
}
