/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.systemui.power;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import javax.inject.Inject;

/**
 * First-time Battery Saver confirmation dialog (SystemUIGoogle).
 * Allows the user to choose between Standard and Extreme Battery Saver.
 */
public final class BatterySaverConfirmationDialog {
    private static final String TAG = "BatterySaverConfirmationDialog";

    public final Context mApplicationContext;
    public final ActivityStarter mActivityStarter;
    public final UiEventLogger mUiEventLogger;
    public final DialogTransitionAnimator mDialogTransitionAnimator;
    public final SystemUIDialog.Factory mSystemUIDialogFactory;
    public SystemUIDialog mConfirmationDialog;
    public boolean mIsStandardMode = true;

    @Inject
    public BatterySaverConfirmationDialog(
            Context context,
            ActivityStarter activityStarter,
            UiEventLogger uiEventLogger,
            DialogTransitionAnimator dialogTransitionAnimator,
            SystemUIDialog.Factory systemUIDialogFactory) {
        mApplicationContext = context.getApplicationContext();
        mActivityStarter = activityStarter;
        mUiEventLogger = uiEventLogger;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mSystemUIDialogFactory = systemUIDialogFactory;
    }

    public void show(Expandable expandable) {
        if (mConfirmationDialog != null && mConfirmationDialog.isShowing()) {
            return;
        }
        View dialogView = LayoutInflater.from(mApplicationContext)
                .inflate(R.layout.battery_saver_confirmation_content, null);

        RadioButton standardButton = dialogView.findViewById(R.id.standard_button);
        RadioButton extremeButton = dialogView.findViewById(R.id.extreme_button);
        View standardLayout = dialogView.findViewById(R.id.standard_option_layout);
        View extremeLayout = dialogView.findViewById(R.id.extreme_option_layout);
        Button setupButton = dialogView.findViewById(R.id.setup_button);

        mIsStandardMode = true;
        standardButton.setChecked(true);
        extremeButton.setChecked(false);

        standardLayout.setOnClickListener(v -> {
            mIsStandardMode = true;
            standardButton.setChecked(true);
            extremeButton.setChecked(false);
        });

        extremeLayout.setOnClickListener(v -> {
            mIsStandardMode = false;
            standardButton.setChecked(false);
            extremeButton.setChecked(true);
        });

        if (setupButton != null) {
            setupButton.setOnClickListener(v -> {
                log(BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG_SETUP);
                ActivityTransitionAnimator.Controller controller =
                        mDialogTransitionAnimator.createActivityTransitionController(setupButton);
                if (controller == null && mConfirmationDialog != null) {
                    mConfirmationDialog.dismiss();
                }
                mActivityStarter.startActivity(
                        new Intent("android.settings.batterysaver.flipendo.onboarding"),
                        true /* dismissShade */,
                        controller);
            });
        }

        mConfirmationDialog = mSystemUIDialogFactory.create(mApplicationContext);
        mConfirmationDialog.setTitle(R.string.saver_confirmation_dialog_title);
        mConfirmationDialog.setMessage(R.string.saver_confirmation_dialog_subtitle);
        mConfirmationDialog.setView(dialogView);
        SystemUIDialog.setShowForAllUsers(mConfirmationDialog);
        mConfirmationDialog.setCanceledOnTouchOutside(true);

        mConfirmationDialog.setPositiveButton(
                R.string.battery_saver_confirmation_ok,
                (dialog, which) -> {
                    log(BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG_TURN_ON);
                    dialog.dismiss();
                    AsyncTask.execute(() -> {
                        if (!mIsStandardMode) {
                            PowerUtils.applyExtremeSaverMode(mApplicationContext);
                        }
                        BatterySaverUtils.setPowerSaveMode(
                                mApplicationContext, true, false, 1 /* SAVER_ENABLED_CONFIRMATION */);
                        Settings.Secure.putInt(
                                mApplicationContext.getContentResolver(),
                                "low_power_warning_acknowledged", 1);
                        Settings.Secure.putInt(
                                mApplicationContext.getContentResolver(),
                                "extra_low_power_warning_acknowledged", 1);
                    });
                });

        mConfirmationDialog.setNeutralButton(
                R.string.saver_confirmation_dialog_dismiss_text,
                (dialog, which) -> {
                    log(BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG_CANCEL);
                    dialog.dismiss();
                },
                true);

        mConfirmationDialog.setOnDismissListener(dialog -> mConfirmationDialog = null);

        if (expandable != null) {
            DialogTransitionAnimator.Controller controller =
                    expandable.dialogTransitionController(
                            new DialogCuj(7, "battery_saver_confirmation"));
            if (controller != null) {
                mDialogTransitionAnimator.show(mConfirmationDialog, controller, true);
            } else {
                mConfirmationDialog.show();
            }
        } else {
            mConfirmationDialog.show();
        }

        log(BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG);
    }

    public void log(BatteryMetricEvent event) {
        if (mUiEventLogger != null) {
            if (event == BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG_TURN_ON) {
                mUiEventLogger.logWithPosition(event, 0, null, !mIsStandardMode ? 1 : 0);
            } else {
                mUiEventLogger.log(event);
            }
        }
    }
}
