package com.example.warthundermapdraw

import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import java.io.File
import java.io.IOException

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
        val layoutFiles = getLayoutFilesFromDrawableFolder()

        val layoutFilesWithoutPrefix = ArrayList<String>()

        for (filename in layoutFiles) {
            Log.d("cos","cos2")
            val nameWithoutPrefix = filename.replaceFirst(Regex("^map_\\d{3}+px-maplayout_"), "").replace(Regex("_ab[rbsb]?\\.jpg$"),"")
            layoutFilesWithoutPrefix.add(nameWithoutPrefix)

        }
        val mapAdapter = ArrayAdapter(this,android.R.layout.simple_spinner_item,layoutFilesWithoutPrefix)
        mapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMap.adapter=mapAdapter
       /*spinnerMach.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedOption = options[position]
                val optionsMap = when(selectedOption){
                    "TANKS" -> resources.getStringArray(R.array.TankMap)
                    "PLANES" -> resources.getStringArray(R.array.PlaneMap)
                    else -> arrayOf()
                }
                val adapterMap = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, optionsMap)
                adapterMap.setDropDownViewResource(android.R.layout.simple_spinner_item)
                spinnerMap.adapter = adapterMap
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {

            }
        }*/

    }
    private fun getLayoutFilesFromDrawableFolder(): Array<String> {
        val layoutFilesList = ArrayList<String>()
        try {
            val resEntries = resources.assets.list("drawable")
            resEntries?.let { entries ->
                for (entry in entries) {
                    Log.d("cos11","cos")
                    layoutFilesList.add(entry)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return layoutFilesList.toTypedArray()
    }
}