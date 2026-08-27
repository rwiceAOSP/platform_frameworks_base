package com.google.android.systemui.statusbar.policy;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.ServiceManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.qs.pipeline.domain.model.AutoAddable;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.statusbar.policy.BatteryControllerLogger;
import com.android.systemui.util.settings.SecureSettings;
import com.google.android.systemui.qs.pipeline.domain.autoaddable.ReverseChargingAutoAddable;
import com.google.android.systemui.qs.tiles.ReverseChargingTile;
import com.google.android.systemui.reversecharging.ReverseChargingController;
import com.google.android.systemui.reversecharging.ReverseChargingViewController;
import com.google.android.systemui.reversecharging.ReverseWirelessCharger;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;

import java.util.Optional;

@Module
public abstract class GooglePolicyModule {

    @Provides
    @SysUISingleton
    static BatteryControllerImplGoogle provideBatteryController(
            Context context,
            EnhancedEstimates enhancedEstimates,
            PowerManager powerManager,
            BroadcastDispatcher broadcastDispatcher,
            DemoModeController demoModeController,
            DumpManager dumpManager,
            BatteryControllerLogger batteryControllerLogger,
            @Main Handler mainHandler,
            @Background Handler bgHandler,
            UserTracker userTracker,
            ReverseChargingController reverseChargingController,
            SecureSettings secureSettings) {
        BatteryControllerImplGoogle batteryControllerImplGoogle = new BatteryControllerImplGoogle(
                context, enhancedEstimates, powerManager, broadcastDispatcher, demoModeController,
                dumpManager, batteryControllerLogger, mainHandler, bgHandler, userTracker,
                reverseChargingController, secureSettings, userTracker);
        batteryControllerImplGoogle.init();
        return batteryControllerImplGoogle;
    }

    @Binds
    abstract BatteryController bindBatteryController(BatteryControllerImplGoogle impl);

    @Binds
    abstract BatteryControllerImpl bindBatteryControllerImpl(BatteryControllerImplGoogle impl);

    @Provides
    @SysUISingleton
    static Optional<ReverseWirelessCharger> provideReverseWirelessCharger(Context context) {
        if (context.getResources().getBoolean(R.bool.config_wlc_support_enabled)) {
            return Optional.of(new ReverseWirelessCharger(context));
        } else {
            return Optional.empty();
        }
    }

    @Provides
    @SysUISingleton
    static Optional<UsbManager> provideUsbManager(Context context) {
        return Optional.ofNullable(context.getSystemService(UsbManager.class));
    }

    @Provides
    @SysUISingleton
    static IThermalService provideIThermalService() {
        return IThermalService.Stub.asInterface(ServiceManager.getService("thermalservice"));
    }

    @Binds
    @IntoMap
    @ClassKey(ReverseChargingViewController.class)
    abstract CoreStartable bindReverseChargingViewControllerStartable(ReverseChargingViewController impl);

    @Binds
    @IntoMap
    @StringKey(ReverseChargingTile.TILE_SPEC)
    abstract QSTileImpl<?> bindReverseChargingTile(ReverseChargingTile impl);

    @Binds
    @IntoSet
    abstract AutoAddable bindReverseChargingAutoAddable(ReverseChargingAutoAddable impl);
}
