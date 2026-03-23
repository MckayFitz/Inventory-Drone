package com.dji.sdk.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AisleSelectionPallet : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_full_aisle) // Your layout with A-Z, AA, BB buttons

        val buttonLabels = ('A'..'Z').map { it.toString() } + listOf("AA", "BB")

        for (label in buttonLabels) {
            val resId = resources.getIdentifier("btnAisle$label", "id", packageName)
            if (resId != 0) {
                findViewById<Button>(resId).setOnClickListener {
                    val intent = Intent(this, RowSelectionPalletActivity::class.java).apply {
                        putExtra("aisle", label)
                        putExtra("mode", "PALLET") // 🚚 Pallet scan mode
                    }
                    startActivity(intent)
                }
            }
        }
    }
}
