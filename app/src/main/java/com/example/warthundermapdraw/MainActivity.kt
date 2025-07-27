package com.example.warthundermapdraw

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerMach: Spinner
    private lateinit var spinnerMap: Spinner
    private lateinit var image: ImageView
    private lateinit var edit: Button
    private lateinit var share: Button

    private lateinit var allMapNames: Array<String>
    private val displayMapNames = mutableListOf<String>()
    private val drawableFileNames = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerMach = findViewById(R.id.SelectMach)
        spinnerMap = findViewById(R.id.SelectMap)
        image = findViewById(R.id.Map)
        edit = findViewById(R.id.Edit)
        share = findViewById(R.id.Share)

        val machineOptions = resources.getStringArray(R.array.SelectMach)
        allMapNames = resources.getStringArray(R.array.SelectMap)

        val machAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, machineOptions)
        machAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMach.adapter = machAdapter

        val mapAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayMapNames)
        mapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
                            val drawableName = mapName.lowercase().replace(" ", "").replaceAfterLast(".", "").replace(".", "")
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
            val text = share.text.toString()

            if (text == "Share") {

                val currentMap = spinnerMap.selectedItem?.toString() ?: "brak mapy"
                val tacticCode = "KOD_${currentMap.uppercase().replace(" ", "_")}"

                val messageView = EditText(this).apply {
                    setText(tacticCode)
                    isEnabled = false
                    isFocusable = false
                    setTextIsSelectable(true)
                    textSize = 18f
                    setPadding(60, 50, 60, 50)
                }


                val dialog = android.app.AlertDialog.Builder(this)
                    .setTitle("Kod twojej taktyki")
                    .setView(messageView)
                    .setPositiveButton("OK") { dialogInterface, _ ->
                        dialogInterface.dismiss()
                    }
                    .create()
                dialog.show()

            } else if (text == "Load") {
                val input = EditText(this).apply {
                    hint = "kod taktyki"
                    textSize = 18f
                    setPadding(60, 50, 60, 50)
                }


                val dialog = android.app.AlertDialog.Builder(this)
                    .setTitle("Podaj kod taktyki")
                    .setView(input)
                    .setPositiveButton("OK") { dialogInterface, _ ->
                        val enteredCode = input.text.toString()
                        Toast.makeText(this, "Wprowadzony kod: $enteredCode", Toast.LENGTH_SHORT).show()

                        dialogInterface.dismiss()
                    }
                    .create()
                dialog.show()
            }
        }

    }

    private fun loadImageForSelectedMap(position: Int) {
        if (position !in drawableFileNames.indices) return

        val fileName = drawableFileNames[position]

        if (fileName.startsWith("saved_")) {
            val file = File(filesDir, "$fileName.png")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                image.setImageBitmap(bitmap)
            } else {
                Log.e("MainActivity", "Saved file not found: $fileName.png")
                image.setImageDrawable(null)
            }
        } else {
            val resId = resources.getIdentifier(fileName, "drawable", packageName)
            if (resId != 0) {
                image.setImageResource(resId)
            } else {
                Log.e("MainActivity", "Drawable not found: $fileName")
                image.setImageDrawable(null)
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
