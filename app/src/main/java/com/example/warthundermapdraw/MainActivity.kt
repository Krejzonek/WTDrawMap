package com.example.warthundermapdraw

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
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

    private val firestore by lazy { FirebaseFirestore.getInstance() }

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
            if (share.text == "Share") {
                shareCurrentMap()
            } else {
                loadSharedMap()
            }
        }
    }

    private fun shareCurrentMap() {
        val position = spinnerMap.selectedItemPosition
        if (position !in drawableFileNames.indices) return
        Log.d("MainActivity", "shareCurrentMap() called")
        Toast.makeText(this, "Start sharing", Toast.LENGTH_SHORT).show()
        val fileName = drawableFileNames[position]
        val file = File(filesDir, "$fileName.png")
        if (!file.exists()) {
            Toast.makeText(this, "Plik nie istnieje", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val encoded = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

        val code = "CODE_${System.currentTimeMillis()}"
        val map = hashMapOf("image" to encoded)

        firestore.collection("tactics").document(code).set(map)
            .addOnSuccessListener {
                val dialog = android.app.AlertDialog.Builder(this)
                    .setTitle("Tactic code")
                    .setMessage("Twój kod to:\n\n$code")
                    .setPositiveButton("OK", null)
                    .create()
                dialog.show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Błąd przy zapisie", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadSharedMap() {
        val input = EditText(this).apply {
            hint = "Wprowadź kod"
            textSize = 18f
            setPadding(60, 50, 60, 50)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Wczytaj z kodu")
            .setView(input)
            .setPositiveButton("Wczytaj") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    firestore.collection("tactics").document(code).get()
                        .addOnSuccessListener { doc ->
                            val encoded = doc.getString("image")
                            if (encoded != null) {
                                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                image.setImageBitmap(bitmap)
                                Toast.makeText(this, "Taktika załadowana", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Nie znaleziono obrazu", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Niepowodzenie pobierania", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton("Anuluj", null)
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
