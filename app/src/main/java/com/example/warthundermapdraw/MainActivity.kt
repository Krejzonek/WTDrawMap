package com.example.warthundermapdraw

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

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
       spinnerMach.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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
        }
    }
}