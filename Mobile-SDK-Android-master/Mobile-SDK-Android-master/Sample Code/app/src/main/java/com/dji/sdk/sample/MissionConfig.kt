package com.dji.sdk.sample.config

import android.content.Context
import com.dji.sdk.sample.CalibrationStore
import com.dji.sdk.sample.model.MissionStepAisle

/**
 * Unified mission config facade.
 *
 * - Single source of truth for values at runtime: MissionStepAisle (in-memory).
 * - Persistence: CalibrationStore (SharedPreferences JSON maps).
 * - init(context) loads persisted values into the model once.
 *
 * Public API stays compatible with previous MissionConfig usages.
 */
object MissionConfig {
    private lateinit var appContext: Context
    private var initialized = false

    // Defaults (used only as fallback if nothing is persisted)
    private val defaultRowHeights = floatArrayOf(0.8f, 2.0f, 3.6f, 5.6f, 7.1f, 8.6f)
    private val defaultCumulative = floatArrayOf(
        1.8740f, 2.9810f, 4.3590f, 5.6220f, 6.8140f, 8.1320f, 9.3960f, 10.5950f,
        11.9140f, 13.1950f, 14.4240f, 15.6180f, 17.1130f, 18.1860f, 19.4610f, 20.6350f,
        22.0460f, 23.1980f, 24.4600f, 25.7340f, 26.9430f, 28.2600f, 29.5670f, 30.7470f,
        32.0890f, 33.2640f, 34.6000f, 35.7810f
    )

    /** Call once (e.g. Application.onCreate). Loads persisted values into MissionStepAisle. */
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        // Load any saved calibration into the model so missions see edited values.
        CalibrationStore.loadIntoModel(appContext)
        initialized = true
    }

    // ---- Row heights ----
    fun getRowHeight(row: Int): Float {
        require(row in 1..6) { "row must be 1..6" }
        ensureInit()
        return MissionStepAisle.getRowHeights()[row] ?: defaultRowHeights[row - 1]
    }

    fun setRowHeight(row: Int, value: Float) {
        require(row in 1..6) { "row must be 1..6" }
        require(value > 0f) { "row height must be > 0" }
        ensureInit()
        // Update model + persist
        MissionStepAisle.setRowHeight(row, value)
        CalibrationStore.setRowHeight(appContext, row, value)
    }

    fun getAllRowHeights(): List<Float> = (1..6).map { getRowHeight(it) }

    fun setAllRowHeights(values: List<Float>) {
        require(values.size == 6) { "Expected 6 row heights" }
        ensureInit()
        val map = (1..6).associateWith { v ->
            values[v - 1].also { require(it > 0f) { "row height must be > 0" } }
        }
        MissionStepAisle.setAllRowHeights(map)
        // Persist rows, keep current cumulative values
        CalibrationStore.saveFromUI(appContext, map, MissionStepAisle.getCumulativeFromFirst())
    }

    // ---- Cumulative distances (to pallet center) ----
    fun getCumulative(slot: Int): Float {
        require(slot in 1..28) { "slot must be 1..28" }
        ensureInit()
        return MissionStepAisle.getCumulativeFromFirst()[slot] ?: defaultCumulative[slot - 1]
    }

    fun setCumulative(slot: Int, value: Float) {
        require(slot in 1..28) { "slot must be 1..28" }
        require(value >= 0f) { "cumulative distance must be ≥ 0" }
        ensureInit()
        // Update model + persist
        MissionStepAisle.setPalletCumulative(slot, value)
        CalibrationStore.setCumulativeForSlot(appContext, slot, value)
    }

    fun getAllCumulative(): List<Float> = (1..28).map { getCumulative(it) }

    fun setAllCumulative(values: List<Float>) {
        require(values.size == 28) { "Expected 28 cumulative values" }
        ensureInit()
        val map = (1..28).associateWith { v ->
            values[v - 1].also { require(it >= 0f) { "cumulative distance must be ≥ 0" } }
        }
        MissionStepAisle.setAllCumulativeFromFirst(map)
        // Persist cumulative, keep current row heights
        CalibrationStore.saveFromUI(appContext, MissionStepAisle.getRowHeights(), map)
    }

    // ---- Utility ----
    private fun ensureInit() {
        check(initialized) {
            "MissionConfig.init(context) must be called once (e.g., in Application.onCreate())."
        }
    }
}
