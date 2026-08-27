package com.google.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.domain.autoaddable.CallbackControllerAutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BatteryControllerImpl
import kotlinx.coroutines.channels.ProducerScope
import javax.inject.Inject

@SysUISingleton
class ReverseChargingAutoAddable @Inject constructor(
    batteryController: BatteryControllerImpl
) : CallbackControllerAutoAddable<BatteryController.BatteryStateChangeCallback>(batteryController) {
    override val spec = TileSpec.create("reverse")
    override val description = "ReverseChargingAutoAddable ($autoAddTracking)"

    override fun getCallback(producerScope: ProducerScope<Unit>): BatteryController.BatteryStateChangeCallback {
        return object : BatteryController.BatteryStateChangeCallback {
            override fun onReverseChanged(rtxLevel: Int, name: String?, rtx: Boolean) {
                if (rtx) {
                    sendAdd(producerScope)
                }
            }
        }
    }
}
