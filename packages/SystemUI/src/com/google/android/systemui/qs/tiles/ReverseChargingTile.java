package com.google.android.systemui.qs.tiles;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Temperature;
import android.provider.Settings;
import android.util.Log;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Prefs;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;

import javax.inject.Inject;

public final class ReverseChargingTile extends QSTileImpl<QSTile.BooleanState> implements BatteryController.BatteryStateChangeCallback {
    public static final String TILE_SPEC = "reverse";
    public static final boolean DEBUG = Log.isLoggable("ReverseChargingTile", 3);

    public final BatteryControllerImpl mBatteryController;
    public int mBatteryLevel;
    public boolean mListening;
    public boolean mOverHeat;
    public boolean mPowerSave;
    public boolean mReverse;
    public boolean mRtxDisabled;
    public final ContentObserver mSettingsObserver;
    public final IThermalEventListener mThermalEventListener;
    public final IThermalService mThermalService;
    public int mThresholdLevel;

    @Inject
    public ReverseChargingTile(
            QSHost qSHost,
            QsEventLogger qsEventLogger,
            @Background Looper bgLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qSLogger,
            BatteryControllerImpl batteryControllerImpl,
            IThermalService iThermalService) {
        super(qSHost, qsEventLogger, bgLooper, mainHandler, falsingManager, metricsLogger, statusBarStateController, activityStarter, qSLogger);
        this.mThermalEventListener = new IThermalEventListener.Stub() {
            @Override
            public void notifyThrottling(Temperature temperature) {
                int status = temperature.getStatus();
                mOverHeat = status >= 5;
                if (DEBUG) {
                    Log.d("ReverseChargingTile", "notifyThrottling(): status=" + status);
                }
            }
        };
        this.mSettingsObserver = new ContentObserver(this.mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateThresholdLevel();
            }
        };
        this.mBatteryController = batteryControllerImpl;
        batteryControllerImpl.observe(this.mLifecycle, this);
        this.mThermalService = iThermalService;
    }

    @Override
    public Intent getLongClickIntent() {
        Intent intent = new Intent("android.settings.REVERSE_CHARGING_SETTINGS");
        intent.setPackage("com.android.settings");
        return intent;
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public CharSequence getTileLabel() {
        return this.mContext.getString(R.string.reverse_charging_title);
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        if (((QSTile.BooleanState) this.mState).state == 0) {
            return;
        }
        this.mReverse = !this.mReverse;
        if (DEBUG) {
            Log.d("ReverseChargingTile", "handleClick(): rtx=" + (this.mReverse ? 1 : 0));
        }
        this.mBatteryController.setReverseState(this.mReverse);
        if (Prefs.get(this.mHost.getUserContext()).getBoolean("HasSeenReverseBottomSheet", false)) {
            return;
        }
        Intent intent = new Intent("android.settings.REVERSE_CHARGING_BOTTOM_SHEET");
        intent.setPackage("com.android.settings");
        this.mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        Prefs.putBoolean(this.mHost.getUserContext(), "HasSeenReverseBottomSheet", true);
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (this.mListening == listening) {
            return;
        }
        this.mListening = listening;
        if (listening) {
            updateThresholdLevel();
            boolean overHeat = false;
            this.mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor("advanced_battery_usage_amount"), false, this.mSettingsObserver);
            try {
                if (this.mThermalService != null) {
                    this.mThermalService.registerThermalEventListenerWithType(this.mThermalEventListener, 3);
                    Temperature[] temperatures = this.mThermalService.getCurrentTemperaturesWithType(3);
                    for (Temperature t : temperatures) {
                        if (t.getStatus() >= 5) {
                            Log.w("ReverseChargingTile", "isOverHeat(): current skin status = " + t.getStatus());
                            overHeat = true;
                            break;
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.e("ReverseChargingTile", "Could not register thermal event listener, exception: " + e);
            }
            this.mOverHeat = overHeat;
        } else {
            this.mContext.getContentResolver().unregisterContentObserver(this.mSettingsObserver);
            try {
                if (this.mThermalService != null) {
                    this.mThermalService.unregisterThermalEventListener(this.mThermalEventListener);
                }
            } catch (RemoteException e) {
                Log.e("ReverseChargingTile", "Could not unregister thermal event listener, exception: " + e);
            }
        }
        if (DEBUG) {
            Log.d("ReverseChargingTile", "handleSetListening(): rtx=" + (this.mReverse ? 1 : 0) + " listening=" + listening);
        }
    }

    @Override
    public void handleUpdateState(QSTile.BooleanState state, Object arg) {
        boolean wireless = this.mBatteryController.isWirelessCharging();
        boolean lowBattery = this.mBatteryLevel <= this.mThresholdLevel;
        boolean disabled = this.mRtxDisabled || this.mOverHeat || this.mPowerSave || wireless || lowBattery;
        boolean active = !disabled && this.mReverse;
        state.value = active;
        state.state = disabled ? 0 : (this.mReverse ? 2 : 1);
        state.icon = QSTileImpl.ResourceIcon.get(active ? R.drawable.qs_battery_share_icon_on : R.drawable.qs_battery_share_icon_off);
        CharSequence label = getTileLabel();
        state.label = label;
        state.contentDescription = label;
        state.expandedAccessibilityClassName = Switch.class.getName();
        String secondaryLabel;
        if (this.mOverHeat) {
            secondaryLabel = this.mContext.getString(R.string.too_hot_label);
        } else if (this.mPowerSave) {
            secondaryLabel = this.mContext.getString(R.string.quick_settings_dark_mode_secondary_label_battery_saver);
        } else if (wireless) {
            secondaryLabel = this.mContext.getString(R.string.wireless_charging_label);
        } else if (lowBattery) {
            secondaryLabel = this.mContext.getString(R.string.low_battery_label);
        } else {
            secondaryLabel = null;
        }
        state.secondaryLabel = secondaryLabel;
        if (DEBUG) {
            Log.d("ReverseChargingTile", "handleUpdateState(): rtx=" + (this.mReverse ? 1 : 0) + " state=" + state.state);
        }
    }

    @Override
    public boolean isAvailable() {
        return this.mBatteryController.isReverseSupported();
    }

    @Override
    public QSTile.BooleanState newTileState() {
        return new QSTile.BooleanState();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        this.mBatteryLevel = level;
        this.mReverse = this.mBatteryController.isReverseOn();
        refreshState(null);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        this.mPowerSave = isPowerSave;
        refreshState(null);
    }

    @Override
    public void onReverseChanged(int rtxLevel, String name, boolean rtx) {
        this.mReverse = rtx;
        this.mRtxDisabled = !rtx && rtxLevel == -100;
        refreshState(null);
    }

    public void updateThresholdLevel() {
        this.mThresholdLevel = Settings.Global.getInt(this.mContext.getContentResolver(), "advanced_battery_usage_amount", 2) * 5;
    }
}
