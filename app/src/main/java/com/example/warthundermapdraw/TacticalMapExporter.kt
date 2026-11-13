package com.example.warthundermapdraw

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log

object TacticalMapExporter {

    private const val TAG = "TacticalMapExporter"

    fun export(
        contentResolver: ContentResolver,
        uri: Uri,
        sourceBitmap: Bitmap,
        overlay: MapOverlayView
    ): Boolean {
        var outputBitmap: Bitmap? = null

        return try {
            val displayMapRect = overlay.getMapRect()
                ?: throw IllegalStateException("Map is not ready")

            outputBitmap = Bitmap.createBitmap(
                sourceBitmap.width,
                sourceBitmap.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(outputBitmap)

            canvas.drawBitmap(
                sourceBitmap,
                0f,
                0f,
                Paint(
                    Paint.ANTI_ALIAS_FLAG or
                            Paint.FILTER_BITMAP_FLAG
                )
            )

            val scaleX =
                sourceBitmap.width.toFloat() / displayMapRect.width()

            val scaleY =
                sourceBitmap.height.toFloat() / displayMapRect.height()

            canvas.save()
            canvas.scale(scaleX, scaleY)

            val exportRect = RectF(
                0f,
                0f,
                displayMapRect.width(),
                displayMapRect.height()
            )

            overlay.drawExportOverlay(
                canvas,
                exportRect
            )

            canvas.restore()

            val stream = contentResolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Cannot open output file")

            stream.use {
                val success = outputBitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    it
                )

                if (!success) {
                    throw IllegalStateException("PNG compression failed")
                }
            }

            true

        } catch (e: Exception) {
            Log.e(TAG, "Map export error", e)
            false
        } finally {
            outputBitmap?.recycle()
        }
    }
}