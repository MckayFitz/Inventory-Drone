package com.dji.sdk.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class RowSelectionFullActivity : AppCompatActivity() {

    private var aisleLetter: String = "A"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_row_selection_full)

        aisleLetter = intent.getStringExtra("aisle") ?: "A"

        findViewById<Button>(R.id.btnRow1).setOnClickListener { startFullAisleMission(1) }
        findViewById<Button>(R.id.btnRow2).setOnClickListener { startFullAisleMission(2) }
        findViewById<Button>(R.id.btnRow3).setOnClickListener { startFullAisleMission(3) }
        findViewById<Button>(R.id.btnRow4).setOnClickListener { startFullAisleMission(4) }
        findViewById<Button>(R.id.btnRow5).setOnClickListener { startFullAisleMission(5) }
        findViewById<Button>(R.id.btnRow6).setOnClickListener { startFullAisleMission(6) }
    }

    private fun startFullAisleMission(row: Int) {
        val intent = Intent(this, DroneFeedActivity::class.java)
        intent.putExtra("aisle", aisleLetter) // 🔄 consistent key
        intent.putExtra("row", row)
        startActivity(intent)
    }

}
