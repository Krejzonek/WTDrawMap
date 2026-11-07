package com.example.warthundermapdraw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var searchMap: AutoCompleteTextView
    private lateinit var image: ImageView
    private lateinit var searchMapHint: TextView
    private lateinit var edit: Button
    private lateinit var share: Button
    private lateinit var tacticalMap: ImageButton
    private lateinit var sideMenu: LinearLayout
    private lateinit var menuOverlay: View
    private lateinit var drawMapOption: TextView
    private lateinit var tacticMapOption: TextView
    private lateinit var mapAdapter: ArrayAdapter<MapEntry>
    private lateinit var mapLegend: LinearLayout
    private lateinit var mapLegendScroll: HorizontalScrollView

    private val mapEntries = mutableListOf<MapEntry>()
    private var selectedMap: MapEntry? = null

    private data class MapEntry(
        val displayName: String,
        val fileName: String,
        val isSaved: Boolean
    ) {
        override fun toString(): String = displayName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        searchMap = findViewById(R.id.SearchMap)
        image = findViewById(R.id.Map)
        searchMapHint = findViewById(R.id.SearchMapHint)
        edit = findViewById(R.id.Edit)
        share = findViewById(R.id.Share)
        tacticalMap = findViewById(R.id.TacticalMap)
        sideMenu = findViewById(R.id.SideMenu)
        menuOverlay = findViewById(R.id.MenuOverlay)
        drawMapOption = findViewById(R.id.DrawMapOption)
        tacticMapOption = findViewById(R.id.TacticMapOption)
        mapLegend = findViewById(R.id.MapLegend)
        mapLegendScroll = findViewById(R.id.MapLegendScroll)

        sideMenu.translationX = -280 * resources.displayMetrics.density

        tacticalMap.setOnClickListener {
            openMenu()
        }

        menuOverlay.setOnClickListener {
            closeMenu()
        }

        drawMapOption.setOnClickListener {
            closeMenu()
        }

        tacticMapOption.setOnClickListener {
            closeMenu()
            startActivity(Intent(this, TacticalMapActivity::class.java))
        }

        mapAdapter = ArrayAdapter(this, R.layout.spinner_item, mapEntries)
        mapAdapter.setDropDownViewResource(R.layout.spinner_item)
        searchMap.setAdapter(mapAdapter)
        searchMap.threshold = 1

        loadMapEntries()

        selectedMap = null
        searchMap.setText("", false)
        image.setImageDrawable(null)
        searchMapHint.visibility = View.VISIBLE
        edit.visibility = View.GONE
        share.visibility = View.VISIBLE
        share.text = "Load"
        mapLegendScroll.visibility = View.GONE
        mapLegend.removeAllViews()

        searchMap.setOnClickListener {
            searchMap.showDropDown()
        }

        searchMap.setOnItemClickListener { parent, _, position, _ ->
            val entry = parent.getItemAtPosition(position) as MapEntry
            selectMap(entry)

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchMap.windowToken, 0)

            searchMap.clearFocus()
        }

        searchMap.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                searchMap.showDropDown()
            }
        }

        edit.setOnClickListener {
            val entry = selectedMap ?: return@setOnClickListener

            val intent = Intent(this, MapViewActivity::class.java)
            intent.putExtra("MAP_NAME", entry.fileName)
            startActivity(intent)
        }

        share.setOnClickListener {
            val entry = selectedMap

            if (entry == null) {
                loadSharedMap()
                return@setOnClickListener
            }

            if (entry.isSaved) {
                shareCurrentMap()
            } else {
                loadSharedMap()
            }
        }

        handleCustomLink(intent)
    }

    private fun loadMapEntries() {
        mapEntries.clear()

        val allMapNames = resources.getStringArray(R.array.SelectMap)

        for (mapName in allMapNames) {
            val displayName = mapName
                .replace(Regex("map_\\d{3}px_maplayout_"), "")
                .substringBeforeLast("_")
                .replace("_", " ")

            val drawableName = mapName.lowercase()
                .replace(" ", "")
                .replaceAfterLast(".", "")
                .replace(".", "")

            mapEntries.add(
                MapEntry(
                    displayName = displayName,
                    fileName = drawableName,
                    isSaved = false
                )
            )
        }

        for (name in getSavedMaps()) {
            mapEntries.add(
                MapEntry(
                    displayName = "Saved: $name",
                    fileName = "saved_$name",
                    isSaved = true
                )
            )
        }

        mapAdapter.notifyDataSetChanged()
    }

    private fun selectMap(entry: MapEntry) {
        selectedMap = entry
        searchMap.setText(entry.displayName, false)

        searchMapHint.visibility = View.GONE
        share.text = if (entry.isSaved) "Share" else "Load"

        edit.visibility = View.VISIBLE
        share.visibility = View.VISIBLE

        loadImageForSelectedMap(entry)
        loadMapLegend(entry)
    }

    private fun loadMapLegend(entry: MapEntry) {
        mapLegend.removeAllViews()
        mapLegendScroll.visibility = View.GONE

        if (!entry.isSaved) return

        val file = File(filesDir, "${entry.fileName}.legend")
        if (!file.exists()) return

        val density = resources.displayMetrics.density

        file.readLines().forEach { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size != 2) return@forEach

            val color = parts[0].toIntOrNull() ?: return@forEach
            val label = parts[1]

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (8 * density).toInt(),
                    (5 * density).toInt(),
                    (12 * density).toInt(),
                    (5 * density).toInt()
                )
            }

            val colorView = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }

            item.addView(
                colorView,
                LinearLayout.LayoutParams(
                    (12 * density).toInt(),
                    (12 * density).toInt()
                )
            )

            val textView = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding((6 * density).toInt(), 0, 0, 0)
            }

            item.addView(textView)
            mapLegend.addView(item)
        }

        if (mapLegend.childCount > 0) {
            mapLegendScroll.visibility = View.VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCustomLink(intent)
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

    private fun handleCustomLink(intent: Intent?) {
        val data = intent?.data ?: return

        if (data.scheme != "warthundermap" || data.host != "load") {
            Toast.makeText(this, "Invalid map link", Toast.LENGTH_SHORT).show()
            return
        }

        val encoded = data.getQueryParameter("img") ?: return

        try {
            val bytes = Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Invalid image data")

            val fileName = "from_link_${System.currentTimeMillis()}"
            val savedFileName = "saved_$fileName"

            val file = File(filesDir, "$savedFileName.png")

            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            bitmap.recycle()

            val encodedLegend = data.getQueryParameter("legend")

            if (!encodedLegend.isNullOrBlank()) {
                val legendBytes = Base64.decode(
                    encodedLegend,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )

                val legendText = String(legendBytes, Charsets.UTF_8)

                File(filesDir, "$savedFileName.legend").writeText(legendText)
            }

            loadMapEntries()

            val importedMap = mapEntries.firstOrNull {
                it.fileName == savedFileName
            }

            importedMap?.let {
                selectMap(it)
            }

            Toast.makeText(
                this,
                "Map imported successfully",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Log.e("MainActivity", "Import error", e)
            Toast.makeText(
                this,
                "Invalid link or image data",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareCurrentMap() {
        try {
            val link = createMapLink() ?: return
            val qrBitmap = generateQrCode(link)

            showShareDialog(link, qrBitmap)

        } catch (e: Exception) {
            Log.e("MainActivity", "Share error", e)
            Toast.makeText(
                this,
                "Unable to generate QR code: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun createMapLink(): String? {
        val entry = selectedMap ?: return null

        val bitmap = getSelectedMapBitmap() ?: run {
            Toast.makeText(this, "Unable to load map", Toast.LENGTH_SHORT).show()
            return null
        }

        try {
            val legendFile = File(filesDir, "${entry.fileName}.legend")

            val legendText = if (entry.isSaved && legendFile.exists()) {
                legendFile.readText()
            } else {
                ""
            }

            val encodedLegend = if (legendText.isNotEmpty()) {
                Base64.encodeToString(
                    legendText.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
            } else {
                null
            }

            val scales = listOf(1f, 0.8f, 0.65f, 0.5f, 0.35f, 0.25f, 0.15f)
            val qualities = listOf(85, 70, 55, 40, 30)

            for (scale in scales) {
                val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val height = (bitmap.height * scale).toInt().coerceAtLeast(1)

                val scaledBitmap = if (scale == 1f) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, width, height, true)
                }

                try {
                    for (quality in qualities) {
                        val output = ByteArrayOutputStream()

                        scaledBitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            quality,
                            output
                        )

                        val encoded = Base64.encodeToString(
                            output.toByteArray(),
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                        )

                        val builder = Uri.Builder()
                            .scheme("warthundermap")
                            .authority("load")
                            .appendQueryParameter("img", encoded)

                        if (!encodedLegend.isNullOrEmpty()) {
                            builder.appendQueryParameter("legend", encodedLegend)
                        }

                        val link = builder.build().toString()

                        if (link.length <= 2500) {
                            return link
                        }
                    }
                } finally {
                    if (scaledBitmap !== bitmap) {
                        scaledBitmap.recycle()
                    }
                }
            }

            Toast.makeText(
                this,
                "Map and legend are too large for a QR code",
                Toast.LENGTH_LONG
            ).show()

            return null

        } finally {
            bitmap.recycle()
        }
    }

    private fun generateQrCode(link: String): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2
        )

        val matrix = QRCodeWriter().encode(
            link,
            BarcodeFormat.QR_CODE,
            900,
            900,
            hints
        )

        val bitmap = Bitmap.createBitmap(
            matrix.width,
            matrix.height,
            Bitmap.Config.ARGB_8888
        )

        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(
                    x,
                    y,
                    if (matrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }

        return bitmap
    }

    private fun showShareDialog(link: String, qrBitmap: Bitmap) {
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (12 * density).toInt(),
                (20 * density).toInt(),
                (8 * density).toInt()
            )
        }

        val qrImage = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }

        container.addView(
            qrImage,
            LinearLayout.LayoutParams(
                (260 * density).toInt(),
                (260 * density).toInt()
            )
        )

        val description = TextView(this).apply {
            text = "Scan this QR code to import the map and its legend"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, (16 * density).toInt())
        }

        container.addView(description)

        val linkLabel = TextView(this).apply {
            text = "Map link"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, (8 * density).toInt(), 0, (6 * density).toInt())
        }

        container.addView(linkLabel)

        val linkText = TextView(this).apply {
            text = link
            textSize = 12f
            setTextColor(Color.rgb(80, 160, 255))
            setPadding(
                (10 * density).toInt(),
                (10 * density).toInt(),
                (10 * density).toInt(),
                (10 * density).toInt()
            )
            setBackgroundResource(R.drawable.dark_input_background)
            isClickable = true
            isFocusable = true
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        container.addView(
            linkText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val copyButton = Button(this).apply {
            text = "Copy link"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("War Thunder Map", link)
                )

                Toast.makeText(
                    this@MainActivity,
                    "Link copied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        container.addView(
            copyButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        )

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Share map")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        linkText.setOnClickListener {
            dialog.dismiss()
            handleCustomLink(Intent().setData(Uri.parse(link)))
        }

        dialog.setOnDismissListener {
            qrBitmap.recycle()
        }

        dialog.show()
    }

    private fun getSelectedMapBitmap(): Bitmap? {
        val entry = selectedMap ?: return null
        val fileName = entry.fileName

        return if (entry.isSaved) {
            BitmapFactory.decodeFile(
                File(filesDir, "$fileName.png").absolutePath
            )
        } else {
            val resId = resources.getIdentifier(
                fileName,
                "drawable",
                packageName
            )

            if (resId == 0) return null

            val options = BitmapFactory.Options().apply {
                inScaled = false
            }

            BitmapFactory.decodeResource(resources, resId, options)
        }
    }

    private fun loadSharedMap() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue

                if (value.isNullOrBlank()) {
                    Toast.makeText(
                        this,
                        "Invalid QR code",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addOnSuccessListener
                }

                handleCustomLink(Intent().setData(Uri.parse(value)))
            }
            .addOnCanceledListener {
                Toast.makeText(
                    this,
                    "Scanning cancelled",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "QR scan error", e)

                Toast.makeText(
                    this,
                    "Unable to scan QR code",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun loadImageForSelectedMap(entry: MapEntry) {
        if (entry.isSaved) {
            val file = File(filesDir, "${entry.fileName}.png")

            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)

                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    searchMapHint.visibility = View.GONE
                    edit.visibility = View.VISIBLE
                    share.visibility = View.VISIBLE
                    share.text = "Share"
                } else {
                    image.setImageDrawable(null)
                    searchMapHint.visibility = View.VISIBLE
                    edit.visibility = View.GONE
                    share.visibility = View.VISIBLE
                    share.text = "Share"
                }
            } else {
                image.setImageDrawable(null)
                searchMapHint.visibility = View.VISIBLE
                edit.visibility = View.GONE
                share.visibility = View.VISIBLE
                share.text = "Share"
            }
        } else {
            val resId = resources.getIdentifier(
                entry.fileName,
                "drawable",
                packageName
            )

            if (resId != 0) {
                image.setImageResource(resId)
                searchMapHint.visibility = View.GONE
                edit.visibility = View.VISIBLE
                share.visibility = View.VISIBLE
                share.text = "Load"
            } else {
                image.setImageDrawable(null)
                searchMapHint.visibility = View.VISIBLE
                edit.visibility = View.GONE
                share.visibility = View.VISIBLE
                share.text = "Load"
            }
        }
    }

    private fun getSavedMaps(): List<String> {
        return filesDir.listFiles()
            ?.filter {
                it.name.startsWith("saved_") && it.name.endsWith(".png")
            }
            ?.map {
                it.name.removePrefix("saved_").removeSuffix(".png")
            }
            ?: emptyList()
    }
}