package com.google.android.systemui.theme;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.UserManager;
import android.util.Log;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.SystemPropertiesHelper;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.theme.ThemeOverlayApplier;
import com.android.systemui.theme.ThemeOverlayController;
import com.android.systemui.user.utils.UserScopedService;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Google variant of {@link ThemeOverlayController} that writes the four Material You accents into
 * {@code persist.bootanim.color{1..4}} so the boot animation can be tinted.
 */
@SysUISingleton
public final class ThemeOverlayControllerGoogle extends ThemeOverlayController {

    private final Context mContext;
    private final UserTracker mUserTracker;
    private final ConfigurationController mConfigurationController;

    @Inject
    public ThemeOverlayControllerGoogle(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            @Background Handler bgHandler,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor,
            ThemeOverlayApplier themeOverlayApplier,
            SecureSettings secureSettings,
            WallpaperManager wallpaperManager,
            UserManager userManager,
            DeviceProvisionedController deviceProvisionedController,
            UserTracker userTracker,
            DumpManager dumpManager,
            FeatureFlags featureFlags,
            @Main Resources resources,
            WakefulnessLifecycle wakefulnessLifecycle,
            JavaAdapter javaAdapter,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            UiModeManager uiModeManager,
            UserScopedService<UiModeManager> uiModeManagerProvider,
            ActivityManager activityManager,
            SystemPropertiesHelper systemPropertiesHelper,
            ConfigurationController configurationController) {
        super(
                context,
                broadcastDispatcher,
                bgHandler,
                mainExecutor,
                bgExecutor,
                themeOverlayApplier,
                secureSettings,
                wallpaperManager,
                userManager,
                deviceProvisionedController,
                userTracker,
                dumpManager,
                featureFlags,
                resources,
                wakefulnessLifecycle,
                javaAdapter,
                keyguardTransitionInteractor,
                uiModeManager,
                uiModeManagerProvider,
                activityManager,
                systemPropertiesHelper);
        mContext = context;
        mUserTracker = userTracker;
        mConfigurationController = configurationController;

        mConfigurationController.addCallback(
                new ConfigurationListener() {
                    @Override
                    public void onThemeChanged() {
                        if (mUserTracker.getUserId() != 0) {
                            return;
                        }
                        try {
                            final int[] bootColors = getBootColors();
                            for (int i = 0; i < bootColors.length; i++) {
                                final int color = bootColors[i];
                                mSystemPropertiesHelper.set(
                                        "persist.bootanim.color" + (i + 1),
                                        Integer.toString(color));
                                Log.d(
                                        TAG,
                                        "Writing boot animation colors "
                                                + (i + 1)
                                                + ": "
                                                + Integer.toHexString(color));
                            }
                        } catch (RuntimeException e) {
                            Log.w(
                                    TAG,
                                    "Cannot set sysprop. Look for 'init' and 'dmesg' logs for more "
                                            + "info.");
                        }
                    }
                });

        final int[] bootColors = getBootColors();
        for (int i = 0; i < bootColors.length; i++) {
            Log.d(TAG, "Boot animation colors " + (i + 1) + ": " + bootColors[i]);
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        super.dump(pw, args);
        pw.println("ThemeOverlayControllerGoogle: yes");
    }

    public int[] getBootColors() {
        return new int[] {
            mContext.getColor(android.R.color.system_accent3_100),
            mContext.getColor(android.R.color.system_accent1_300),
            mContext.getColor(android.R.color.system_accent2_500),
            mContext.getColor(android.R.color.system_accent1_100),
        };
    }
}
