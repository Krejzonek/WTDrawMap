package com.example.warthundermapdraw

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerMach: Spinner
    private lateinit var spinnerMap: Spinner
    private lateinit var image: ImageView
    private lateinit var edit: Button
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
        val machineOptions = resources.getStringArray(R.array.SelectMach)
        allMapNames = resources.getStringArray(R.array.SelectMap)

        edit.setOnClickListener{
            val intent = Intent(this, MapViewActivity::class.java)
            val position = spinnerMap.selectedItemPosition
            intent.putExtra("MAP_NAME", drawableFileNames[position])
            startActivity(intent)
        }

        val machAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, machineOptions)
        machAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMach.adapter = machAdapter


        val mapAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayMapNames)
        mapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMap.adapter = mapAdapter

        spinnerMach.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedMach = machineOptions[position].lowercase()


                displayMapNames.clear()
                drawableFileNames.clear()

                for (mapName in allMapNames) {
                    if (mapName.lowercase().contains(selectedMach)) {
                        val displayName = mapName
                            .replace(Regex("map_\\d{3}px_maplayout_"), "")
                            .substringBeforeLast("_")
                            .replace("_", " ")

                        val drawableName = mapName.lowercase().replace(" ", "").replaceAfterLast(".", "").replace(".", "")
                        displayMapNames.add(displayName)
                        drawableFileNames.add(drawableName)
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
    }

    private fun loadImageForSelectedMap(position: Int) {
        if (position in drawableFileNames.indices) {
            val fileName = drawableFileNames[position]
            val resId = resources.getIdentifier(fileName, "drawable", packageName)

            if (resId != 0) {
                image.setImageResource(resId)
            } else {
                Log.e("Error", "Nie znaleziono obrazka: $fileName")
                image.setImageDrawable(null)
            }
        }
    }
}
