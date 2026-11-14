package com.example.warthundermapdraw

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.Log

object TacticalMapExporter {

    private const val TAG = "TacticalMapExporter"

    fun export(
        contentResolver: ContentResolver,
        uri: Uri,
        sourceBitmap: Bitmap,
        overlay: MapOverlayView,
        drawingEntries: List<MapDrawingManager.LegendEntry>
    ): Boolean {

        var outputBitmap: Bitmap? = null

        return try {
            val displayMapRect = overlay.getMapRect()
                ?: throw IllegalStateException("Map is not ready")

            val width = sourceBitmap.width

            val padding = (width * 0.025f).coerceAtLeast(12f)
            val legendTextSize = (width * 0.018f).coerceIn(13f, 25f)
            val swatchSize = legendTextSize * 0.9f
            val textGap = legendTextSize * 0.55f
            val itemGap = legendTextSize * 1.1f
            val rowHeight = legendTextSize * 1.9f
            val legendTitleSize = legendTextSize * 1.15f

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(220, 232, 245)
                textSize = legendTextSize
                typeface = Typeface.DEFAULT_BOLD
            }

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(114, 183, 245)
                textSize = legendTitleSize
                typeface = Typeface.DEFAULT_BOLD
            }

            val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(17, 26, 39)
            }

            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(48, 67, 89)
                strokeWidth = (width * 0.0015f).coerceAtLeast(1f)
            }

            val availableWidth = width - padding * 2f

            fun label(entry: MapDrawingManager.LegendEntry): String {
                return if (entry.description.isBlank()) {
                    entry.colorName
                } else {
                    "${entry.colorName} — ${entry.description}"
                }
            }

            fun itemWidth(entry: MapDrawingManager.LegendEntry): Float {
                return swatchSize +
                        textGap +
                        textPaint.measureText(label(entry)) +
                        itemGap
            }

            fun rowCount(): Int {
                if (drawingEntries.isEmpty()) return 0

                var rows = 1
                var currentX = 0f

                for (entry in drawingEntries) {
                    val entryWidth = itemWidth(entry)

                    if (
                        currentX > 0f &&
                        currentX + entryWidth > availableWidth
                    ) {
                        rows++
                        currentX = 0f
                    }

                    currentX += entryWidth
                }

                return rows
            }

            val footerHeight = if (drawingEntries.isEmpty()) {
                0
            } else {
                (
                        padding * 2f +
                                legendTitleSize * 1.8f +
                                rowCount() * rowHeight
                        ).toInt()
            }

            outputBitmap = Bitmap.createBitmap(
                width,
                sourceBitmap.height + footerHeight,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(outputBitmap!!)

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

            overlay.drawExportOverlay(
                canvas,
                RectF(
                    0f,
                    0f,
                    displayMapRect.width(),
                    displayMapRect.height()
                )
            )

            canvas.restore()

            if (footerHeight > 0) {
                val footerTop = sourceBitmap.height.toFloat()

                canvas.drawRect(
                    0f,
                    footerTop,
                    width.toFloat(),
                    outputBitmap!!.height.toFloat(),
                    backgroundPaint
                )

                canvas.drawLine(
                    0f,
                    footerTop,
                    width.toFloat(),
                    footerTop,
                    dividerPaint
                )

                var y = footerTop + padding + legendTitleSize

                canvas.drawText(
                    "DRAWING LEGEND",
                    padding,
                    y,
                    titlePaint
                )

                y += legendTitleSize * 0.8f

                var x = padding

                for (entry in drawingEntries) {
                    val entryLabel = label(entry)
                    val labelWidth = textPaint.measureText(entryLabel)

                    val totalWidth =
                        swatchSize + textGap + labelWidth + itemGap

                    if (
                        x > padding &&
                        x + totalWidth > width - padding
                    ) {
                        x = padding
                        y += rowHeight
                    }

                    val centerY = y + rowHeight * 0.25f

                    swatchPaint.color = entry.color
                    swatchPaint.style = Paint.Style.FILL

                    canvas.drawCircle(
                        x + swatchSize / 2f,
                        centerY,
                        swatchSize / 2f,
                        swatchPaint
                    )

                    val baseline =
                        centerY -
                                (textPaint.ascent() + textPaint.descent()) / 2f

                    canvas.drawText(
                        entryLabel,
                        x + swatchSize + textGap,
                        baseline,
                        textPaint
                    )

                    x += totalWidth
                }
            }

            val stream = contentResolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Cannot open output file")

            stream.use {
                if (
                    !outputBitmap!!.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        it
                    )
                ) {
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