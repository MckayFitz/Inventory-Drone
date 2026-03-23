package com.dji.sdk.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AisleSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_full_aisle)

        val aisleMap: Map<Int, String> = ('A'..'Z').associate { letter ->
            val resId = resources.getIdentifier("btnAisle$letter", "id", packageName)
            resId to letter.toString()
        } + mapOf(
            // Add special buttons for AA and BB if used in your XML
            resources.getIdentifier("btnAisleAA", "id", packageName) to "AA",
            resources.getIdentifier("btnAisleBB", "id", packageName) to "BB"
        ).filterKeys { it != 0 }

        aisleMap.forEach { (btnId, aisleLetter) ->
            findViewById<Button>(btnId).setOnClickListener {
                val intent = Intent(this, RowSelectionFullActivity::class.java)
                intent.putExtra("aisle", aisleLetter)
                intent.putExtra("mode", "FULL_ROW") // ✅ pass mode
                startActivity(intent)
            }
        }
    }
}
