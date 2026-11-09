package com.example.warthundermapdraw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var searchMap: AutoCompleteTextView
    private lateinit var image: ImageView
    private lateinit var tacticOverlay: ImageView
    private lateinit var tacticSpinner: Spinner
    private lateinit var searchMapHint: TextView
    private lateinit var edit: Button
    private lateinit var delete: Button
    private lateinit var mapActions: LinearLayout
    private lateinit var share: Button
    private lateinit var tacticalMap: ImageButton
    private lateinit var sideMenu: LinearLayout
    private lateinit var menuOverlay: View
    private lateinit var drawMapOption: TextView
    private lateinit var tacticMapOption: TextView
    private lateinit var mapAdapter: ArrayAdapter<MapEntry>
    private lateinit var mapLegend: LinearLayout
    private lateinit var mapLegendScroll: HorizontalScrollView
    private lateinit var mapModes: LinearLayout
    private lateinit var mapModesScroll: HorizontalScrollView

    private val mapEntries = mutableListOf<MapEntry>()
    private val tacticIds = mutableListOf<String>()
    private var selectedMap: MapEntry? = null
    private var selectedMapFolder: String? = null
    private var selectedTacticId: String? = null
    private var isChangingTacticSpinner = false

    private val vectorMagic = 0x57544D32

    private data class MapEntry(
        val displayName: String,
        val fileName: String,
        val isSaved: Boolean,
        val assetPath: String = "",
        val mapFolder: String = "",
        val modeName: String = ""
    ) {
        override fun toString(): String = displayName
    }

    private data class MapMode(
        val name: String,
        val assetPath: String,
        val fileName: String
    )

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
        tacticOverlay = findViewById(R.id.MapTacticOverlay)
        tacticSpinner = findViewById(R.id.MapTacticSpinner)
        searchMapHint = findViewById(R.id.SearchMapHint)
        edit = findViewById(R.id.Edit)
        delete = findViewById(R.id.Delete)
        mapActions = findViewById(R.id.MapActions)
        share = findViewById(R.id.Share)
        tacticalMap = findViewById(R.id.TacticalMap)
        sideMenu = findViewById(R.id.SideMenu)
        menuOverlay = findViewById(R.id.MenuOverlay)
        drawMapOption = findViewById(R.id.DrawMapOption)
        tacticMapOption = findViewById(R.id.TacticMapOption)
        mapLegend = findViewById(R.id.MapLegend)
        mapLegendScroll = findViewById(R.id.MapLegendScroll)
        mapModes = findViewById(R.id.MapModes)
        mapModesScroll = findViewById(R.id.MapModesScroll)

        sideMenu.translationX = -280 * resources.displayMetrics.density

        tacticalMap.setOnClickListener { openMenu() }
        menuOverlay.setOnClickListener { closeMenu() }
        drawMapOption.setOnClickListener { closeMenu() }

        tacticMapOption.setOnClickListener {
            closeMenu()
            startActivity(
                Intent(this, TacticalMapActivity::class.java)
                    .putExtra("MAP_NAME", selectedMap?.fileName ?: "")
            )
        }

        mapAdapter = ArrayAdapter(this, R.layout.spinner_item, mapEntries)
        mapAdapter.setDropDownViewResource(R.layout.spinner_item)
        searchMap.setAdapter(mapAdapter)
        searchMap.threshold = 1

        loadMapEntries()

        selectedMap = null
        selectedMapFolder = null
        searchMap.setText("", false)
        image.setImageDrawable(null)
        tacticOverlay.setImageDrawable(null)
        tacticOverlay.visibility = View.GONE
        tacticSpinner.visibility = View.GONE
        searchMapHint.visibility = View.VISIBLE
        mapActions.visibility = View.GONE
        delete.visibility = View.GONE
        share.visibility = View.VISIBLE
        share.text = "Load"
        mapLegendScroll.visibility = View.GONE
        mapLegend.removeAllViews()
        mapModesScroll.visibility = View.GONE
        mapModes.removeAllViews()

        searchMap.setOnClickListener {
            searchMap.showDropDown()
        }

        searchMap.setOnItemClickListener { parent, _, position, _ ->
            val entry = parent.getItemAtPosition(position) as MapEntry

            if (entry.isSaved) {
                selectedMapFolder = null
                mapModesScroll.visibility = View.GONE
                selectMap(entry)
            } else {
                showMapModes(entry)
            }

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchMap.windowToken, 0)
            searchMap.clearFocus()
        }

        searchMap.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) searchMap.showDropDown()
        }

        tacticSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isChangingTacticSpinner || position !in tacticIds.indices) return
                selectedTacticId = tacticIds[position]
                loadSelectedTacticOverlay()
            }
        }

        edit.setOnClickListener {
            val entry = selectedMap ?: return@setOnClickListener

            val intent = Intent(this, MapViewActivity::class.java)
            intent.putExtra("MAP_NAME", entry.fileName)
            intent.putExtra("MAP_ASSET_PATH", entry.assetPath)
            intent.putExtra("ACTIVE_TACTIC_ID", selectedTacticId)
            startActivity(intent)
        }

        delete.setOnClickListener {
            deleteSelectedMap()
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

        val folders = assets.list("maps2d") ?: emptyArray()

        for (folder in folders.sorted()) {
            val modes = assets.list("maps2d/$folder") ?: emptyArray()

            if (modes.isEmpty()) continue

            mapEntries.add(
                MapEntry(
                    displayName = folder.replace("_", " "),
                    fileName = folder,
                    isSaved = false,
                    mapFolder = folder
                )
            )
        }

        for (name in getSavedMaps()) {
            mapEntries.add(
                MapEntry(
                    displayName = "Saved: ${name.replace("_", " ")}",
                    fileName = "saved_$name",
                    isSaved = true
                )
            )
        }

        mapAdapter.notifyDataSetChanged()
    }

    private fun refreshMapSearch() {
        loadMapEntries()
        searchMap.setText("", false)
        searchMap.dismissDropDown()
    }

    private fun getModesForMap(folder: String): List<MapMode> {
        val result = mutableListOf<MapMode>()
        val modeFolders = assets.list("maps2d/$folder") ?: return result

        for (mode in modeFolders.sorted()) {
            val modePath = "maps2d/$folder/$mode"
            val files = assets.list(modePath) ?: continue

            val imageFile = files.firstOrNull {
                it.endsWith(".png", true)
            } ?: continue

            val assetPath = "$modePath/$imageFile"
            val safeId = "${folder}_${mode}_${imageFile.substringBeforeLast(".")}"
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")

            result.add(
                MapMode(
                    name = mode.replace("_", " "),
                    assetPath = assetPath,
                    fileName = safeId
                )
            )
        }

        return result
    }

    private fun showMapModes(entry: MapEntry) {
        selectedMap = null
        selectedMapFolder = entry.mapFolder

        searchMap.setText(entry.displayName, false)

        mapModes.removeAllViews()
        mapModesScroll.visibility = View.VISIBLE

        image.setImageDrawable(null)
        tacticOverlay.setImageDrawable(null)
        tacticOverlay.visibility = View.GONE
        tacticSpinner.visibility = View.GONE
        mapActions.visibility = View.GONE
        mapLegendScroll.visibility = View.GONE
        mapLegend.removeAllViews()
        searchMapHint.visibility = View.VISIBLE
        searchMapHint.text = "Select a game mode"

        val modes = getModesForMap(entry.mapFolder)

        if (modes.isEmpty()) {
            mapModesScroll.visibility = View.GONE
            Toast.makeText(this, "No maps found for this location", Toast.LENGTH_SHORT).show()
            return
        }

        val density = resources.displayMetrics.density

        for (mode in modes) {
            val button = Button(this).apply {
                text = mode.name
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColor(R.color.dark_button)
                )
                setOnClickListener {
                    val selected = MapEntry(
                        displayName = "${entry.displayName} - ${mode.name}",
                        fileName = mode.fileName,
                        isSaved = false,
                        assetPath = mode.assetPath,
                        mapFolder = entry.mapFolder,
                        modeName = mode.name
                    )

                    selectMap(selected)
                }
            }

            mapModes.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (44 * density).toInt()
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
            )
        }
    }

    private fun selectMap(entry: MapEntry) {
        selectedMap = entry
        selectedTacticId = null
        searchMap.setText(entry.displayName, false)

        searchMapHint.visibility = View.GONE
        searchMapHint.text = "Search for a map"

        share.text = if (entry.isSaved) "Share" else "Load"
        mapActions.visibility = View.VISIBLE
        delete.visibility = if (entry.isSaved) View.VISIBLE else View.GONE
        share.visibility = View.VISIBLE

        tacticOverlay.setImageDrawable(null)
        tacticOverlay.visibility = View.GONE
        tacticSpinner.visibility = View.GONE
        tacticIds.clear()

        loadImageForSelectedMap(entry)
        loadMapLegend(entry)

        if (entry.isSaved) {
            loadTacticOptions(entry)
        } else {
            tacticSpinner.visibility = View.GONE
        }
    }

    private fun deleteSelectedMap() {
        val entry = selectedMap ?: return
        if (!entry.isSaved) return

        android.app.AlertDialog.Builder(this)
            .setTitle("Delete map?")
            .setMessage("Are you sure you want to permanently delete \"${entry.displayName}\" and all its tactics?")
            .setPositiveButton("Delete") { _, _ ->
                val fileName = entry.fileName
                val mainFile = File(filesDir, "$fileName.png")

                val mapFiles = filesDir.listFiles()?.filter {
                    it.name == mainFile.name || it.name.startsWith("$fileName.")
                } ?: emptyList()

                val failedFiles = mutableListOf<String>()

                for (file in mapFiles) {
                    if (file.exists() && !file.delete()) {
                        failedFiles.add(file.name)
                        Log.e("DELETE_MAP", "Unable to delete: ${file.absolutePath}")
                    }
                }

                if (mainFile.exists()) {
                    Toast.makeText(this, "Unable to delete map file", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                selectedMap = null
                selectedMapFolder = null
                selectedTacticId = null
                tacticIds.clear()

                searchMap.dismissDropDown()
                searchMap.setText("", false)
                image.setImageDrawable(null)
                tacticOverlay.setImageDrawable(null)
                tacticOverlay.visibility = View.GONE
                tacticSpinner.visibility = View.GONE
                searchMapHint.visibility = View.VISIBLE
                searchMapHint.text = "Search for a map"
                mapActions.visibility = View.GONE
                delete.visibility = View.GONE
                share.visibility = View.VISIBLE
                share.text = "Load"
                mapLegendScroll.visibility = View.GONE
                mapLegend.removeAllViews()
                mapModesScroll.visibility = View.GONE
                mapModes.removeAllViews()

                refreshMapSearch()

                Toast.makeText(
                    this,
                    if (failedFiles.isEmpty()) "Map deleted successfully"
                    else "Map deleted, but some additional files could not be removed",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadTacticOptions(entry: MapEntry) {
        val file = File(filesDir, "${entry.fileName}.tactics.json")
        if (!file.exists()) return

        try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("tactics")

            tacticIds.clear()
            val names = mutableListOf<String>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.getString("id")
                val name = item.getString("name")

                if (!id.matches(Regex("[a-zA-Z0-9_-]+"))) continue

                tacticIds.add(id)
                names.add(name)
            }

            if (tacticIds.isEmpty()) return

            val activeIndex = json.optInt("active", 0).coerceIn(0, tacticIds.lastIndex)
            selectedTacticId = tacticIds[activeIndex]

            val adapter = ArrayAdapter(this, R.layout.spinner_item, names)
            adapter.setDropDownViewResource(R.layout.spinner_item)

            isChangingTacticSpinner = true
            tacticSpinner.adapter = adapter
            tacticSpinner.setSelection(activeIndex, false)
            tacticSpinner.visibility = View.VISIBLE
            isChangingTacticSpinner = false

            loadSelectedTacticOverlay()
        } catch (e: Exception) {
            Log.e("MainActivity", "Tactic loading error", e)
            tacticIds.clear()
            tacticSpinner.visibility = View.GONE
            isChangingTacticSpinner = false
        }
    }

    private fun testQrCode(bitmap: Bitmap, expectedLink: String) {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = BarcodeScanning.getClient(options)
        val testBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val image = InputImage.fromBitmap(testBitmap, 0)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val result = barcodes.firstOrNull()?.rawValue

                if (result == null) {
                    Log.e("QR_TEST", "QR code not detected")
                    Toast.makeText(this, "QR code not detected", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        if (result == expectedLink) "QR code is valid!" else "QR link differs!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .addOnFailureListener {
                Log.e("QR_TEST", "Scanning failed", it)
            }
            .addOnCompleteListener {
                scanner.close()
                testBitmap.recycle()
            }
    }

    private fun loadSelectedTacticOverlay() {
        val entry = selectedMap ?: return
        val tacticId = selectedTacticId ?: return

        val bitmapFile = File(filesDir, "${entry.fileName}.$tacticId.png")
        val vectorFile = File(filesDir, "${entry.fileName}.$tacticId.json")

        val bitmap = when {
            bitmapFile.exists() -> BitmapFactory.decodeFile(bitmapFile.absolutePath)
            vectorFile.exists() -> {
                val base = getMapBaseBitmap(entry.fileName)
                if (base != null) {
                    val result = createVectorOverlay(vectorFile, base.width, base.height)
                    base.recycle()
                    result
                } else null
            }
            else -> null
        }

        if (bitmap != null) {
            tacticOverlay.setImageBitmap(bitmap)
            tacticOverlay.visibility = View.VISIBLE
        } else {
            tacticOverlay.setImageDrawable(null)
            tacticOverlay.visibility = View.GONE
        }
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

        try {
            val packageData = data.getQueryParameter("pkg")

            showImportNameDialog { savedFileName ->
                try {
                    if (!packageData.isNullOrBlank()) {
                        importTacticPackage(packageData, savedFileName)
                    } else {
                        importLegacyMap(data, savedFileName)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Import error", e)
                    Toast.makeText(this, "Invalid link or map data", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Import error", e)
            Toast.makeText(this, "Invalid link or map data", Toast.LENGTH_LONG).show()
        }
    }

    private fun showImportNameDialog(onConfirm: (String) -> Unit) {
        val density = resources.displayMetrics.density

        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Enter map name"
            setText("Imported Map")
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (8 * density).toInt(),
                (20 * density).toInt(),
                (4 * density).toInt()
            )
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Name imported map")
            .setMessage("Choose a name for this map and its tactics.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()

                if (name.isBlank()) {
                    input.error = "Enter a map name"
                    return@setOnClickListener
                }

                val savedFileName = createUniqueMapFileName(name)
                dialog.dismiss()
                onConfirm(savedFileName)
            }
        }

        dialog.show()
    }

    private fun createUniqueMapFileName(name: String): String {
        var baseName = name
            .trim()
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')

        if (baseName.isBlank()) baseName = "Imported_Map"

        val existingNames = getSavedMaps().toSet()
        var result = baseName
        var counter = 2

        while (result in existingNames || File(filesDir, "saved_$result.png").exists()) {
            result = "${baseName}_$counter"
            counter++
        }

        return "saved_$result"
    }

    private fun importLegacyMap(data: Uri, savedFileName: String) {
        val encoded = data.getQueryParameter("img") ?: return

        val bytes = Base64.decode(
            encoded,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Invalid image data")

        File(filesDir, "$savedFileName.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        bitmap.recycle()

        val encodedLegend = data.getQueryParameter("legend")

        if (!encodedLegend.isNullOrBlank()) {
            val legendBytes = Base64.decode(
                encodedLegend,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            File(filesDir, "$savedFileName.legend").writeText(
                String(legendBytes, Charsets.UTF_8)
            )
        }

        loadMapEntries()

        mapEntries.firstOrNull { it.fileName == savedFileName }?.let {
            selectMap(it)
        }

        Toast.makeText(this, "Map imported successfully", Toast.LENGTH_LONG).show()
    }

    private fun importTacticPackage(encoded: String, savedFileName: String) {
        val bytes = Base64.decode(
            encoded,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                val name = entry.name

                val validEntry = name == "base.png" ||
                        name == "source.txt" ||
                        name == "legend.txt" ||
                        name == "tactics.json" ||
                        name.matches(Regex("tactics/[a-zA-Z0-9_-]+\\.(png|json|bin)"))

                if (validEntry && !entry.isDirectory) {
                    val outputName = when {
                        name == "base.png" -> "$savedFileName.base.png"
                        name == "source.txt" -> "$savedFileName.source"
                        name == "legend.txt" -> "$savedFileName.legend"
                        name == "tactics.json" -> "$savedFileName.tactics.json"
                        name.startsWith("tactics/") -> {
                            val tacticFile = name.removePrefix("tactics/")
                            "$savedFileName.${tacticFile.substringBeforeLast(".")}.${tacticFile.substringAfterLast(".")}"
                        }
                        else -> null
                    }

                    if (outputName != null) {
                        File(filesDir, outputName).outputStream().use { output ->
                            zip.copyTo(output)
                        }
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val metadataFile = File(filesDir, "$savedFileName.tactics.json")
        if (!metadataFile.exists()) {
            throw IllegalArgumentException("Missing tactics metadata")
        }

        val metadata = JSONObject(metadataFile.readText())
        val tactics = metadata.getJSONArray("tactics")

        for (i in 0 until tactics.length()) {
            val id = tactics.getJSONObject(i).getString("id")
            if (!id.matches(Regex("[a-zA-Z0-9_-]+"))) continue

            val binaryFile = File(filesDir, "$savedFileName.$id.bin")
            val vectorFile = File(filesDir, "$savedFileName.$id.json")

            if (binaryFile.exists()) {
                val vectorData = decodeVectorActions(binaryFile.readBytes())
                vectorFile.writeText(vectorData.toString())
                binaryFile.delete()
            }
        }

        val base = getMapBaseBitmap(savedFileName)
            ?: throw IllegalArgumentException("Missing or invalid base map")

        try {
            for (i in 0 until tactics.length()) {
                val id = tactics.getJSONObject(i).getString("id")
                if (!id.matches(Regex("[a-zA-Z0-9_-]+"))) continue

                val vectorFile = File(filesDir, "$savedFileName.$id.json")
                val bitmapFile = File(filesDir, "$savedFileName.$id.png")

                if (vectorFile.exists() && !bitmapFile.exists()) {
                    val overlay = createVectorOverlay(vectorFile, base.width, base.height)

                    if (overlay != null) {
                        File(filesDir, "$savedFileName.$id.png").outputStream().use {
                            overlay.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        overlay.recycle()
                    }
                }
            }
        } finally {
            base.recycle()
        }

        createFlattenedPreview(savedFileName)
        loadMapEntries()

        mapEntries.firstOrNull { it.fileName == savedFileName }?.let {
            selectMap(it)
        }

        Toast.makeText(this, "Map imported successfully", Toast.LENGTH_LONG).show()
    }

    private fun getMapBaseBitmap(fileName: String): Bitmap? {
        val baseFile = File(filesDir, "$fileName.base.png")

        if (baseFile.exists()) {
            return BitmapFactory.decodeFile(baseFile.absolutePath)
        }

        val sourceFile = File(filesDir, "$fileName.source")

        if (sourceFile.exists()) {
            val sourceName = sourceFile.readText().trim()

            try {
                assets.open(sourceName).use {
                    return BitmapFactory.decodeStream(it)
                }
            } catch (_: Exception) {}

            val resId = resources.getIdentifier(sourceName, "drawable", packageName)

            if (resId != 0) {
                val options = BitmapFactory.Options().apply {
                    inScaled = false
                }

                return BitmapFactory.decodeResource(resources, resId, options)
            }
        }

        val previewFile = File(filesDir, "$fileName.png")

        if (previewFile.exists()) {
            return BitmapFactory.decodeFile(previewFile.absolutePath)
        }

        return null
    }

    private fun createFlattenedPreview(fileName: String) {
        val base = getMapBaseBitmap(fileName)
            ?: throw IllegalArgumentException("Invalid base map")

        val preview = base.copy(Bitmap.Config.ARGB_8888, true)
        base.recycle()

        try {
            val metadataFile = File(filesDir, "$fileName.tactics.json")

            if (metadataFile.exists()) {
                val metadata = JSONObject(metadataFile.readText())
                val tactics = metadata.optJSONArray("tactics") ?: JSONArray()

                if (tactics.length() > 0) {
                    val active = metadata.optInt("active", 0).coerceIn(0, tactics.length() - 1)
                    val id = tactics.getJSONObject(active).getString("id")
                    val overlayFile = File(filesDir, "$fileName.$id.png")

                    if (overlayFile.exists()) {
                        val overlay = BitmapFactory.decodeFile(overlayFile.absolutePath)

                        if (overlay != null) {
                            Canvas(preview).drawBitmap(
                                overlay,
                                0f,
                                0f,
                                Paint(Paint.FILTER_BITMAP_FLAG)
                            )
                            overlay.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Preview creation error", e)
        }

        File(filesDir, "$fileName.png").outputStream().use {
            preview.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        preview.recycle()
    }

    private fun createVectorOverlay(file: File, width: Int, height: Int): Bitmap? {
        if (!file.exists() || width <= 0 || height <= 0) return null

        return try {
            val data = JSONArray(file.readText())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val scale = minOf(width, height).toFloat()

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val clear = item.optBoolean("clear", false)
                val points = item.optJSONArray("points") ?: JSONArray()

                if (points.length() == 0) {
                    if (clear) bitmap.eraseColor(Color.TRANSPARENT)
                    continue
                }

                val path = Path()
                var hasFirstPoint = false

                for (j in 0 until points.length()) {
                    val point = points.optJSONArray(j) ?: continue
                    if (point.length() < 2) continue

                    val x = (point.optDouble(0) * width).toFloat()
                    val y = (point.optDouble(1) * height).toFloat()

                    if (!x.isFinite() || !y.isFinite()) continue

                    if (!hasFirstPoint) {
                        path.moveTo(x, y)
                        hasFirstPoint = true
                    } else {
                        path.lineTo(x, y)
                    }
                }

                if (!hasFirstPoint) continue

                val paint = Paint().apply {
                    color = item.optInt("color", Color.BLUE)
                    strokeWidth = item.optDouble("stroke", 0.01).toFloat() * scale
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND

                    if (clear) {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    }
                }

                canvas.drawPath(path, paint)
            }

            bitmap
        } catch (e: Exception) {
            Log.e("MainActivity", "Vector overlay error", e)
            null
        }
    }

    private fun writeVarInt(output: DataOutputStream, value: Int) {
        var number = value
        while ((number and -128) != 0) {
            output.writeByte((number and 127) or 128)
            number = number ushr 7
        }
        output.writeByte(number)
    }

    private fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0

        while (shift < 35) {
            val value = input.readUnsignedByte()
            result = result or ((value and 127) shl shift)

            if ((value and 128) == 0) return result

            shift += 7
        }

        throw IllegalArgumentException("Invalid variable integer")
    }

    private fun zigZagEncode(value: Int): Int {
        return (value shl 1) xor (value shr 31)
    }

    private fun zigZagDecode(value: Int): Int {
        return (value ushr 1) xor -(value and 1)
    }

    private fun encodeVectorActions(file: File): ByteArray? {
        if (!file.exists()) return null

        return try {
            val actions = JSONArray(file.readText())
            if (actions.length() == 0) return null

            val output = ByteArrayOutputStream()
            val data = DataOutputStream(output)

            data.writeInt(vectorMagic)
            writeVarInt(data, actions.length())

            for (i in 0 until actions.length()) {
                val item = actions.getJSONObject(i)
                val clear = item.optBoolean("clear", false)
                val color = item.optInt("color", Color.BLUE)
                val stroke = item.optDouble("stroke", 0.01).toFloat()
                val points = item.optJSONArray("points") ?: JSONArray()

                data.writeByte(if (clear) 1 else 0)
                data.writeInt(color)
                data.writeFloat(stroke)
                writeVarInt(data, points.length())

                var previousX = 0
                var previousY = 0

                for (j in 0 until points.length()) {
                    val point = points.optJSONArray(j) ?: JSONArray()

                    val x = (point.optDouble(0, 0.0).coerceIn(0.0, 1.0) * 65535.0).toInt()
                    val y = (point.optDouble(1, 0.0).coerceIn(0.0, 1.0) * 65535.0).toInt()

                    writeVarInt(data, zigZagEncode(x - previousX))
                    writeVarInt(data, zigZagEncode(y - previousY))

                    previousX = x
                    previousY = y
                }
            }

            data.flush()
            data.close()

            output.toByteArray()
        } catch (e: Exception) {
            Log.e("MainActivity", "Vector encoding error", e)
            null
        }
    }

    private fun decodeVectorActions(bytes: ByteArray): JSONArray {
        val result = JSONArray()

        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val magic = data.readInt()

            if (magic == 0x57544D31) {
                val actionCount = data.readInt()

                if (actionCount < 0 || actionCount > 100000) {
                    throw IllegalArgumentException("Invalid action count")
                }

                for (i in 0 until actionCount) {
                    val clear = data.readUnsignedByte() != 0
                    val color = data.readInt()
                    val stroke = data.readFloat()
                    val pointCount = data.readInt()

                    if (pointCount < 0 || pointCount > 2000000) {
                        throw IllegalArgumentException("Invalid point count")
                    }

                    val points = JSONArray()

                    for (j in 0 until pointCount) {
                        val x = data.readUnsignedShort() / 65535.0
                        val y = data.readUnsignedShort() / 65535.0
                        points.put(JSONArray().put(x).put(y))
                    }

                    result.put(
                        JSONObject()
                            .put("clear", clear)
                            .put("color", color)
                            .put("stroke", stroke.toDouble())
                            .put("points", points)
                    )
                }
            } else if (magic == vectorMagic) {
                val actionCount = readVarInt(data)

                if (actionCount < 0 || actionCount > 100000) {
                    throw IllegalArgumentException("Invalid action count")
                }

                for (i in 0 until actionCount) {
                    val clear = data.readUnsignedByte() != 0
                    val color = data.readInt()
                    val stroke = data.readFloat()
                    val pointCount = readVarInt(data)

                    if (pointCount < 0 || pointCount > 2000000) {
                        throw IllegalArgumentException("Invalid point count")
                    }

                    val points = JSONArray()
                    var previousX = 0
                    var previousY = 0

                    for (j in 0 until pointCount) {
                        previousX += zigZagDecode(readVarInt(data))
                        previousY += zigZagDecode(readVarInt(data))

                        if (previousX !in 0..65535 || previousY !in 0..65535) {
                            throw IllegalArgumentException("Invalid vector coordinates")
                        }

                        val x = previousX / 65535.0
                        val y = previousY / 65535.0

                        points.put(JSONArray().put(x).put(y))
                    }

                    result.put(
                        JSONObject()
                            .put("clear", clear)
                            .put("color", color)
                            .put("stroke", stroke.toDouble())
                            .put("points", points)
                    )
                }
            } else {
                throw IllegalArgumentException("Invalid vector format")
            }

            if (data.available() != 0) {
                throw IllegalArgumentException("Unexpected vector data")
            }
        }

        return result
    }

    private fun shareCurrentMap() {
        try {
            val link = createMapLink() ?: return
            val qrBitmap = generateQrCode(link)

            testQrCode(qrBitmap, link)
            showShareDialog(link, qrBitmap)
        } catch (e: WriterException) {
            Log.e("MainActivity", "QR generation error", e)
            Toast.makeText(
                this,
                "Map and tactics are too large for one QR code. Reduce the map size or drawings.",
                Toast.LENGTH_LONG
            ).show()
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
        if (!entry.isSaved) return null

        val archive = createTacticPackage(entry.fileName) ?: return null

        val encoded = Base64.encodeToString(
            archive,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        return Uri.Builder()
            .scheme("warthundermap")
            .authority("load")
            .appendQueryParameter("pkg", encoded)
            .build()
            .toString()
    }

    private fun createTacticPackage(fileName: String): ByteArray? {
        val output = ByteArrayOutputStream()

        try {
            ZipOutputStream(output).use { zip ->
                val sourceFile = File(filesDir, "$fileName.source")
                val sourceName = if (sourceFile.exists()) {
                    sourceFile.readText().trim()
                } else ""

                val sourceRes = if (sourceName.isNotBlank()) {
                    resources.getIdentifier(sourceName, "drawable", packageName)
                } else 0

                if (sourceRes != 0) {
                    zip.putNextEntry(ZipEntry("source.txt"))
                    zip.write(sourceName.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                } else {
                    val baseFile = File(filesDir, "$fileName.base.png")
                    val previewFile = File(filesDir, "$fileName.png")
                    val sourceBase = if (baseFile.exists()) baseFile else previewFile

                    if (!sourceBase.exists()) {
                        Toast.makeText(this, "Map image not found", Toast.LENGTH_SHORT).show()
                        return null
                    }

                    addFileToZip(zip, sourceBase, "base.png")
                }

                val legendFile = File(filesDir, "$fileName.legend")
                if (legendFile.exists()) {
                    addFileToZip(zip, legendFile, "legend.txt")
                }

                val metadataFile = File(filesDir, "$fileName.tactics.json")
                val metadata = if (metadataFile.exists()) {
                    JSONObject(metadataFile.readText())
                } else {
                    JSONObject().apply {
                        put(
                            "tactics",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("id", "t0")
                                    put("name", "Tactic 1")
                                }
                            )
                        )
                        put("active", 0)
                    }
                }

                zip.putNextEntry(ZipEntry("tactics.json"))
                zip.write(metadata.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                val tactics = metadata.optJSONArray("tactics") ?: JSONArray()

                for (i in 0 until tactics.length()) {
                    val id = tactics.getJSONObject(i).getString("id")
                    if (!id.matches(Regex("[a-zA-Z0-9_-]+"))) continue

                    val vectorFile = File(filesDir, "$fileName.$id.json")
                    val bitmapFile = File(filesDir, "$fileName.$id.png")

                    val binaryData = if (vectorFile.exists()) {
                        encodeVectorActions(vectorFile)
                    } else null

                    if (binaryData != null && binaryData.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("tactics/$id.bin"))
                        zip.write(binaryData)
                        zip.closeEntry()
                    } else if (bitmapFile.exists()) {
                        addFileToZip(zip, bitmapFile, "tactics/$id.png")
                    } else if (vectorFile.exists()) {
                        addFileToZip(zip, vectorFile, "tactics/$id.json")
                    }
                }
            }

            return output.toByteArray()
        } catch (e: Exception) {
            Log.e("MainActivity", "Package creation error", e)
            Toast.makeText(this, "Unable to prepare map package", Toast.LENGTH_SHORT).show()
            return null
        }
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun generateQrCode(link: String): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 4
        )

        val matrix = QRCodeWriter().encode(
            link,
            BarcodeFormat.QR_CODE,
            900,
            900,
            hints
        )

        val size = 900
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = minOf(size / matrix.width, size / matrix.height)
        val offsetX = (size - matrix.width * scale) / 2
        val offsetY = (size - matrix.height * scale) / 2

        bitmap.eraseColor(Color.WHITE)

        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) {
                    for (dx in 0 until scale) {
                        for (dy in 0 until scale) {
                            bitmap.setPixel(
                                offsetX + x * scale + dx,
                                offsetY + y * scale + dy,
                                Color.BLACK
                            )
                        }
                    }
                }
            }
        }

        return bitmap
    }

    private fun showShareDialog(link: String, qrBitmap: Bitmap) {
        val density = resources.displayMetrics.density
        val screenWidth = resources.configuration.screenWidthDp
        val screenHeight = resources.configuration.screenHeightDp

        val dialogWidth = (screenWidth - 32).coerceAtLeast(240)
        val maxDialogHeight = (screenHeight * 0.85f).toInt()
        val qrSize = minOf(360, dialogWidth - 48, (maxDialogHeight * 0.48f).toInt()).coerceAtLeast(120)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
        }

        val qrImage = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
        }

        container.addView(
            qrImage,
            LinearLayout.LayoutParams(
                (qrSize * density).toInt(),
                (qrSize * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        val description = TextView(this).apply {
            text = "Scan this QR code to import the map, all tactics and the legend"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
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
            maxLines = 5
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
                clipboard.setPrimaryClip(ClipData.newPlainText("War Thunder Map", link))

                Toast.makeText(this@MainActivity, "Link copied", Toast.LENGTH_SHORT).show()
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

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Share map")
            .setView(scrollView)
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnDismissListener {
            if (!qrBitmap.isRecycled) qrBitmap.recycle()
        }

        dialog.show()

        dialog.window?.apply {
            setLayout(
                (dialogWidth * density).toInt(),
                (maxDialogHeight * density).toInt()
            )
            setBackgroundDrawableResource(android.R.color.background_light)
        }
    }

    private fun loadSharedMap() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue

                if (value.isNullOrBlank()) {
                    Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                handleCustomLink(Intent().setData(Uri.parse(value)))
            }
            .addOnCanceledListener {
                Toast.makeText(this, "Scanning cancelled", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "QR scan error", e)
                Toast.makeText(
                    this,
                    "Unable to scan QR code: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun loadImageForSelectedMap(entry: MapEntry) {
        if (entry.isSaved) {
            val baseFile = File(filesDir, "${entry.fileName}.base.png")
            val sourceFile = File(filesDir, "${entry.fileName}.source")
            val previewFile = File(filesDir, "${entry.fileName}.png")

            when {
                baseFile.exists() -> {
                    val bitmap = BitmapFactory.decodeFile(baseFile.absolutePath)

                    if (bitmap != null) {
                        image.setImageBitmap(bitmap)
                        searchMapHint.visibility = View.GONE
                        edit.visibility = View.VISIBLE
                    } else {
                        image.setImageDrawable(null)
                        searchMapHint.visibility = View.VISIBLE
                        edit.visibility = View.GONE
                    }
                }

                sourceFile.exists() -> {
                    val sourceName = sourceFile.readText().trim()

                    val bitmap = try {
                        assets.open(sourceName).use {
                            BitmapFactory.decodeStream(it)
                        }
                    } catch (_: Exception) {
                        null
                    }

                    if (bitmap != null) {
                        image.setImageBitmap(bitmap)
                        searchMapHint.visibility = View.GONE
                        edit.visibility = View.VISIBLE
                    } else {
                        val resId = resources.getIdentifier(sourceName, "drawable", packageName)

                        if (resId != 0) {
                            image.setImageResource(resId)
                            searchMapHint.visibility = View.GONE
                            edit.visibility = View.VISIBLE
                        } else if (previewFile.exists()) {
                            val preview = BitmapFactory.decodeFile(previewFile.absolutePath)

                            if (preview != null) {
                                image.setImageBitmap(preview)
                                searchMapHint.visibility = View.GONE
                                edit.visibility = View.VISIBLE
                            } else {
                                image.setImageDrawable(null)
                                searchMapHint.visibility = View.VISIBLE
                                edit.visibility = View.GONE
                            }
                        } else {
                            image.setImageDrawable(null)
                            searchMapHint.visibility = View.VISIBLE
                            edit.visibility = View.GONE
                        }
                    }
                }

                previewFile.exists() -> {
                    val bitmap = BitmapFactory.decodeFile(previewFile.absolutePath)

                    if (bitmap != null) {
                        image.setImageBitmap(bitmap)
                        searchMapHint.visibility = View.GONE
                        edit.visibility = View.VISIBLE
                    } else {
                        image.setImageDrawable(null)
                        searchMapHint.visibility = View.VISIBLE
                        edit.visibility = View.GONE
                    }
                }

                else -> {
                    image.setImageDrawable(null)
                    searchMapHint.visibility = View.VISIBLE
                    edit.visibility = View.GONE
                }
            }

            share.visibility = View.VISIBLE
            share.text = "Share"
        } else {
            val bitmap = try {
                assets.open(entry.assetPath).use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Map asset load failed: ${entry.assetPath}", e)
                null
            }

            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                searchMapHint.visibility = View.GONE
                edit.visibility = View.VISIBLE
            } else {
                image.setImageDrawable(null)
                searchMapHint.visibility = View.VISIBLE
                edit.visibility = View.GONE
                Toast.makeText(this, "Map image could not be loaded", Toast.LENGTH_SHORT).show()
            }

            share.visibility = View.VISIBLE
            share.text = "Load"
        }
    }

    private fun getSavedMaps(): List<String> {
        return filesDir.listFiles()
            ?.filter {
                it.isFile && it.name.matches(Regex("saved_[a-zA-Z0-9_]+\\.png"))
            }
            ?.map {
                it.name.removePrefix("saved_").removeSuffix(".png")
            }
            ?: emptyList()
    }
}