package com.example.warthundermapdraw

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

class MapOverlayView(
    context: Context,
    private val mapImage: android.widget.ImageView,
    private val getBitmap: () -> android.graphics.Bitmap?,
    private val drawingManager: MapDrawingManager
) : View(context) {

    companion object {
        private const val TAG = "MapOverlayView"
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imageRect = RectF()
    private val bitmapRect = RectF()
    private val drawableCache = mutableMapOf<Int, Drawable?>()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 20, 28)
        style = Paint.Style.FILL
    }

    private var mapData = MapData(emptyList(), emptyList(), false)

    fun setMapData(data: MapData) {
        mapData = data
        invalidate()
    }

    fun getMapRect(): RectF? {
        val bitmap = getBitmap() ?: return null
        if (mapImage.drawable == null) return null

        bitmapRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        imageRect.set(bitmapRect)
        mapImage.imageMatrix.mapRect(imageRect)

        imageRect.offset(
            mapImage.left.toFloat() + mapImage.paddingLeft,
            mapImage.top.toFloat() + mapImage.paddingTop
        )

        return if (imageRect.width() > 0f && imageRect.height() > 0f) {
            RectF(imageRect)
        } else {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val mapRect = getMapRect() ?: return

        drawSpawnSectors(canvas, mapRect)
        drawMarkers(canvas, mapRect)
        drawingManager.draw(canvas, mapRect)
    }

    fun drawExportOverlay(canvas: Canvas, exportRect: RectF) {
        drawSpawnSectors(canvas, exportRect)
        drawMarkers(canvas, exportRect)
        drawingManager.draw(canvas, exportRect)
    }

    private fun parseColorOrDefault(color: String, defaultColor: Int): Int {
        if (color.isBlank()) return defaultColor

        return try {
            Color.parseColor(if (color.startsWith("#")) color else "#$color")
        } catch (_: Exception) {
            defaultColor
        }
    }

    private fun drawSpawnSectors(canvas: Canvas, mapRect: RectF) {
        val density = resources.displayMetrics.density

        for (sector in mapData.sectors) {
            val color = parseColorOrDefault(sector.color, Color.rgb(90, 150, 255))
            val hull = convexHull(sector.points)
            if (hull.isEmpty()) continue

            val centerX = sector.points.sumOf { it.x.toDouble() }.toFloat() /
                    sector.points.size.coerceAtLeast(1)
            val centerY = sector.points.sumOf { it.y.toDouble() }.toFloat() /
                    sector.points.size.coerceAtLeast(1)

            when (hull.size) {
                1 -> {
                    paint.apply {
                        style = Paint.Style.STROKE
                        this.color = color
                        alpha = 190
                        strokeWidth = 0.8f * density
                    }

                    canvas.drawCircle(
                        mapRect.left + hull[0].x * mapRect.width(),
                        mapRect.top + hull[0].y * mapRect.height(),
                        2f * density,
                        paint
                    )
                }

                2 -> {
                    paint.apply {
                        style = Paint.Style.STROKE
                        this.color = color
                        alpha = 190
                        strokeWidth = density
                        strokeCap = Paint.Cap.ROUND
                    }

                    canvas.drawLine(
                        mapRect.left + hull[0].x * mapRect.width(),
                        mapRect.top + hull[0].y * mapRect.height(),
                        mapRect.left + hull[1].x * mapRect.width(),
                        mapRect.top + hull[1].y * mapRect.height(),
                        paint
                    )
                }

                else -> {
                    val path = Path().apply {
                        moveTo(
                            mapRect.left + hull[0].x * mapRect.width(),
                            mapRect.top + hull[0].y * mapRect.height()
                        )

                        for (i in 1 until hull.size) {
                            lineTo(
                                mapRect.left + hull[i].x * mapRect.width(),
                                mapRect.top + hull[i].y * mapRect.height()
                            )
                        }

                        close()
                    }

                    paint.apply {
                        style = Paint.Style.FILL
                        this.color = color
                        alpha = 10
                    }
                    canvas.drawPath(path, paint)

                    paint.apply {
                        style = Paint.Style.STROKE
                        alpha = 190
                        strokeWidth = density
                        pathEffect = null
                    }
                    canvas.drawPath(path, paint)

                    val bounds = RectF(
                        mapRect.left + hull.minOf { it.x } * mapRect.width(),
                        mapRect.top + hull.minOf { it.y } * mapRect.height(),
                        mapRect.left + hull.maxOf { it.x } * mapRect.width(),
                        mapRect.top + hull.maxOf { it.y } * mapRect.height()
                    )

                    drawFitLabel(
                        canvas,
                        sector.type,
                        mapRect.left + centerX * mapRect.width(),
                        mapRect.top + centerY * mapRect.height(),
                        bounds,
                        10f * density
                    )
                }
            }
        }

        paint.pathEffect = null
    }

    private fun drawMarkers(canvas: Canvas, mapRect: RectF) {
        val density = resources.displayMetrics.density

        for (marker in mapData.markers) {
            val x = mapRect.left + marker.x * mapRect.width()
            val y = mapRect.top + marker.y * mapRect.height()

            when (marker.type) {
                MarkerType.CAPTURE -> {
                    drawUnitIcon(canvas, x, y, marker, density)

                    if (marker.label.isNotBlank()) {
                        textPaint.apply {
                            color = Color.BLACK
                            style = Paint.Style.FILL
                            textSize = 12f * density
                            textAlign = Paint.Align.CENTER
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }

                        canvas.drawText(
                            marker.label,
                            x,
                            y - (textPaint.ascent() + textPaint.descent()) / 2f,
                            textPaint
                        )
                    }
                }

                MarkerType.AIRFIELD,
                MarkerType.PLAYER_UNIT -> drawUnitIcon(canvas, x, y, marker, density)

                MarkerType.GROUND_UNIT -> {
                    if (!mapData.isAirView) {
                        drawUnitIcon(canvas, x, y, marker, density)
                    }
                }

                MarkerType.AIR_UNIT -> {
                    if (mapData.isAirView) {
                        drawUnitIcon(canvas, x, y, marker, density)
                    }
                }
            }
        }
    }

    private fun drawUnitIcon(
        canvas: Canvas,
        x: Float,
        y: Float,
        marker: MapMarker,
        density: Float
    ) {
        val resourceId = MapIconProvider.getIconRes(marker.iconName)
        val drawable = drawableCache.getOrPut(resourceId) {
            try {
                context.getDrawable(resourceId)?.mutate()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot load icon ${marker.iconName}", e)
                null
            }
        } ?: return

        val isPlayer = marker.type == MarkerType.PLAYER_UNIT

        val size = when (marker.iconName) {
            "capture_zone" -> 19f
            "airfield" -> 23f
            "Battleship", "Battlecruiser" -> if (isPlayer) 10f else 9f
            "Bomber" -> if (isPlayer) 9f else 8f
            "Player" -> 7.3f
            else -> if (isPlayer) 7.3f else 6.3f
        } * density

        val width = size * when (marker.iconName) {
            "Ship", "TorpedoBoat", "Frigate", "Gunboat",
            "Destroyer", "Cruiser", "LightCruiser", "HeavyCruiser",
            "Battleship", "Battlecruiser" -> 0.72f
            else -> 0.78f
        }

        val directionLength = hypot(marker.directionX, marker.directionY)
        val directionX = if (directionLength > 0.001f) marker.directionX else 0f
        val directionY = if (directionLength > 0.001f) marker.directionY else -1f

        val angle = Math.toDegrees(
            atan2(directionY.toDouble(), directionX.toDouble())
        ).toFloat() + 90f

        val left = -width / 2f
        val top = -size / 2f
        val right = width / 2f
        val bottom = size / 2f
        val outline = 0.7f * density

        canvas.save()
        canvas.translate(x, y)

        if (marker.type == MarkerType.GROUND_UNIT ||
            marker.type == MarkerType.AIR_UNIT ||
            marker.type == MarkerType.PLAYER_UNIT
        ) {
            canvas.rotate(angle)
        }

        drawable.setBounds(
            (left - outline).toInt(),
            (top - outline).toInt(),
            (right + outline).toInt(),
            (bottom + outline).toInt()
        )
        drawable.setTint(Color.BLACK)
        drawable.alpha = 255
        drawable.draw(canvas)

        drawable.setBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        drawable.setTint(marker.color)
        drawable.alpha = 255
        drawable.draw(canvas)

        canvas.restore()
    }

    private fun drawFitLabel(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        bounds: RectF,
        maxTextSize: Float
    ) {
        if (text.isBlank()) return

        val density = resources.displayMetrics.density
        val horizontalPadding = 3f * density
        val verticalPadding = 2f * density
        val availableWidth = bounds.width() - horizontalPadding * 2f
        val availableHeight = bounds.height() - verticalPadding * 2f

        if (availableWidth <= 0f || availableHeight <= 0f) return

        textPaint.apply {
            textSize = maxTextSize
            style = Paint.Style.FILL
        }

        val initialWidth = textPaint.measureText(text)
        val initialMetrics = textPaint.fontMetrics
        val initialHeight = initialMetrics.descent - initialMetrics.ascent

        if (initialWidth <= 0f || initialHeight <= 0f) return

        val scale = minOf(
            1f,
            availableWidth / initialWidth,
            availableHeight / initialHeight
        )

        textPaint.textSize = maxTextSize * scale

        val metrics = textPaint.fontMetrics
        val baseline = y - (metrics.ascent + metrics.descent) / 2f
        val textWidth = textPaint.measureText(text)

        val paddingX = minOf(horizontalPadding, availableWidth * 0.08f)
        val paddingY = minOf(verticalPadding, availableHeight * 0.08f)

        val background = RectF(
            x - textWidth / 2f - paddingX,
            baseline + metrics.ascent - paddingY,
            x + textWidth / 2f + paddingX,
            baseline + metrics.descent + paddingY
        )

        if (background.left < bounds.left ||
            background.right > bounds.right ||
            background.top < bounds.top ||
            background.bottom > bounds.bottom
        ) return

        canvas.drawRoundRect(
            background,
            2f * density * scale,
            2f * density * scale,
            textBackgroundPaint
        )

        textPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density * scale
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        canvas.drawText(text, x, baseline, textPaint)

        textPaint.apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawText(text, x, baseline, textPaint)
    }

    private fun convexHull(points: List<SpawnPoint>): List<android.graphics.PointF> {
        val sorted = points
            .map { android.graphics.PointF(it.x, it.y) }
            .distinctBy { "${it.x}:${it.y}" }
            .sortedWith(compareBy<android.graphics.PointF> { it.x }.thenBy { it.y })

        if (sorted.size <= 2) return sorted

        fun cross(
            o: android.graphics.PointF,
            a: android.graphics.PointF,
            b: android.graphics.PointF
        ): Float {
            return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }

        val lower = mutableListOf<android.graphics.PointF>()
        for (point in sorted) {
            while (lower.size >= 2 &&
                cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0f
            ) {
                lower.removeAt(lower.lastIndex)
            }
            lower.add(point)
        }

        val upper = mutableListOf<android.graphics.PointF>()
        for (point in sorted.asReversed()) {
            while (upper.size >= 2 &&
                cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0f
            ) {
                upper.removeAt(upper.lastIndex)
            }
            upper.add(point)
        }

        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)

        return lower + upper
    }
}