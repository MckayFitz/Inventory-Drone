package com.dji.sdk.sample.settings

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.CalibrationStore
import com.dji.sdk.sample.R
import com.dji.sdk.sample.model.MissionStepAisle

class CalibrationActivity : AppCompatActivity() {

    private val rowEdits = mutableMapOf<Int, EditText>()      // 1..6
    private val palletEdits = mutableMapOf<Int, EditText>()   // 1..28

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calibration_activity)

        // Pull latest saved values into the in-memory model before building UI
        CalibrationStore.loadIntoModel(this)

        buildRowHeightsUI()
        buildPalletsUI()

        findViewById<Button>(R.id.btnSave).setOnClickListener { onSave() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            // Reload from disk and push into model, then refill UI
            CalibrationStore.loadIntoModel(this)
            fillFromModel()
            toast("Reset to last saved values")
        }

        fillFromModel()
    }

    override fun onResume() {
        super.onResume()
        // In case another screen changed values while this was paused
        CalibrationStore.loadIntoModel(this)
        fillFromModel()
    }

    private fun buildRowHeightsUI() {
        val container = findViewById<LinearLayout>(R.id.row_heights_container)
        container.removeAllViews()

        for (row in 1..6) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }

            val label = TextView(this).apply {
                text = "Row $row (m)"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = "0.0"
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(0xFFFFFFFF.toInt())
            }

            rowLayout.addView(label)
            rowLayout.addView(input)
            container.addView(rowLayout)

            rowEdits[row] = input
        }
    }

    private fun buildPalletsUI() {
        val container = findViewById<LinearLayout>(R.id.pallets_container)
        container.removeAllViews()

        for (i in 1..28) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }

            val label = TextView(this).apply {
                text = "Slot $i (m from slot #1)"
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = "0.0"
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(0xFFFFFFFF.toInt())
            }

            rowLayout.addView(label)
            rowLayout.addView(input)
            container.addView(rowLayout)

            palletEdits[i] = input
        }
    }

    private fun fillFromModel() {
        // Use the model’s current values (which CalibrationStore.loadIntoModel just pushed)
        val rows = MissionStepAisle.getRowHeights()
        for (row in 1..6) {
            val v = rows[row]
            rowEdits[row]?.setText(v?.toString() ?: "")
        }

        val cum = MissionStepAisle.getCumulativeFromFirst()
        for (i in 1..28) {
            val v = cum[i]
            palletEdits[i]?.setText(v?.toString() ?: "")
        }
    }

    private fun onSave() {
        // Parse & validate row heights
        val newRows = mutableMapOf<Int, Float>()
        for (row in 1..6) {
            val s = rowEdits[row]?.text?.toString()?.trim().orEmpty()
            val v = s.toFloatOrNullSafely()
            if (v == null || v <= 0f) {
                toast("Invalid row $row height")
                return
            }
            newRows[row] = v
        }

        // Parse & validate cumulative distances
        val newCum = mutableMapOf<Int, Float>()
        for (i in 1..28) {
            val s = palletEdits[i]?.text?.toString()?.trim().orEmpty()
            val v = s.toFloatOrNullSafely()
            if (v == null || v < 0f) {
                toast("Invalid slot $i distance")
                return
            }
            newCum[i] = v
        }

        // Update in-memory model first (public APIs on MissionStepAisle)
        MissionStepAisle.setAllRowHeights(newRows)
        // Either bulk set if you added it, or per-slot (this works with your current API)
        // MissionStepAisle.setAllCumulativeFromFirst(newCum)
        newCum.forEach { (i, v) -> MissionStepAisle.setPalletCumulative(i, v) }

        // Persist to SharedPreferences (and also pushes into model)
        CalibrationStore.saveFromUI(this, newRows, newCum)

        toast("Saved calibration")
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun String.toFloatOrNullSafely(): Float? {
        // Accept both "." and "," decimals
        val normalized = this.replace(',', '.')
        return normalized.toFloatOrNull()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
