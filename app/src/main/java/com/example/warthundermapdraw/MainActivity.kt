package com.example.warthundermapdraw

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import java.io.File
import java.io.IOException
import java.lang.Exception

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val spinnerMach: Spinner = findViewById(R.id.SelectMach)
        val spinnerMap: Spinner = findViewById(R.id.SelectMap)
        val options = resources.getStringArray(R.array.SelectMach)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMach.adapter = adapter


        val layoutFilesList = resources.getStringArray(R.array.SelectMap)

        val filesPath = ArrayList<String>()
        for (file in layoutFilesList){
           val filePath = file.lowercase().replace(" ","")
            filesPath.add(filePath)
        }

        val layoutFilesWithoutPrefix = ArrayList<String>()
        for (filename in layoutFilesList) {
            val nameWithoutPrefix = filename.replace(Regex("map_\\d{3}px_maplayout_"), "").replaceAfterLast("_","").replace("_"," ")
            layoutFilesWithoutPrefix.add(nameWithoutPrefix)
        }
        val image = findViewById<ImageView>(R.id.Map)

        val mapAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, layoutFilesWithoutPrefix)
        mapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMap.adapter = mapAdapter
       spinnerMap.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItemId = position



                val resourcesId = resources.getIdentifier(filesPath[selectedItemId],"drawable",packageName)
                Log.d("drawable",resourcesId.toString())
                if(resourcesId != 0){
                    image.setImageResource(resourcesId)
                }
                else{
                    Log.e("Error","Image not found")
                }
               /* val selectedOption = options[position]
                val optionsMap = when(selectedOption){
                    "TANKS" -> resources.getStringArray(R.array.TankMap)
                    "PLANES" -> resources.getStringArray(R.array.PlaneMap)
                    else -> arrayOf()
                }
                val adapterMap = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, optionsMap)
                adapterMap.setDropDownViewResource(android.R.layout.simple_spinner_item)
                spinnerMap.adapter = adapterMap*/
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {

            }
        }
    }


}