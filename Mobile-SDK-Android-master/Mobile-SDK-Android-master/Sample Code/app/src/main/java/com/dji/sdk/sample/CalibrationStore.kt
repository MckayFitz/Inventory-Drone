package com.dji.sdk.sample

import android.content.Context
import com.dji.sdk.sample.model.MissionStepAisle
import com.dji.sdk.sample.model.MissionStepPallet
import org.json.JSONObject

/**
 * SharedPreferences-backed persistence for:
 *  - Row heights (rows 1..6), meters AGL
 *  - Cumulative distances from FIRST pallet (slots 1..28), meters
 *
 * Exposes: loadIntoModel(), saveFromUI(), getters, and single-field setters.
 */
object CalibrationStore {
    private const val PREFS = "calibration_prefs"
    private const val KEY_ROW = "row_heights"
    private const val KEY_CUM = "pallet_cumulative_from_first"

    // ---------- Public API used by CalibrationActivity ----------

    /** Load saved values from disk and push them into BOTH mission models. */
    fun loadIntoModel(ctx: Context) {
        val rows = getRowHeights(ctx)
        val cum  = getCumulativeFromFirst(ctx)
        // Aisle missions
        MissionStepAisle.setAllRowHeights(rows)
        MissionStepAisle.setAllCumulativeFromFirst(cum)
        // Pallet missions
        MissionStepPallet.setAllRowHeights(rows)
        MissionStepPallet.setAllCumulativeFromFirst(cum)
    }

    /**
     * Persist values the user edited and update BOTH mission models immediately.
     */
    fun saveFromUI(ctx: Context, rows: Map<Int, Float>, cumulative: Map<Int, Float>) {
        // persist
        saveMap(ctx, KEY_ROW, rows)
        saveMap(ctx, KEY_CUM, cumulative)

        // update in-memory models (aisle + pallet)
        MissionStepAisle.setAllRowHeights(rows)
        MissionStepAisle.setAllCumulativeFromFirst(cumulative)
        MissionStepPallet.setAllRowHeights(rows)
        MissionStepPallet.setAllCumulativeFromFirst(cumulative)
    }

    // Convenience getters for UI fill
    fun getRowHeights(ctx: Context): MutableMap<Int, Float> =
        loadMap(ctx, KEY_ROW, defaultRows())

    fun getCumulativeFromFirst(ctx: Context): MutableMap<Int, Float> =
        loadMap(ctx, KEY_CUM, defaultCum())

    // Optional single setters that also update BOTH models immediately
    fun setRowHeight(ctx: Context, row: Int, meters: Float) {
        val map = getRowHeights(ctx)
        map[row] = meters
        saveMap(ctx, KEY_ROW, map)
        MissionStepAisle.setRowHeight(row, meters)
        MissionStepPallet.setRowHeight(row, meters)
    }

    fun setCumulativeForSlot(ctx: Context, slot: Int, meters: Float) {
        val map = getCumulativeFromFirst(ctx)
        map[slot] = meters
        saveMap(ctx, KEY_CUM, map)
        MissionStepAisle.setPalletCumulative(slot, meters)
        MissionStepPallet.setPalletCumulative(slot, meters)
    }

    // ---------- Storage helpers ----------

    private fun defaultRows(): LinkedHashMap<Int, Float> = linkedMapOf(
        1 to 0.8f, 2 to 2.0f, 3 to 3.6f, 4 to 5.6f, 5 to 7.1f, 6 to 8.6f
    )

    private fun defaultCum(): LinkedHashMap<Int, Float> = linkedMapOf(
        1 to 1.4692f,  2 to 2.6096f,  3 to 3.9542f,  4 to 5.2172f,
        5 to 6.4092f,  6 to 7.7272f,  7 to 8.9912f,  8 to 10.1902f,
        9 to 11.5092f, 10 to 12.8902f, 11 to 14.0192f, 12 to 15.2132f,
        13 to 16.7082f, 14 to 17.7812f, 15 to 19.0562f, 16 to 20.2302f,
        17 to 21.6412f, 18 to 22.7932f, 19 to 24.0552f, 20 to 25.3292f,
        21 to 26.6096f, 22 to 27.8552f, 23 to 29.1622f, 24 to 30.3422f,
        25 to 31.6842f, 26 to 32.8592f, 27 to 34.1952f, 28 to 35.3762f
    )

    private fun loadMap(
        ctx: Context,
        key: String,
        defaultMap: LinkedHashMap<Int, Float>
    ): MutableMap<Int, Float> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(key, null) ?: return LinkedHashMap(defaultMap)
        return try {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<Int, Float>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k.toInt()] = obj.optDouble(k, 0.0).toFloat()
            }
            // ensure all defaults exist
            defaultMap.forEach { (k, v) -> if (!out.containsKey(k)) out[k] = v }
            out
        } catch (_: Exception) {
            LinkedHashMap(defaultMap)
        }
    }

    private fun saveMap(ctx: Context, key: String, map: Map<Int, Float>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k.toString(), v.toDouble()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, obj.toString())
            .apply()
    }
}
