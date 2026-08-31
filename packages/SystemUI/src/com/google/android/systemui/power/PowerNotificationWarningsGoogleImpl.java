package com.google.android.systemui.power;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatterySaverLogging;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.settings.GlobalSettings;

import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.aidl.SurfaceType;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Google implementation of the power warnings UI. It replaces the AOSP low-battery warning with a
 * three-tier (low / severe / extreme) notification system driven by {@link
 * LowPowerWarningsController}.
 */
@SysUISingleton
public final class PowerNotificationWarningsGoogleImpl extends PowerNotificationWarnings {

    private static final String TAG = "PowerNotificationWarningsGoogleImpl";

    private static final String ACTION_START_FLIPENDO = "systemui.power.action.START_FLIPENDO";
    private static final String ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING =
            "PNW.dismissSevereLowBatteryWarning";
    private static final String EXTRA_SEVERE_LOW_BATTERY_NOTIFICATION =
            "extra_severe_low_battery_notification";
    private static final String SEVERE_NOTIFICATION_TURN_ON_EBS =
            "low_battery_notification_turn_on_ebs";
    private static final String SEVERE_NOTIFICATION_SWITCH_TO_EBS =
            "low_battery_notification_switch_to_ebs";

    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final GlobalSettings mGlobalSettings;
    private final UiEventLogger mUiEventLogger;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final BatteryEventClient mBatteryEventClient;

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (ACTION_START_FLIPENDO.equals(action)) {
                        handleStartFlipendo(intent);
                    } else if (ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING.equals(action)) {
                        handleDismissSevereLowBatteryWarning(intent);
                    } else if (mLowPowerWarningsController != null) {
                        mLowPowerWarningsController.dispatchIntent(intent);
                    }
                }
            };

    private LowPowerWarningsController mLowPowerWarningsController;
    private SevereLowBatteryNotification mSevereLowBatteryNotification;

    @Inject
    public PowerNotificationWarningsGoogleImpl(
            Context context,
            ActivityStarter activityStarter,
            BroadcastDispatcher broadcastDispatcher,
            BroadcastSender broadcastSender,
            UiEventLogger uiEventLogger,
            GlobalSettings globalSettings,
            Lazy<BatteryController> batteryControllerLazy,
            DialogTransitionAnimator dialogTransitionAnimator,
            UserTracker userTracker,
            BatteryEventClient batteryEventClient,
            SystemUIDialog.Factory systemUIDialogFactory,
            @UiBackground Executor executor,
            @Main Handler handler) {
        super(
                context,
                activityStarter,
                broadcastSender,
                batteryControllerLazy,
                dialogTransitionAnimator,
                uiEventLogger,
                userTracker,
                systemUIDialogFactory);
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mGlobalSettings = globalSettings;
        mUiEventLogger = uiEventLogger;
        mExecutor = executor;
        mHandler = handler;
        mBatteryEventClient = batteryEventClient;

        handler.post(this::initLowPowerWarningsController);
    }

    private void initLowPowerWarningsController() {
        LowBatteryNotification lowBatteryNotification = new LowBatteryNotification();
        lowBatteryNotification.mContext = mContext;
        lowBatteryNotification.mNotificationManager =
                mContext.getSystemService(NotificationManager.class);
        lowBatteryNotification.mKeyguardManager = mContext.getSystemService(KeyguardManager.class);

        ExtremeLowBatteryNotification extremeLowBatteryNotification =
                new ExtremeLowBatteryNotification();
        extremeLowBatteryNotification.mContext = mContext;
        extremeLowBatteryNotification.mUiEventLogger = mUiEventLogger;
        extremeLowBatteryNotification.mNotificationManager =
                mContext.getSystemService(NotificationManager.class);

        SevereLowBatteryNotification severeLowBatteryNotification =
                new SevereLowBatteryNotification(
                        mContext, mUiEventLogger, mContext.getSystemService(KeyguardManager.class));
        mSevereLowBatteryNotification = severeLowBatteryNotification;

        mLowPowerWarningsController =
                new LowPowerWarningsController(
                        mContext,
                        mExecutor,
                        mGlobalSettings,
                        mContext.getSystemService(PowerManager.class),
                        mUiEventLogger,
                        lowBatteryNotification,
                        severeLowBatteryNotification,
                        extremeLowBatteryNotification);

        // Suppress the AOSP low-battery notification and auto-saver suggestion so the tiered
        // notification system takes over.
        Settings.Secure.putInt(
                mContext.getContentResolver(), "suppress_auto_battery_saver_suggestion", 1);
        Settings.Secure.putInt(mContext.getContentResolver(), "low_power_warning_acknowledged", 1);

        mBatteryEventClient.registerBatteryEventCallback(
                SurfaceType.NOTIFICATION,
                List.of(
                        BatteryEventType.LOW_BATTERY,
                        BatteryEventType.SEVERE_LOW_BATTERY,
                        BatteryEventType.EXTREME_LOW_BATTERY),
                (events, batteryLevel) -> {
                    Log.d(TAG, "[onBatteryEventUpdate] " + events);
                    if (mLowPowerWarningsController != null) {
                        mLowPowerWarningsController.onBatteryEventUpdate(batteryLevel, events);
                    }
                });

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(BatterySaverLogging.ACTION_SAVER_STATE_MANUAL_UPDATE);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(ACTION_START_FLIPENDO);
        filter.addAction(ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING);
        mBroadcastDispatcher.registerReceiverWithHandler(
                mBroadcastReceiver, filter, mHandler, UserHandle.ALL);
    }

    @Override
    public void showLowBatteryWarning(boolean playSound) {
        // Suppress the AOSP low-battery notification. The tiered notification system
        // (LowPowerWarningsController) drives low / severe / extreme warnings instead, so the
        // inherited AOSP 20% / 10% notification must not fire and momentarily override ours.
    }

    @Override
    public void dismissLowBatteryWarning() {
        if (mLowPowerWarningsController != null) {
            mLowPowerWarningsController.cancelNotification();
        }
    }

    private void handleStartFlipendo(Intent intent) {
        mExecutor.execute(
                () -> {
                    // Force-enable Extreme Battery Saver (Flipendo).
                    try {
                        mContext.getContentResolver()
                                .call(
                                        "com.google.android.flipendo.api",
                                        "force_enable_flipendo_method",
                                        null,
                                        null);
                    } catch (Exception e) {
                        Log.e("PowerUtils", "enableFlipendo() failed", e);
                    }

                    BatteryMetricEvent event = severeLowBatteryEvent(intent, /* click= */ true);
                    if (event != null && mSevereLowBatteryNotification != null) {
                        mSevereLowBatteryNotification.logEvent(event);
                    }
                });
    }

    private void handleDismissSevereLowBatteryWarning(Intent intent) {
        BatteryMetricEvent event = severeLowBatteryEvent(intent, /* click= */ false);
        if (event != null && mSevereLowBatteryNotification != null) {
            mSevereLowBatteryNotification.logEvent(event);
        }

        mContext.getSystemService(NotificationManager.class)
                .cancelAsUser("low_battery", 3, UserHandle.ALL);
        dismissLowBatteryWarning();
    }

    private BatteryMetricEvent severeLowBatteryEvent(Intent intent, boolean click) {
        String source = intent.getStringExtra(EXTRA_SEVERE_LOW_BATTERY_NOTIFICATION);
        if (SEVERE_NOTIFICATION_TURN_ON_EBS.equals(source)) {
            return click
                    ? BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS_CLICK_TURN_ON
                    : BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS_DISMISS;
        }
        if (SEVERE_NOTIFICATION_SWITCH_TO_EBS.equals(source)) {
            return click
                    ? BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS_CLICK_SWITCH
                    : BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS_DISMISS;
        }
        return null;
    }

    @Override
    public void userSwitched() {
        if (mLowPowerWarningsController == null) {
            return;
        }
        Integer batteryLevel = mLowPowerWarningsController.getPrevBatteryLevel();
        if (batteryLevel != null) {
            mLowPowerWarningsController.onBatteryEventUpdate(
                    batteryLevel, mLowPowerWarningsController.getPrevBatteryEventTypes());
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        if (mLowPowerWarningsController != null) {
            pw.println("\tdump LowPowerWarningsController states");
            pw.println(
                    "\t\tprevBatteryLevel: " + mLowPowerWarningsController.getPrevBatteryLevel());
            pw.println(
                    "\t\tprevBatteryEventType: "
                            + mLowPowerWarningsController.getPrevBatteryEventTypes());
            pw.println(
                    "\t\tisScheduledByPercentage: "
                            + mLowPowerWarningsController.isScheduledByPercentage());
            pw.println(
                    "\t\tlowBatteryNotificationCancelled: "
                            + mLowPowerWarningsController.getLowBatteryNotificationCancelled());
            pw.println(
                    "\t\tsevereLowBatteryNotificationCancelled: "
                            + mLowPowerWarningsController
                                    .getSevereLowBatteryNotificationCancelled());
        }
    }
}
