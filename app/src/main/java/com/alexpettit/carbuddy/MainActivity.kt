package com.alexpettit.carbuddy

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import android.graphics.Paint
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private var motionX by mutableStateOf(0f)
    private var motionY by mutableStateOf(0f)
    private var leanAngle by mutableStateOf(0f)
    private var rollAngle by mutableStateOf(0f)

    private var speed by mutableStateOf(0f)
    private var latitude by mutableStateOf(0.0)
    private var longitude by mutableStateOf(0.0)

    private var lowFreqData = FloatArray(22)
    private var highFreqData = FloatArray(1024)
    private var lowFreqAvg by mutableStateOf(0f)
    private var highFreqPeak by mutableStateOf(0f)
    private var smoothedLowFreqAvg by mutableStateOf(0f)
    private var smoothedHighFreqPeak by mutableStateOf(0f)

    private var bumpEffect by mutableStateOf(0f)
    private var turnEffect by mutableStateOf(0f)

    private var fusedRoll by mutableStateOf(0f)
    private var fusedPitch by mutableStateOf(0f)
    private var lastUpdateTime = 0L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var audioScope: CoroutineScope

    private var audioEnginePtr: Long = 0

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private external fun startAudioEngine(instance: Long, ptr: LongArray): Long
    private external fun stopAudioEngine(instance: Long, ptr: Long)
    private external fun updateFrequencies(instance: Long, ptr: Long, lowFreq: FloatArray, highFreq: FloatArray)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestPermissions()
        setupSensors()
        setupLocation()
        setupAudio()

        setContent {
            CarBuddyUI()
        }
    }

    @Composable
    fun CarBuddyUI() {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFE8E13C)),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "🚗 Speed: ${speed.toInt()} mph",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "🗣️ Low Freq: ${String.format("%.2f", lowFreqAvg)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "🔔 High Freq: ${String.format("%.2f", highFreqPeak)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "📍 Lat: ${String.format("%.4f", latitude)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "📍 Lon: ${String.format("%.4f", longitude)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    DancingStickFigure()
                }
            }
            SpeedBar(speed)
        }
    }

    @Composable
    fun DancingStickFigure() {
        Canvas(modifier = Modifier.size(1200.dp)) {
            val centerX = size.width / 2f
            val centerY = size.height * 0.85f // ~1020dp

            val animatedX = (motionX * 200f + turnEffect).coerceIn(-size.width/4f, size.width/4f)
            val animatedY = (motionY * 200f + bumpEffect).coerceIn(-size.height * 0.8f, size.height * 0.2f)

            // Body
            drawLine(
                color = Color.Black,
                start = Offset(centerX + animatedX, centerY - 100f + animatedY),
                end = Offset(centerX + animatedX, centerY + 100f + animatedY),
                strokeWidth = 40f
            )

            val fingerCount = 5
            val fingerSpacing = 20f
            val maxFingerHeight = 600f
            val fingerColors = listOf(
                Color.Red,
                Color.Green,
                Color.Blue,
                Color.Yellow,
                Color.Magenta
            )
            for (i in 0 until fingerCount) {
                val binIndex = i * (1024 / fingerCount)
                val fingerHeight = (50f + highFreqData[binIndex] * 400f).coerceAtMost(maxFingerHeight)
                val fingerXLeft = centerX + animatedX - 80f - (i * fingerSpacing)
                val fingerXRight = centerX + animatedX + 80f + (i * fingerSpacing)
                val fingerBaseY = centerY + animatedY
                Log.d("FingerDebug", "Finger $i: binIndex=$binIndex, height=$fingerHeight, highFreqData=${highFreqData[binIndex]}")

                // Connectors to fingers
                drawLine(
                    color = Color.Gray,
                    start = Offset(centerX + animatedX, centerY + animatedY),
                    end = Offset(fingerXLeft, fingerBaseY),
                    strokeWidth = 5f
                )
                drawLine(
                    color = Color.Gray,
                    start = Offset(centerX + animatedX, centerY + animatedY),
                    end = Offset(fingerXRight, fingerBaseY),
                    strokeWidth = 5f
                )

                // Left finger
                drawLine(
                    color = fingerColors[i],
                    start = Offset(fingerXLeft, fingerBaseY),
                    end = Offset(fingerXLeft, fingerBaseY - fingerHeight),
                    strokeWidth = 10f
                )
                // Right finger
                drawLine(
                    color = fingerColors[i],
                    start = Offset(fingerXRight, fingerBaseY),
                    end = Offset(fingerXRight, fingerBaseY - fingerHeight),
                    strokeWidth = 10f
                )
            }

            val legCount = 2
            val legSpacing = 40f
            val maxLegHeight = 400f
            val bassBin = 0
            val legHeight = (20f + lowFreqData[bassBin] * 10f).coerceAtMost(maxLegHeight) // Increased to * 10f
            val leftLegX = centerX + animatedX - 20f
            val rightLegX = centerX + animatedX + 20f
            val legBaseY = centerY + 100f + animatedY
            Log.d("LegDebug", "Left Leg: binIndex=$bassBin, height=$legHeight, lowFreqData=${lowFreqData[bassBin]}")
            Log.d("LegDebug", "Right Leg: binIndex=$bassBin, height=$legHeight, lowFreqData=${lowFreqData[bassBin]}")

            // Connectors to legs
            drawLine(
                color = Color.Gray,
                start = Offset(centerX + animatedX, centerY + 100f + animatedY),
                end = Offset(leftLegX, legBaseY),
                strokeWidth = 5f
            )
            drawLine(
                color = Color.Gray,
                start = Offset(centerX + animatedX, centerY + 100f + animatedY),
                end = Offset(rightLegX, legBaseY),
                strokeWidth = 5f
            )

            // Left leg
            drawLine(
                color = Color.Black,
                start = Offset(leftLegX, legBaseY),
                end = Offset(leftLegX, legBaseY + legHeight),
                strokeWidth = 20f
            )
            // Right leg
            drawLine(
                color = Color.Black,
                start = Offset(rightLegX, legBaseY),
                end = Offset(rightLegX, legBaseY + legHeight),
                strokeWidth = 20f
            )

            val headEmoji = when {
                bumpEffect > 300f -> "😮"
                abs(leanAngle) > 25f -> "😮"
                speed >= 80f -> "😈"
                speed >= 60f -> "🤘"
                speed >= 40f -> "😎"
                lowFreqAvg > 0.8f -> "😃"
                else -> "🙂"
            }
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    textSize = 160f
                    color = android.graphics.Color.BLACK
                    textAlign = Paint.Align.CENTER
                }
                canvas.nativeCanvas.drawText(headEmoji, centerX + animatedX, centerY - 120f + animatedY, paint)
            }
        }
    }

    @Composable
    fun SpeedBar(speed: Float) {
        Canvas(modifier = Modifier
            .fillMaxHeight()
            .width(40.dp)
            .padding(8.dp)) {
            val maxSpeed = 90f
            val ratio = (speed / maxSpeed).coerceIn(0f, 1f)
            drawRect(
                color = Color.Red,
                topLeft = Offset(0f, size.height * (1f - ratio)),
                size = Size(size.width, size.height * ratio)
            )
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 200)
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private fun setupLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val locationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 500
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    latitude = location.latitude
                    longitude = location.longitude
                    speed = location.speed * 2.23694f
                }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        }
    }

    private fun setupAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val ptrArray = LongArray(1)
            audioEnginePtr = startAudioEngine(hashCode().toLong(), ptrArray)
            if (audioEnginePtr != 0L) {
                audioScope = CoroutineScope(Dispatchers.Default)
                audioScope.launch {
                    var prevLowFreqAvg = 0f
                    var prevHighFreqPeak = 0f
                    val smoothingFactor = 0.7f
                    while (isActive && audioEnginePtr != 0L) {
                        updateFrequencies(hashCode().toLong(), audioEnginePtr, lowFreqData, highFreqData)
                        lowFreqAvg = lowFreqData.map { abs(it) }.filter { it.isFinite() && it <= 1000f }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
                        highFreqPeak = highFreqData.map { abs(it) }.filter { it.isFinite() && it <= 1000f }.takeIf { it.isNotEmpty() }?.maxOrNull() ?: 0f
                        smoothedLowFreqAvg = (smoothingFactor * prevLowFreqAvg) + ((1f - smoothingFactor) * lowFreqAvg)
                        smoothedHighFreqPeak = (smoothingFactor * prevHighFreqPeak) + ((1f - smoothingFactor) * highFreqPeak)
                        prevLowFreqAvg = smoothedLowFreqAvg
                        prevHighFreqPeak = smoothedHighFreqPeak
                        Log.d("AudioDebug", "LowFreqAvg: $lowFreqAvg, HighFreqPeak: $highFreqPeak, LowFreq[0]: ${lowFreqData[0]}, HighFreq[0]: ${highFreqData[0]}")
                        delay(50)
                    }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val currentTime = System.currentTimeMillis()
            val dt = if (lastUpdateTime == 0L) 0f else (currentTime - lastUpdateTime) / 1000f
            lastUpdateTime = currentTime

            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val accelX = it.values[0] // Left-right (turns)
                    val accelY = it.values[1] // Forward-back (bumps)
                    val accelZ = it.values[2] // Up-down (gravity when upright)

                    // Convert Float to Double for Math.atan2 and Math.sqrt
                    val accelRoll = Math.toDegrees(Math.atan2(accelX.toDouble(), Math.sqrt((accelY * accelY + accelZ * accelZ).toDouble()))).toFloat()
                    val accelPitch = Math.toDegrees(Math.atan2(accelY.toDouble(), Math.sqrt((accelX * accelX + accelZ * accelZ).toDouble()))).toFloat()

                    // Complementary filter
                    if (dt > 0f) {
                        val alpha = 0.98f
                        fusedRoll = alpha * (fusedRoll + rollAngle * dt) + (1 - alpha) * accelRoll
                        fusedPitch = alpha * (fusedPitch + leanAngle * dt) + (1 - alpha) * accelPitch
                    } else {
                        fusedRoll = accelRoll
                        fusedPitch = accelPitch
                    }

                    // Motion and effects
                    motionX = -accelX / 9.81f // Left-right tilt
                    motionY = -accelZ / 9.81f // Up-down (Z-axis for upright)
                    bumpEffect = if (abs(accelY) > 1.5f) { // Y-axis for bumps (forward-back)
                        (-accelY) * 50f
                    } else 0f
                    turnEffect = -accelX * 20f // X-axis for turns (left-right)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    leanAngle = it.values[1] * 57.2958f // Pitch (Y-axis rotation)
                    rollAngle = it.values[0] * 57.2958f // Roll (X-axis rotation)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        if (audioEnginePtr != 0L) {
            stopAudioEngine(hashCode().toLong(), audioEnginePtr)
            audioScope.cancel()
            audioEnginePtr = 0L
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(
                LocationRequest.create().apply {
                    interval = 1000
                    fastestInterval = 500
                    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                },
                locationCallback,
                null
            )
        }
        setupAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        if (audioEnginePtr != 0L) {
            stopAudioEngine(hashCode().toLong(), audioEnginePtr)
            audioScope.cancel()
            audioEnginePtr = 0L
        }
    }
}