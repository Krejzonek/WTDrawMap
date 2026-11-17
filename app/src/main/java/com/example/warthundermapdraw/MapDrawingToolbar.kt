package com.example.warthundermapdraw

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MapDrawingToolbar(
    private val context: Context,
    private val container: FrameLayout,
    private val drawingManager: MapDrawingManager,
    private val onInvalidate: () -> Unit,
    private val onExport: () -> Unit
) {

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private lateinit var drawButton: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var palette: HorizontalScrollView
    private lateinit var paletteContent: LinearLayout
    private lateinit var exportButton: TextView

    private lateinit var pencilButton: ImageButton
    private lateinit var eraserButton: ImageButton
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton

    private lateinit var brushSizeSeekBar: SeekBar
    private lateinit var brushSizeLabel: TextView

    private var drawingAllowed = true

    private val colors = listOf(
        Color.RED,
        Color.rgb(255, 140, 0),
        Color.YELLOW,
        Color.GREEN,
        Color.CYAN,
        Color.BLUE,
        Color.rgb(160, 70, 220),
        Color.MAGENTA,
        Color.WHITE,
        Color.BLACK
    )

    init {
        createViews()
        createPalette()
        updateButtons()
    }

    private fun createViews() {
        drawButton = TextView(context).apply {
            text = "DRAW"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = createBackground(
                Color.rgb(21, 31, 45),
                Color.rgb(65, 91, 120)
            )
            elevation = dp(6).toFloat()
            isClickable = true
        }

        toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = createBackground(
                Color.rgb(17, 26, 39),
                Color.rgb(48, 67, 89)
            )
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }

        paletteContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            clipChildren = false
            clipToPadding = false
        }

        palette = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            clipChildren = false
            clipToPadding = false
            background = createBackground(
                Color.rgb(17, 26, 39),
                Color.rgb(48, 67, 89)
            )
            elevation = dp(7).toFloat()
            visibility = View.GONE

            addView(
                paletteContent,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        pencilButton = createIcon(R.drawable.ic_pen, "Pencil")
        eraserButton = createIcon(R.drawable.ic_eraser, "Eraser")
        undoButton = createIcon(R.drawable.ic_undo, "Undo")
        redoButton = createIcon(R.drawable.ic_redo, "Redo")

        val drawingControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        drawingControls.addView(pencilButton)
        drawingControls.addView(eraserButton)
        drawingControls.addView(undoButton)
        drawingControls.addView(redoButton)

        toolbar.addView(
            drawingControls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
            )
        )

        createBrushSizeControls()

        exportButton = TextView(context).apply {
            text = "SAVE PNG"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = createBackground(
                Color.rgb(21, 31, 45),
                Color.rgb(65, 91, 120)
            )
            elevation = dp(6).toFloat()
            isClickable = true
            visibility = View.GONE
        }

        val toolbarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply {
            marginStart = dp(12)
            bottomMargin = dp(58)
        }

        val paletteParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(48),
            Gravity.BOTTOM or Gravity.START
        ).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(150)
        }

        val actionButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        actionButtons.addView(
            drawButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
            )
        )

        actionButtons.addView(
            exportButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
            ).apply {
                marginStart = dp(8)
            }
        )

        val actionParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(42),
            Gravity.BOTTOM or Gravity.START
        ).apply {
            marginStart = dp(12)
            bottomMargin = dp(10)
        }

        container.addView(palette, paletteParams)
        container.addView(toolbar, toolbarParams)
        container.addView(actionButtons, actionParams)

        setupListeners()
    }

    private fun createBrushSizeControls() {
        val brushSizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(2))
        }

        val brushTitle = TextView(context).apply {
            text = "SIZE"
            textSize = 10f
            setTextColor(Color.rgb(190, 205, 220))
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }

        brushSizeLabel = TextView(context).apply {
            text = "${drawingManager.brushSizeDp.toInt()} dp"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }

        brushSizeSeekBar = SeekBar(context).apply {
            max = 9
            progress = (drawingManager.brushSizeDp.toInt() - 1).coerceIn(0, 9)

            progressTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(75, 155, 230)
                )

            thumbTintList =
                android.content.res.ColorStateList.valueOf(Color.WHITE)

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (!drawingAllowed) return

                        val size = (progress + 1).toFloat()
                        drawingManager.brushSizeDp = size
                        brushSizeLabel.text = "${size.toInt()} dp"
                        onInvalidate()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }

        brushSizeRow.addView(
            brushTitle,
            LinearLayout.LayoutParams(dp(38), dp(34))
        )

        brushSizeRow.addView(
            brushSizeSeekBar,
            LinearLayout.LayoutParams(dp(155), dp(34)).apply {
                marginStart = dp(5)
                marginEnd = dp(7)
            }
        )

        brushSizeRow.addView(
            brushSizeLabel,
            LinearLayout.LayoutParams(dp(42), dp(34))
        )

        toolbar.addView(
            brushSizeRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
            )
        )
    }

    private fun createIcon(
        resource: Int,
        description: String
    ): ImageButton {
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setImageResource(resource)

            imageTintList =
                android.content.res.ColorStateList.valueOf(Color.WHITE)

            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description

            setPadding(dp(10), dp(10), dp(10), dp(10))

            setBackgroundResource(
                android.R.drawable.list_selector_background
            )
        }
    }

    private fun createBackground(
        backgroundColor: Int,
        borderColor: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), borderColor)
        }
    }

    private fun createPalette() {
        paletteContent.removeAllViews()

        colors.forEach { color ->
            val colorView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(34),
                    dp(34)
                ).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                }

                setImageResource(R.drawable.color_circle)
                setColorFilter(color)
                scaleType = ImageView.ScaleType.FIT_CENTER

                setPadding(dp(4), dp(4), dp(4), dp(4))

                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)

                    if (color == drawingManager.currentColor) {
                        setStroke(dp(2), Color.WHITE)
                    }
                }

                contentDescription = "Drawing color"
                isEnabled = drawingAllowed
                alpha = if (drawingAllowed) 1f else 0.35f

                setOnClickListener {
                    if (!drawingAllowed) return@setOnClickListener

                    drawingManager.currentColor = color
                    drawingManager.activeTool = MapDrawingManager.Tool.PENCIL

                    palette.visibility = View.GONE

                    createPalette()
                    updateButtons()
                }
            }

            paletteContent.addView(colorView)
        }

        palette.post {
            palette.scrollTo(0, 0)
        }
    }

    private fun setupListeners() {
        drawButton.setOnClickListener {
            if (!drawingAllowed) return@setOnClickListener

            val show = toolbar.visibility != View.VISIBLE

            toolbar.visibility = if (show) View.VISIBLE else View.GONE
            palette.visibility = View.GONE

            if (!show) {
                drawingManager.activeTool = MapDrawingManager.Tool.NONE
            }

            drawButton.text = if (show) "CLOSE" else "DRAW"

            updateButtons()
            onInvalidate()
        }

        pencilButton.setOnClickListener {
            if (!drawingAllowed) return@setOnClickListener

            drawingManager.activeTool = MapDrawingManager.Tool.PENCIL

            palette.visibility =
                if (palette.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

            updateButtons()
        }

        eraserButton.setOnClickListener {
            if (!drawingAllowed) return@setOnClickListener

            drawingManager.activeTool = MapDrawingManager.Tool.ERASER
            palette.visibility = View.GONE
            updateButtons()
        }

        undoButton.setOnClickListener {
            if (!drawingAllowed) return@setOnClickListener

            drawingManager.undo()
            updateButtons()
            onInvalidate()
        }

        redoButton.setOnClickListener {
            if (!drawingAllowed) return@setOnClickListener

            drawingManager.redo()
            updateButtons()
            onInvalidate()
        }

        exportButton.setOnClickListener {
            onExport()
        }
    }

    /**
     * Wywołuj po zmianie uprawnienia użytkownika.
     */
    fun setDrawingAllowed(allowed: Boolean) {
        drawingAllowed = allowed
        drawingManager.setDrawingPermission(allowed)

        if (!allowed) {
            closeDrawingTools()
        }

        drawButton.isEnabled = allowed
        drawButton.alpha = if (allowed) 1f else 0.4f

        brushSizeSeekBar.isEnabled = allowed
        brushSizeLabel.alpha = if (allowed) 1f else 0.4f

        createPalette()
        updateButtons()
        onInvalidate()
    }

    fun updateButtons() {
        val activeColor = Color.rgb(40, 95, 145)
        val normalColor = Color.rgb(17, 26, 39)

        pencilButton.isEnabled = drawingAllowed
        eraserButton.isEnabled = drawingAllowed
        undoButton.isEnabled = drawingAllowed && drawingManager.canUndo
        redoButton.isEnabled = drawingAllowed && drawingManager.canRedo

        pencilButton.alpha = if (drawingAllowed) 1f else 0.35f
        eraserButton.alpha = if (drawingAllowed) 1f else 0.35f

        pencilButton.setBackgroundColor(
            if (
                drawingAllowed &&
                drawingManager.activeTool == MapDrawingManager.Tool.PENCIL
            ) activeColor else normalColor
        )

        eraserButton.setBackgroundColor(
            if (
                drawingAllowed &&
                drawingManager.activeTool == MapDrawingManager.Tool.ERASER
            ) activeColor else normalColor
        )

        undoButton.alpha =
            if (drawingManager.canUndo) 1f else 0.4f

        redoButton.alpha =
            if (drawingManager.canRedo) 1f else 0.4f
    }

    fun setExportVisible(visible: Boolean) {
        exportButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun closeDrawingTools() {
        toolbar.visibility = View.GONE
        palette.visibility = View.GONE
        drawButton.text = "DRAW"

        drawingManager.activeTool = MapDrawingManager.Tool.NONE

        updateButtons()
    }
}