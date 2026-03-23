package com.dji.sdk.sample.model

import android.util.Log
import kotlin.math.abs

/**
 * Virtual Stick mission step (Single or Multiple Pallets, no strafing).
 *
 * AXIS MAPPING (your app/drone):
 *  - PITCH    = lateral left/right  (meters, +right)
 *  - ROLL     = forward/back        (meters, +forward)
 *  - THROTTLE = up/down             (meters, +up)
 *  - YAW      = rotation            (degrees, +clockwise)
 */
data class MissionStepPallet(
    val pitch: Float,
    val roll: Float,
    val throttle: Float,
    val yaw: Float = 0f,
    val boundary: Boolean = false
) {
    companion object {
        // ---- Axis choice (keep TRUE to use ROLL for forward/back) ----
        private const val USE_ROLL_FOR_FORWARD = true

        // ---- Tunables ----
        private const val ENTRY_HEIGHT = 3.4446f      // cruise height between pallets
        private const val TURN_STANDOFF_M = 0.40f     // back off before yawing near rack
        // private const val APPROACH_STANDOFF_M = 0.40f // reserved if you want a re-approach

        // ---------- SINGLE SOURCE OF TRUTH ----------
        // We intentionally do NOT keep our own copies of row heights or cumulative distances.
        // Everything is read/written via MissionStepAisle so your CalibrationActivity edits apply everywhere.

        // Optional per-aisle overrides (distance) and yaw override (±90)
        private val perAisleCumulative = mutableMapOf<String, MutableMap<Int, Float>>()
        private val aisleYawOverride = mutableMapOf<String, Float>()

        // ---- Public API (delegates to MissionStepAisle to keep calibration unified) ----
        fun getRowHeights(): Map<Int, Float> = MissionStepAisle.getRowHeights()

        fun setAllRowHeights(newHeights: Map<Int, Float>) {
            MissionStepAisle.setAllRowHeights(newHeights)
        }

        fun setRowHeight(row: Int, meters: Float) {
            MissionStepAisle.setRowHeight(row, meters)
        }

        fun getCumulativeFromFirst(): Map<Int, Float> = MissionStepAisle.getCumulativeFromFirst()

        fun setAllCumulativeFromFirst(newVals: Map<Int, Float>) {
            MissionStepAisle.setAllCumulativeFromFirst(newVals)
        }

        fun setPalletCumulative(slotIndex: Int, distanceMetersFromFirst: Float) {
            MissionStepAisle.setPalletCumulative(slotIndex, distanceMetersFromFirst)
        }

        // ---- Helpers ----
        private fun normalize(letter: String) = letter.trim().uppercase()

        /** Default: Even aisles (A,C,...) → -90°, Odd (B,D,...) → +90° (face rack). Overridable per aisle. */
        private fun facePalletYaw(letter: String): Float {
            val key = normalize(letter)
            aisleYawOverride[key]?.let { return it.coerceIn(-90f, 90f) } // expect ±90
            val idx = key.first().code - 'A'.code
            return if (idx % 2 == 1) 90f else -90f
        }

        private fun yawToRack(letter: String) = facePalletYaw(letter)
        private fun yawToAislePositive(letter: String) = -facePalletYaw(letter) // one aisle direction
        private fun yawToAisleNegative(letter: String) =  facePalletYaw(letter) // opposite aisle direction

        /** Current calibrated row height (falls back to ENTRY if missing). */
        private fun rowHeight(row: Int): Float {
            return MissionStepAisle.getRowHeights()[row] ?: ENTRY_HEIGHT
        }

        /** Current calibrated cumulative distance for a slot (per-aisle override > global). */
        private fun cumulativeTo(letter: String, slot: Int): Float {
            val key = normalize(letter)
            val perAisle = perAisleCumulative[key]
            val valOverride = perAisle?.get(slot)
            if (valOverride != null) return valOverride

            return MissionStepAisle.getCumulativeFromFirst()[slot]
                ?: error("Missing cumulative distance for slot=$slot")
        }

        /** Forward/back step with correct axis mapping (ROLL is forward). */
        private fun fwd(dMeters: Float): MissionStepPallet =
            if (USE_ROLL_FOR_FORWARD) MissionStepPallet(pitch = 0f, roll = dMeters, throttle = 0f, yaw = 0f)
            else                       MissionStepPallet(pitch = dMeters, roll = 0f, throttle = 0f, yaw = 0f)

        /** Hover step (runner focuses + shoots here). */
        private fun hover() = MissionStepPallet(0f, 0f, 0f, 0f)

        // ---------- Single pallet ----------
        fun missionForPallet(
            letter: String,
            row: Int,
            palletIndex: Int,
            finalFacePallets: Boolean = true,
            extraForwardM: Float = 0f,
            launchAGL: Float = 0f
        ): List<MissionStepPallet> {
            require(palletIndex in 1..28) { "palletIndex must be in 1..28" }
            val rowAGL = rowHeight(row)
            val forwardMeters = cumulativeTo(letter, palletIndex) + extraForwardM
            val yawDegrees = if (finalFacePallets) yawToRack(letter) else 0f

            Log.d(
                "MissionStepPallet",
                "Single: aisle=${normalize(letter)} slot=$palletIndex fwd=$forwardMeters," +
                        " launchAGL=$launchAGL → ENTRY=$ENTRY_HEIGHT → row=$rowAGL, yaw=$yawDegrees"
            )

            val steps = mutableListOf<MissionStepPallet>()

            // 1) Climb to ENTRY height
            val toEntry = ENTRY_HEIGHT - launchAGL
            if (toEntry != 0f) steps += MissionStepPallet(0f, 0f, toEntry)

            // 2) Forward to pallet position (absolute from aisle start)
            if (forwardMeters != 0f) steps += fwd(forwardMeters)

            // 3) Rotate to face pallet
            if (yawDegrees != 0f) steps += MissionStepPallet(0f, 0f, 0f, yawDegrees)

            // 4) Adjust height to row level
            val toRow = rowAGL - ENTRY_HEIGHT
            if (toRow != 0f) steps += MissionStepPallet(0f, 0f, toRow)

            // 5) Hover at pallet  (runner focuses + takes photo)
            repeat(3) { steps += hover() }

            return steps
        }

        // ---------- Multiple pallets ----------
        fun missionForPallets(
            letter: String,
            row: Int,
            pallets: List<Int>,
            finalFacePallets: Boolean = true,
            extraForwardM: Float = 0f,
            launchAGL: Float = 0f
        ): List<MissionStepPallet> {
            require(pallets.isNotEmpty()) { "pallets is empty" }

            val steps = mutableListOf<MissionStepPallet>()
            val rowAGL = rowHeight(row)
            val yawRack = yawToRack(letter)
            val yawAislePos = yawToAislePositive(letter)
            val yawAisleNeg = yawToAisleNegative(letter)

            // First pallet: absolute approach
            steps += MissionStepPallet(0f, 0f, 0f, 0f, boundary = true)
            steps += missionForPallet(letter, row, pallets.first(), finalFacePallets, extraForwardM, launchAGL)

            // Transitions
            for (i in 0 until pallets.lastIndex) {
                val from = pallets[i]
                val to   = pallets[i + 1]

                val cumFrom = cumulativeTo(letter, from)
                val cumTo   = cumulativeTo(letter, to)
                val delta   = cumTo - cumFrom
                val deltaAbs = abs(delta)
                val dirYaw  = if (delta >= 0f) yawAislePos else yawAisleNeg

                // New segment boundary (runner may clear reverse stack here)
                steps += MissionStepPallet(0f, 0f, 0f, 0f, boundary = true)

                // a) Back away from rack before turning (safety)
                steps += fwd(-TURN_STANDOFF_M)

                // b) Yaw down the aisle toward next pallet
                steps += MissionStepPallet(0f, 0f, 0f, dirYaw)

                // c) Climb to ENTRY height to translate
                val climbToEntry = ENTRY_HEIGHT - rowAGL
                if (climbToEntry != 0f) steps += MissionStepPallet(0f, 0f, climbToEntry)

                // d) Translate along aisle by |Δ|
                if (deltaAbs != 0f) steps += fwd(deltaAbs)

                // e) Face the rack again
                steps += MissionStepPallet(0f, 0f, 0f, -dirYaw)

                // f) Return to row height
                val backToRow = rowAGL - ENTRY_HEIGHT
                if (backToRow != 0f) steps += MissionStepPallet(0f, 0f, backToRow)

                // g) Hover at new pallet (runner focuses + shoots)
                repeat(3) { steps += hover() }
            }
            return steps
        }

        // ---- Calibration / overrides ----
        fun setAisleCumulative(letter: String, slot: Int, cumulativeMeters: Float) {
            require(slot in 1..28) { "slot must be in 1..28" }
            require(cumulativeMeters >= 0f) { "cumulativeMeters must be ≥ 0" }
            perAisleCumulative.getOrPut(normalize(letter)) { mutableMapOf() }[slot] = cumulativeMeters
        }

        fun setAllAisleCumulative(letter: String, all: List<Float>) {
            require(all.size == 28) { "Expected 28 cumulative values" }
            val map = perAisleCumulative.getOrPut(normalize(letter)) { mutableMapOf() }
            all.forEachIndexed { i, v ->
                require(v >= 0f) { "Value must be ≥ 0" }
                map[i + 1] = v
            }
        }

        /** If your site’s rack side is flipped, set ±90 here per-aisle. */
        fun setAisleRackYaw(letter: String, yawDegrees: Float /* expect +90 or -90 */) {
            require(abs(yawDegrees) == 90f) { "yawDegrees must be +90 or -90" }
            aisleYawOverride[normalize(letter)] = yawDegrees
        }
    }
}
