package com.example.warthundermapdraw

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream

class MapViewActivity : AppCompatActivity() {
    private lateinit var mapName: String
    private lateinit var imageView: ImageView
    private lateinit var back: Button
    private lateinit var drawButton: Button
    private lateinit var eraseButton: Button
    private lateinit var saveButton: Button
    private lateinit var mapFrame: FrameLayout
    private lateinit var clearAll: Button
    private var drawingEnabled = false
    private var drawingView: DrawingView? = null
    private var isSavedMap = false

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
        isSavedMap = mapName.startsWith("saved_")

        imageView = findViewById(R.id.mapImageView)
        back = findViewById(R.id.Back)
        drawButton = findViewById(R.id.draw)
        eraseButton = findViewById(R.id.Erser)
        saveButton = findViewById(R.id.Save)
        mapFrame = findViewById(R.id.mapFrame)

        loadImage()

        drawingView = DrawingView(this)
        mapFrame.addView(drawingView)


        eraseButton.setOnClickListener {
            drawingView?.setEraseMode(true)
            drawingEnabled = false
        }

        saveButton.setOnClickListener {
            showSaveDialog()
        }
        clearAll = findViewById(R.id.ClearAll)
        clearAll.setOnClickListener{
            drawingView?.clearDrawing()
        }
        back.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        val colorButtons = findViewById<LinearLayout>(R.id.colorButtons)
        val colorRed = findViewById<View>(R.id.colorRed)
        val colorGreen = findViewById<View>(R.id.colorGreen)
        val colorBlue = findViewById<View>(R.id.colorBlue)

        drawButton.setOnClickListener {
            drawingView?.setEraseMode(false)
            drawingView?.setColor(Color.BLUE)
            colorButtons.visibility = View.VISIBLE
        }

        colorRed.setOnClickListener {
            drawingView?.setColor(android.graphics.Color.RED)
            colorButtons.visibility = View.GONE
        }

        colorGreen.setOnClickListener {
            drawingView?.setColor(android.graphics.Color.GREEN)
            colorButtons.visibility = View.GONE
        }

        colorBlue.setOnClickListener {
            drawingView?.setColor(android.graphics.Color.BLUE)
            colorButtons.visibility = View.GONE
        }

    }

    private fun loadImage() {
        if (isSavedMap) {
            val file = File(filesDir, "$mapName.png")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                imageView.setImageBitmap(bitmap)
            } else {
                Log.e("MapView", "Saved file not found: $mapName.png")
            }
        } else {
            val resId = resources.getIdentifier(mapName, "drawable", packageName)
            if (resId != 0) {
                imageView.setImageResource(resId)
            } else {
                Log.e("MapView", "Drawable not found: $mapName")
            }
        }
    }

    private fun showSaveDialog() {
        val editText = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Save Drawing")
            .setMessage("Enter file name:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString()
                if (name.isNotBlank()) saveDrawing(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveDrawing(fileName: String) {
        val safeName = fileName.trim().replace("[^a-zA-Z0-9_]".toRegex(), "_")
        val bitmap = Bitmap.createBitmap(imageView.width, imageView.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        mapFrame.draw(canvas)


        try {
            val file = File(filesDir, "saved_$safeName.png")
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
            fos.close()
            Toast.makeText(this, "Saved as saved_$safeName.png", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
