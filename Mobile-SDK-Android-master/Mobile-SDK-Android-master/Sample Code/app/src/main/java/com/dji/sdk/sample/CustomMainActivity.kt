package com.dji.sdk.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.settings.CalibrationActivity

class CustomMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_main)

        // Full aisle scan: Aisle → Row → DroneFeedActivity
        val btnScanFullAisle = findViewById<Button>(R.id.btnScanFullAisle)
        btnScanFullAisle.setOnClickListener {
            val intent = Intent(this, AisleSelectionActivity::class.java)
            intent.putExtra("mode", "FULL_ROW") // 👈 This is key
            startActivity(intent)
        }

        // Pallet scan: Aisle → Row → Pallet → DroneFeedActivityPallet
        val btnScanPallet = findViewById<Button>(R.id.btnScanPallet)
        btnScanPallet.setOnClickListener {
            val intent = Intent(this, AisleSelectionPallet::class.java) // ✅ Updated here
            intent.putExtra("mode", "PALLET") // 👈 This is key
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnEdit).setOnClickListener {
            val intent = Intent(this, CalibrationActivity::class.java).apply {
                putExtra("aisle", "A")
                putExtra("rowHeights", floatArrayOf(0.8f, 2.0f, 3.6f, 5.6f, 7.1f, 8.6f))
            }
            startActivity(intent)
        }


    }
}
