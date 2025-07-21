package com.example.warthundermapdraw

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MapViewActivity : AppCompatActivity() {
    private lateinit var mapName: String
    private lateinit var imageView: ImageView
    private lateinit var back: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_map_view)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        mapName = intent.getStringExtra("MAP_NAME") ?: ""
        imageView = findViewById(R.id.mapImageView)
        back = findViewById(R.id.Back)
        val filePath = mapName.lowercase().replace(" ", "")
        val resId = resources.getIdentifier(filePath, "drawable", packageName)
        if (resId != 0) {
            imageView.setImageResource(resId)
        } else {
            Log.e("MapView", "Nie znaleziono zasobu: $filePath")
        }
        back.setOnClickListener{
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }
}