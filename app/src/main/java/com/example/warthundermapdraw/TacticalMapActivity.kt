package com.example.warthundermapdraw

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class TacticalMapActivity : AppCompatActivity() {

    private lateinit var tacticalMap: ImageButton
    private lateinit var sideMenu: LinearLayout
    private lateinit var menuOverlay: View
    private lateinit var drawMapOption: TextView
    private lateinit var tacticMapOption: TextView
    private lateinit var scanQR: Button
    private lateinit var clearQR: Button
    private lateinit var scanStatus: TextView
    private lateinit var qrData: TextView
    private lateinit var aiStatus: TextView
    private lateinit var aiOverlay: AiSuggestionOverlay
    private var mapName: String = ""

    private var isScanAvailable = true
    private var hasScanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tactical_map)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tacticalMap = findViewById(R.id.TacticalMap)
        sideMenu = findViewById(R.id.SideMenu)
        menuOverlay = findViewById(R.id.MenuOverlay)
        drawMapOption = findViewById(R.id.DrawMapOption)
        tacticMapOption = findViewById(R.id.TacticMapOption)
        scanQR = findViewById(R.id.ScanQR)
        clearQR = findViewById(R.id.ClearQR)
        scanStatus = findViewById(R.id.ScanStatus)
        qrData = findViewById(R.id.QRData)
        aiStatus = findViewById(R.id.AIStatus)
        aiOverlay = findViewById(R.id.AIOverlay)
        mapName = intent.getStringExtra("MAP_NAME") ?: ""

        sideMenu.translationX = -280 * resources.displayMetrics.density

        tacticalMap.setOnClickListener { openMenu() }
        menuOverlay.setOnClickListener { closeMenu() }

        drawMapOption.setOnClickListener {
            closeMenu()
            finish()
        }

        tacticMapOption.setOnClickListener {
            closeMenu()
        }

        scanQR.setOnClickListener { scanQrCode() }
        clearQR.setOnClickListener { clearQrData() }

        updateButtons()
        setAiStatus("Waiting for map data")
    }

    private fun scanQrCode() {
        if (!isScanAvailable) return

        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)

        isScanAvailable = false
        scanStatus.text = "Opening scanner..."
        scanStatus.setTextColor(Color.WHITE)
        updateButtons()

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val result = barcode.rawValue

                if (!result.isNullOrBlank()) {
                    displayQrData(result)
                    hasScanned = true
                    scanStatus.text = "QR code scanned successfully"
                    scanStatus.setTextColor(Color.rgb(100, 220, 120))
                    Toast.makeText(this, "QR code scanned", Toast.LENGTH_SHORT).show()
                } else {
                    qrData.text = "QR code is empty."
                    scanStatus.text = "QR code is empty"
                    scanStatus.setTextColor(Color.rgb(255, 190, 80))
                }

                isScanAvailable = true
                updateButtons()
            }
            .addOnCanceledListener {
                scanStatus.text = "Scanning cancelled"
                scanStatus.setTextColor(Color.rgb(255, 190, 80))
                isScanAvailable = true
                updateButtons()
            }
            .addOnFailureListener {
                scanStatus.text = "Unable to scan QR code"
                scanStatus.setTextColor(Color.rgb(255, 100, 100))
                Toast.makeText(this, "Scanner error", Toast.LENGTH_SHORT).show()
                isScanAvailable = true
                updateButtons()
            }
    }

    private fun displayQrData(data: String) {
        val uri = android.net.Uri.parse(data)
        val parameters = uri.queryParameterNames
        val result = StringBuilder()

        if (parameters.isNotEmpty()) {
            for (parameter in parameters.sorted()) {
                val value = uri.getQueryParameter(parameter) ?: ""
                val displayedValue = if (parameter.lowercase().contains("token")) {
                    "********"
                } else {
                    value
                }

                result.append("$parameter: $displayedValue\n\n")
            }
        } else {
            result.append(data)
        }

        qrData.text = result.toString().trim()
    }

    private fun clearQrData() {
        qrData.text = "No QR code scanned yet."
        scanStatus.text = "Waiting for scan"
        scanStatus.setTextColor(Color.WHITE)
        hasScanned = false
        updateButtons()
    }

    private fun updateButtons() {
        scanQR.isEnabled = isScanAvailable
        clearQR.isEnabled = isScanAvailable

        scanQR.text = if (hasScanned) "Scan Again" else "Scan QR"

        scanQR.alpha = if (isScanAvailable) 1f else 0.5f
        clearQR.alpha = if (isScanAvailable) 1f else 0.5f
    }

    fun setAiStatus(status: String) {
        aiStatus.text = status
    }

    fun updateAiSuggestions(suggestions: List<AiSuggestion>) {
        aiOverlay.setSuggestions(suggestions)
    }

    fun clearAiSuggestions() {
        aiOverlay.clearSuggestions()
    }

    private fun openMenu() {
        sideMenu.visibility = View.VISIBLE
        menuOverlay.visibility = View.VISIBLE
        menuOverlay.alpha = 0f

        menuOverlay.animate().alpha(1f).setDuration(200).start()
        sideMenu.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeMenu() {
        menuOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            menuOverlay.visibility = View.GONE
        }.start()

        sideMenu.animate()
            .translationX(-280 * resources.displayMetrics.density)
            .setDuration(200)
            .withEndAction {
                sideMenu.visibility = View.GONE
            }.start()
    }
}