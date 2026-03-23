package com.dji.sdk.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class RowSelectionPalletActivity : AppCompatActivity() {
    private var aisleLetter: String = "A"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_row_selection_pallet)

        // 🔁 Fixed key name to match what previous screen sends
        aisleLetter = intent.getStringExtra("aisle") ?: "A"

        findViewById<Button>(R.id.btnRow1).setOnClickListener { selectRow(1) }
        findViewById<Button>(R.id.btnRow2).setOnClickListener { selectRow(2) }
        findViewById<Button>(R.id.btnRow3).setOnClickListener { selectRow(3) }
        findViewById<Button>(R.id.btnRow4).setOnClickListener { selectRow(4) }
        findViewById<Button>(R.id.btnRow5).setOnClickListener { selectRow(5) }
        findViewById<Button>(R.id.btnRow6).setOnClickListener { selectRow(6) }
    }

    private fun selectRow(row: Int) {
        val intent = Intent(this, PalletSelectionActivity::class.java).apply {
            putExtra("aisle", aisleLetter)
            putExtra("row", row)
        }
        startActivity(intent)
    }
}
