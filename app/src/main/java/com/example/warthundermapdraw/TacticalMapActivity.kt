package com.example.warthundermapdraw

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class TacticalMapActivity : AppCompatActivity() {

    private lateinit var tacticalMap: ImageButton
    private lateinit var sideMenu: LinearLayout
    private lateinit var menuOverlay: View
    private lateinit var drawMapOption: TextView
    private lateinit var tacticMapOption: TextView
    private lateinit var scanQR: Button
    private lateinit var scanStatus: TextView
    private lateinit var qrData: TextView

    private var scannedQrContent: String? = null

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
        scanStatus = findViewById(R.id.ScanStatus)
        qrData = findViewById(R.id.QRData)

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

        scanQR.setOnClickListener {
            scanQrCode()
        }
    }

    private fun scanQrCode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val result = barcode.rawValue

                if (!result.isNullOrBlank()) {
                    scannedQrContent = result
                    scanStatus.text = "QR code scanned successfully"
                    displayQrData(result)
                    Toast.makeText(this, "QR code scanned", Toast.LENGTH_SHORT).show()
                } else {
                    scanStatus.text = "QR code is empty"
                    qrData.text = ""
                }
            }
            .addOnCanceledListener {
                scanStatus.text = "Scanning cancelled"
            }
            .addOnFailureListener {
                scanStatus.text = "Unable to scan QR code"
                Toast.makeText(this, "Scanner error", Toast.LENGTH_SHORT).show()
            }
    }

    private fun displayQrData(data: String) {
        val uri = android.net.Uri.parse(data)
        val parameters = uri.queryParameterNames

        val result = StringBuilder()

        if (parameters.isNotEmpty()) {
            for (parameter in parameters) {
                val value = uri.getQueryParameter(parameter) ?: ""
                val displayedValue = if (parameter.lowercase() in listOf("enettoken", "stoken")) {
                    "********"
                } else {
                    value
                }

                result.append("$parameter: $displayedValue\n")
            }
        } else {
            result.append(data)
        }

        qrData.text = result.toString()
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