package com.example.warthundermapdraw

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerMach: Spinner
    private lateinit var spinnerMap: Spinner
    private lateinit var image: ImageView
    private lateinit var edit: Button
    private lateinit var share: Button
    private lateinit var tacticalMap: ImageButton
    private lateinit var sideMenu: LinearLayout
    private lateinit var menuOverlay: View
    private lateinit var drawMapOption: TextView
    private lateinit var tacticMapOption: TextView

    private lateinit var allMapNames: Array<String>
    private val displayMapNames = mutableListOf<String>()
    private val drawableFileNames = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        spinnerMach = findViewById(R.id.SelectMach)
        spinnerMap = findViewById(R.id.SelectMap)
        image = findViewById(R.id.Map)
        edit = findViewById(R.id.Edit)
        share = findViewById(R.id.Share)
        tacticalMap = findViewById(R.id.TacticalMap)
        sideMenu = findViewById(R.id.SideMenu)
        menuOverlay = findViewById(R.id.MenuOverlay)
        drawMapOption = findViewById(R.id.DrawMapOption)
        tacticMapOption = findViewById(R.id.TacticMapOption)

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

        val machineOptions = resources.getStringArray(R.array.SelectMach)
        allMapNames = resources.getStringArray(R.array.SelectMap)

        val machAdapter = ArrayAdapter(this, R.layout.spinner_item, machineOptions)
        machAdapter.setDropDownViewResource(R.layout.spinner_item)
        spinnerMach.adapter = machAdapter

        val mapAdapter = ArrayAdapter(this, R.layout.spinner_item, displayMapNames)
        mapAdapter.setDropDownViewResource(R.layout.spinner_item)
        spinnerMap.adapter = mapAdapter

        spinnerMach.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedMach = machineOptions[position]

                displayMapNames.clear()
                drawableFileNames.clear()

                if (selectedMach == "Saved") {
                    share.text = "Share"
                    val savedMaps = getSavedMaps()

                    for (name in savedMaps) {
                        displayMapNames.add(name)
                        drawableFileNames.add("saved_$name")
                    }
                } else {
                    share.text = "Load"

                    for (mapName in allMapNames) {
                        if (mapName.lowercase().contains(selectedMach.lowercase())) {
                            val displayName = mapName
                                .replace(Regex("map_\\d{3}px_maplayout_"), "")
                                .substringBeforeLast("_")
                                .replace("_", " ")

                            val drawableName = mapName.lowercase()
                                .replace(" ", "")
                                .replaceAfterLast(".", "")
                                .replace(".", "")

                            displayMapNames.add(displayName)
                            drawableFileNames.add(drawableName)
                        }
                    }
                }

                mapAdapter.notifyDataSetChanged()

                if (displayMapNames.isNotEmpty()) {
                    spinnerMap.setSelection(0)
                    loadImageForSelectedMap(0)
                } else {
                    image.setImageDrawable(null)
                    edit.visibility = View.GONE
                    share.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerMap.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadImageForSelectedMap(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        edit.setOnClickListener {
            val position = spinnerMap.selectedItemPosition

            if (position in drawableFileNames.indices) {
                val intent = Intent(this, MapViewActivity::class.java)
                intent.putExtra("MAP_NAME", drawableFileNames[position])
                startActivity(intent)
            }
        }

        share.setOnClickListener {
            if (share.text == "Share") {
                shareCurrentMap()
            } else {
                loadSharedMap()
            }
        }

        handleCustomLink(intent)
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
        val encoded = data.getQueryParameter("img") ?: return

        try {
            val bytes = Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Invalid image data")

            val fileName = "from_link_${System.currentTimeMillis()}"
            val file = File(filesDir, "saved_$fileName.png")

            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            bitmap.recycle()

            Toast.makeText(this, "Map imported successfully", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Import error", e)
            Toast.makeText(this, "Invalid link or image data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentMap() {
        try {
            val bitmap = getSelectedMapBitmap() ?: run {
                Toast.makeText(this, "Unable to load map", Toast.LENGTH_SHORT).show()
                return
            }

            val output = ByteArrayOutputStream()

            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } finally {
                bitmap.recycle()
            }

            val encoded = Base64.encodeToString(
                output.toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val link = Uri.Builder()
                .scheme("warthundermap")
                .authority("load")
                .appendQueryParameter("img", encoded)
                .build()
                .toString()

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, link)
                type = "text/plain"
            }

            startActivity(Intent.createChooser(sendIntent, "Share map"))
        } catch (e: Exception) {
            Log.e("MainActivity", "Share error", e)
            Toast.makeText(this, "Unable to share map: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getSelectedMapBitmap(): Bitmap? {
        val position = spinnerMap.selectedItemPosition
        if (position !in drawableFileNames.indices) return null

        val fileName = drawableFileNames[position]

        return if (fileName.startsWith("saved_")) {
            BitmapFactory.decodeFile(File(filesDir, "$fileName.png").absolutePath)
        } else {
            val resId = resources.getIdentifier(fileName, "drawable", packageName)
            if (resId == 0) return null

            val options = BitmapFactory.Options().apply {
                inScaled = false
            }

            BitmapFactory.decodeResource(resources, resId, options)
        }
    }

    private fun loadSharedMap() {
        val input = EditText(this).apply {
            hint = "Paste map link"
            textSize = 18f
            setPadding(60, 50, 60, 50)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Load from link")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val uri = Uri.parse(input.text.toString().trim())
                handleCustomLink(Intent().setData(uri))
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun loadImageForSelectedMap(position: Int) {
        if (position !in drawableFileNames.indices) return

        val fileName = drawableFileNames[position]

        if (fileName.startsWith("saved_")) {
            val file = File(filesDir, "$fileName.png")

            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                image.setImageBitmap(bitmap)
                edit.visibility = View.VISIBLE
                share.visibility = View.VISIBLE
            } else {
                image.setImageDrawable(null)
                edit.visibility = View.GONE
                share.visibility = View.GONE
            }
        } else {
            val resId = resources.getIdentifier(fileName, "drawable", packageName)

            if (resId != 0) {
                image.setImageResource(resId)
                edit.visibility = View.VISIBLE
                share.visibility = View.VISIBLE
            } else {
                image.setImageDrawable(null)
                edit.visibility = View.GONE
                share.visibility = View.GONE
            }
        }
    }

    private fun getSavedMaps(): List<String> {
        return filesDir.listFiles()
            ?.filter { it.name.startsWith("saved_") && it.name.endsWith(".png") }
            ?.map { it.name.removePrefix("saved_").removeSuffix(".png") }
            ?: emptyList()
    }
}