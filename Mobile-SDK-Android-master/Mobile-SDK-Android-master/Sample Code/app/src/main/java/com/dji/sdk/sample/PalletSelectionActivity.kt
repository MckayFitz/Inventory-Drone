package com.dji.sdk.sample

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton

class PalletSelectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AISLE   = "aisle"
        const val EXTRA_ROW     = "row"
        const val EXTRA_PALLET  = "pallet"          // single pallet
        const val EXTRA_PALLETS = "pallets"         // multiple pallets (ArrayList<Int>)
        private const val TAG = "PalletSelect"
    }

    private val selectedPallets = linkedSetOf<Int>() // ordered, no dups

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pallet_selection)

        val defaultAisle = intent.getStringExtra(EXTRA_AISLE) ?: "A"
        val defaultRow   = intent.getIntExtra(EXTRA_ROW, 1)
        val currentAisleProvider = { defaultAisle.trim().uppercase() }
        val currentRowProvider   = { defaultRow }

        // Find "Enter" button by either id
        val enterBtn: Button? = findButtonByName("btnEnter") ?: findButtonByName("btnStartSelected")
        enterBtn?.apply {
            isVisible = false
            isEnabled = false
            setOnClickListener {
                val list = ArrayList(selectedPallets)
                if (list.isEmpty()) return@setOnClickListener
                val aisle = currentAisleProvider()
                val row   = currentRowProvider()
                Log.d(TAG, "Launching multi-pallet mission: aisle=$aisle row=$row pallets=$list")

                startActivity(
                    Intent(this@PalletSelectionActivity, DroneFeedActivityPallet::class.java).apply {
                        putExtra(EXTRA_AISLE, aisle)
                        putExtra(EXTRA_ROW, row)
                        putIntegerArrayListExtra(EXTRA_PALLETS, list)
                    }
                )
            }
        }

        // Wire up pallet buttons 1–28
        for (pallet in 1..28) {
            val resName = "btnPallet$pallet"
            val buttonId = resources.getIdentifier(resName, "id", packageName)
            val btn = if (buttonId != 0) findViewById<Button>(buttonId) else null
            if (btn == null) {
                Log.w(TAG, "Missing button for $resName")
                continue
            }

            // If MaterialButton, allow check highlight
            (btn as? MaterialButton)?.isCheckable = true

            // Single tap: quick launch if nothing selected; else toggle selection
            btn.setOnClickListener {
                if (selectedPallets.isNotEmpty()) {
                    toggleSelect(btn, pallet, enterBtn)
                } else {
                    val aisle = currentAisleProvider()
                    val row   = currentRowProvider()
                    Log.d(TAG, "Launching single-pallet mission: aisle=$aisle row=$row pallet=$pallet")

                    startActivity(
                        Intent(this, DroneFeedActivityPallet::class.java).apply {
                            putExtra(EXTRA_AISLE, aisle)
                            putExtra(EXTRA_ROW, row)
                            putExtra(EXTRA_PALLET, pallet)
                        }
                    )
                }
            }

            // Long press always toggles (enter multi-select mode)
            btn.setOnLongClickListener {
                toggleSelect(btn, pallet, enterBtn)
                true
            }
        }
    }

    private fun toggleSelect(btn: Button, pallet: Int, enterBtn: Button?) {
        if (selectedPallets.contains(pallet)) {
            selectedPallets.remove(pallet)
        } else {
            selectedPallets.add(pallet)
        }

        // Visual state
        (btn as? MaterialButton)?.let { mb ->
            mb.isChecked = selectedPallets.contains(pallet)
            mb.alpha = if (mb.isChecked) 0.95f else 1.0f
        } ?: run {
            btn.alpha = if (selectedPallets.contains(pallet)) 0.6f else 1.0f
            val base = btn.text.toString().removePrefix("✓ ").trim()
            btn.text = if (selectedPallets.contains(pallet)) "✓ $base" else base
        }

        // Show/enable Enter only when there’s a selection
        enterBtn?.isVisible = selectedPallets.isNotEmpty()
        enterBtn?.isEnabled = selectedPallets.isNotEmpty()
    }

    private fun findButtonByName(name: String): Button? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }
}
