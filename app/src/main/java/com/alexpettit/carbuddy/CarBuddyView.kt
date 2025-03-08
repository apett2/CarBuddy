package com.alexpettit.carbuddy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CarBuddyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val armPaint = Paint().apply { color = Color.BLUE }
    private val legPaint = Paint().apply { color = Color.RED }
    private var lowFreqData = FloatArray(22) // 23 bins - 1 (skip DC)
    private var highFreqData = FloatArray(1024) // Remaining bins (500 Hz - 20 kHz)

    fun updateFrequenciesNative(lowFreq: FloatArray, highFreq: FloatArray) {
        lowFreqData = lowFreq
        highFreqData = highFreq
        invalidate() // Redraw the view
    }

    external fun startAudioEngine()

    init {
        System.loadLibrary("native-lib")
        startAudioEngine() // Start audio processing when the view is initialized
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2

        // Draw body (static)
        canvas.drawCircle(centerX, centerY, 50f, Paint().apply { color = Color.GRAY })

        // Arms (upward graphs for mid-to-high frequencies, 500 Hz - 20 kHz)
        val armWidth = 10f
        for (i in 0 until 10) { // Subsample for simplicity
            val heightScale = highFreqData[i * 100].coerceAtMost(1.0f) * 200f // Scale and cap magnitude
            canvas.drawRect(
                centerX - 60f + i * armWidth, centerY - heightScale,
                centerX - 60f + (i + 1) * armWidth, centerY,
                armPaint
            )
            canvas.drawRect(
                centerX + 60f - (i + 1) * armWidth, centerY - heightScale,
                centerX + 60f - i * armWidth, centerY,
                armPaint
            )
        }

        // Legs (downward graphs for mid-to-low frequencies, 20 Hz - 500 Hz)
        for (i in 0 until 10) {
            val heightScale = lowFreqData[i * 2].coerceAtMost(1.0f) * 200f // Scale and cap magnitude
            canvas.drawRect(
                centerX - 60f + i * armWidth, centerY,
                centerX - 60f + (i + 1) * armWidth, centerY + heightScale,
                legPaint
            )
            canvas.drawRect(
                centerX + 60f - (i + 1) * armWidth, centerY,
                centerX + 60f - i * armWidth, centerY + heightScale,
                legPaint
            )
        }
    }
}