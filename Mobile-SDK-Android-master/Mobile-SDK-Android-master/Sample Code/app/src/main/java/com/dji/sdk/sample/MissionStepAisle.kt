package com.dji.sdk.sample.model

/**
 * Virtual Stick mission step (Aisle SHORT).
 *
 * AXIS MAPPING (your app/drone):
 *  - PITCH    → left/right   (meters, +right)   ✅ lateral hop between pallets
 *  - ROLL     → forward/back (meters, +forward) ✅ reach first pallet
 *  - THROTTLE → up/down      (meters, +up)
 *  - YAW      → rotation     (degrees, +clockwise)
 */
data class MissionStepAisle(
    val pitch: Float,
    val roll: Float,
    val throttle: Float,
    val yaw: Float = 0f,
) {
    companion object {
        // --- Tunables ---
        private const val ENTRY_HEIGHT = 3.4446f
        private const val DEFAULT_FIRST_PALLET_FORWARD = 1.874f   // forward distance to slot #1 center
        private const val DEFAULT_PALLET_SPACING = 1.3f           // fallback per-hop lateral
        private const val YAW_SETTLE_STEPS = 3
        private const val EXTRA_HOVER_AT_FIRST = 1

        // Optional per-aisle override for forward distance to FIRST pallet center.
        private val firstPalletForwardByAisle = mutableMapOf<String, Float>()

        // Target heights per row (meters AGL) — mutable so UI can edit
        private val rowHeights: LinkedHashMap<Int, Float> = linkedMapOf(
            1 to 0.8f, 2 to 2.0f, 3 to 3.6f, 4 to 5.6f, 5 to 7.1f, 6 to 8.6f
        )

        // CUMULATIVE lateral distance of each slot center from slot #1 center (meters)
        // (These are editable via CalibrationActivity/CalibrationStore.)
        private val palletCentersFromFirst: LinkedHashMap<Int, Float> = linkedMapOf(
            1 to 1.4692f,  2 to 2.6096f,  3 to 3.9542f,  4 to 5.2172f,
            5 to 6.4092f,  6 to 7.7272f,  7 to 8.9912f,  8 to 10.1902f,
            9 to 11.5092f, 10 to 12.8902f, 11 to 14.0192f, 12 to 15.2132f,
            13 to 16.7082f, 14 to 17.7812f, 15 to 19.0562f, 16 to 20.2302f,
            17 to 21.6412f, 18 to 22.7932f, 19 to 24.0552f, 20 to 25.3292f,
            21 to 26.6096f, 22 to 27.8552f, 23 to 29.1622f, 24 to 30.3422f,
            25 to 31.6842f, 26 to 32.8592f, 27 to 34.1952f, 28 to 35.3762f
        )

        // ------- PUBLIC API (used by CalibrationActivity/CalibrationStore) -------

        fun getRowHeights(): Map<Int, Float> = LinkedHashMap(rowHeights)

        fun setAllRowHeights(newHeights: Map<Int, Float>) {
            for (k in 1..6) newHeights[k]?.let { v -> if (v > 0f) rowHeights[k] = v }
        }

        fun setRowHeight(row: Int, meters: Float) {
            require(row in 1..6) { "row must be 1..6" }
            require(meters > 0f) { "height must be > 0" }
            rowHeights[row] = meters
        }

        fun getCumulativeFromFirst(): Map<Int, Float> = LinkedHashMap(palletCentersFromFirst)

        fun setAllCumulativeFromFirst(newVals: Map<Int, Float>) {
            for (k in 1..28) newVals[k]?.let { v -> if (v >= 0f) palletCentersFromFirst[k] = v }
        }

        fun setPalletCumulative(slotIndex: Int, distanceMetersFromFirst: Float) {
            require(slotIndex in 1..28) { "slotIndex must be in 1..28" }
            require(distanceMetersFromFirst >= 0f) { "distance must be ≥ 0" }
            palletCentersFromFirst[slotIndex] = distanceMetersFromFirst
        }

        /** Per-aisle forward distance to FIRST pallet center. */
        fun setFirstPalletForward(letter: String, meters: Float) {
            require(meters > 0f) { "first pallet forward must be > 0" }
            firstPalletForwardByAisle[normalize(letter)] = meters
        }

        /** Override a single hop (i-1 → i) by recomputing cumulative for slot i. */
        fun setHopDelta(slotIndex: Int, hopMeters: Float) {
            require(slotIndex in 2..28) { "slotIndex must be in 2..28" }
            require(hopMeters > 0f) { "hop must be > 0" }
            val prevCum = palletCentersFromFirst[slotIndex - 1] ?: return
            palletCentersFromFirst[slotIndex] = prevCum + hopMeters
        }

        // ----------------- Helpers -----------------

        private fun normalize(letter: String): String = letter.trim().uppercase()

        /** AA→A, BB→B; Odd (B,D,...) => +90°; Even (A,C,...) => −90°. Faces the racks. */
        private fun faceYaw(letter: String): Float {
            val first = normalize(letter).first()
            val idx = first.code - 'A'.code
            return if ((idx % 2) == 1) 90f else -90f
        }

        private fun forwardToFirst(letter: String, override: Float?): Float {
            val key = normalize(letter)
            return override ?: firstPalletForwardByAisle[key] ?: DEFAULT_FIRST_PALLET_FORWARD
        }

        /** Per-hop lateral delta from (i-1) → i; i = 2..N. Falls back to DEFAULT_PALLET_SPACING. */
        private fun hopDelta(i: Int): Float {
            if (i <= 1) return 0f
            val cur = palletCentersFromFirst[i]      // cumulative to slot i
            val prev = palletCentersFromFirst[i - 1] // cumulative to slot (i-1)
            return if (cur != null && prev != null) (cur - prev).coerceAtLeast(0f) else DEFAULT_PALLET_SPACING
        }

        /**
         * SHORT indoor aisle scan:
         * 1) Up: launchAGL → ENTRY
         * 2) Up/Down: ENTRY → row height
         * 3) Forward (ROLL) to FIRST pallet center (per-aisle or override)
         * 4) Yaw to face racks, settle + hover at slot #1
         * 5) Lateral (PITCH) per-hop moves for slots 2..N with hover after each
         */
        fun missionForFullAisle(
            letter: String,
            row: Int,
            positions: Int = 28,
            forwardToFirstM: Float? = null,   // optional override to slot #1 center
            launchAGL: Float = 0f             // starting altitude (m AGL)
        ): List<MissionStepAisle> {
            require(positions in 1..28) { "positions must be in 1..28" }

            val targetHeight = rowHeights[row] ?: ENTRY_HEIGHT
            val yawToFace = faceYaw(letter)                 // +90 right, -90 left
            val pitchSign = if (yawToFace > 0) -1f else +1f // lateral sign (empirically observed)

            val steps = mutableListOf<MissionStepAisle>()

            // 1) From launch → ENTRY
            val toEntryDelta = ENTRY_HEIGHT - launchAGL
            if (toEntryDelta != 0f) steps += MissionStepAisle(0f, 0f, toEntryDelta, 0f)

            // 2) ENTRY → row height
            val toRowDelta = targetHeight - ENTRY_HEIGHT
            if (toRowDelta != 0f) steps += MissionStepAisle(0f, 0f, toRowDelta, 0f)

            // 3) Forward to FIRST pallet center (ROLL = forward)
            val fwdFirst = forwardToFirst(letter, forwardToFirstM)
            if (fwdFirst != 0f) steps += MissionStepAisle(0f, fwdFirst, 0f, 0f)

            // 4) Face racks, then settle/hover AT SLOT #1
            steps += MissionStepAisle(0f, 0f, 0f, yawToFace)
            repeat(YAW_SETTLE_STEPS + EXTRA_HOVER_AT_FIRST) {
                steps += MissionStepAisle(0f, 0f, 0f, 0f)
            }

            // 5) Lateral traverse for slots 2..positions (PITCH = lateral)
            if (positions >= 2) {
                for (i in 2..positions) {
                    val hop = hopDelta(i) // per-hop, not cumulative
                    if (hop != 0f) steps += MissionStepAisle(pitchSign * hop, 0f, 0f, 0f)
                    steps += MissionStepAisle(0f, 0f, 0f, 0f) // hover/scan at slot i
                }
            }
            return steps
        }
    }
}
