package app.tsunagi.e46m3.launcher.vehicle

/**
 * `ICarInfoService.requestCarBaseInfo()` unpacked.
 *
 * The service hands back a bare `int[69]` with no schema attached, so the index
 * table below is the schema. It is transcribed from MainUI's own packing code
 * (`com.ts.can.carinfo.CarInfoService.requestCarBaseInfo`) — the same source
 * that writes the array, so the two cannot drift apart silently.
 *
 * ## Why this exists at all, given ITsCommon
 *
 * `ITsCommon` and `ICarInfoService` are two separate binder interfaces over two
 * separate data stores inside MainUI:
 *
 *     ITsCommon.GetTemp()      -> Can.mOutTemp        (CanDataInfo.CAN_OutTmp)
 *     ICarInfoService.request..-> CanFunc.mCarInfo    (CanDataInfo.CAN_Msg)
 *
 * They are filled by *different* native calls — `CanJni.GetOutTemp()` versus
 * `CanJni.GetCarInfoAidl()` — and one being empty says nothing about the other.
 * The BMW screens in MainUI read `CAN_Msg` (`CanBMWLzYbxxView` uses
 * `mCanMsg.OutTemp`), which makes this the channel BMW data actually travels on.
 */
data class CarInfo(
    val valid: Boolean,
    val rpm: Int?,
    val speedKmh: Int?,
    val coolantC: Int?,
    val oilTempRaw: Int?,
    val outsideTempC: Float?,
    val batteryV: Float?,
) {
    companion object {
        /** Indices into the int[69]. Names as MainUI writes them. */
        const val I_AVALID = 0
        const val I_SPEED = 2
        const val I_RPM = 3
        const val I_WATER_TEMP = 4
        const val I_BAT_V = 15
        const val I_OUT_TEMP = 67
        const val I_OIL_TEMP = 68

        const val LENGTH = 69

        /**
         * Outside temperature arrives in tenths of a degree.
         *
         * MainUI's own consumers agree on the scale — `CanVwCarInfoActivity`
         * formats it as `OutTemp * 0.1` in Celsius, and converts with
         * `*0.1*1.8+32` for Fahrenheit — so the stored unit is decicelsius
         * regardless of what the UI is displaying.
         */
        const val OUT_TEMP_SCALE = 0.1f

        private val PLAUSIBLE_TEMP = -60f..90f

        /**
         * Unpacks, refusing anything that is not a reading.
         *
         * `Avalid` is MainUI's own "this data means something" flag and is
         * honoured rather than second-guessed. Beyond that, an out-of-range
         * temperature is dropped rather than displayed: a wrong number on an
         * instrument is worse than no number, and this array is full of fields
         * that sit at 0 or 0xFF until the bus supplies them.
         */
        fun parse(data: IntArray?): CarInfo? {
            if (data == null || data.size < LENGTH) return null

            val outC = (data[I_OUT_TEMP] * OUT_TEMP_SCALE)
                .takeIf { it in PLAUSIBLE_TEMP && data[I_OUT_TEMP] != 0 }

            return CarInfo(
                valid = data[I_AVALID] != 0,
                rpm = data[I_RPM].takeIf { it in 0..9999 },
                speedKmh = data[I_SPEED].takeIf { it in 0..400 },
                coolantC = data[I_WATER_TEMP].takeIf { it != 0 },
                oilTempRaw = data[I_OIL_TEMP].takeIf { it != 0 },
                outsideTempC = outC,
                // Scale unconfirmed; kept raw-ish and never shown until it is.
                batteryV = null,
            )
        }
    }
}
