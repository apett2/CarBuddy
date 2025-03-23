package com.alexpettit.carbuddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.decode.GifDecoder
import android.graphics.Paint
import android.net.ConnectivityManager
import android.content.Context
import androidx.compose.ui.graphics.luminance
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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

    private var lowFreqData = FloatArray(22) { 0f }
    private var highFreqData = FloatArray(1024) { 0f }
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

    private var audioEnginePtr: Long = 0L

    private val isCustomizationUnlocked = true
    private var emoji80 by mutableStateOf("😈")
    private var emoji60 by mutableStateOf("🤘")
    private var emoji40 by mutableStateOf("😎")
    private var emoji0 by mutableStateOf("🙂")
    private var backgroundColor by mutableStateOf(Color(0xFFE8E13C))
    private var backgroundImageUri by mutableStateOf<String?>(null)

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Storage permission granted, reloading background")
            val sharedPrefs = getSharedPreferences("CarBuddyPrefs", MODE_PRIVATE)
            backgroundImageUri = sharedPrefs.getString("background_image_uri", null)
        } else {
            Log.w(TAG, "Storage permission denied")
        }
    }

    private val recordPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Record permission granted")
        } else {
            Log.w(TAG, "Record permission denied")
        }
    }

    // Added for API key handling
    private lateinit var sharedPreferences: android.content.SharedPreferences
    private var showApiDialog by mutableStateOf(false)
    private var apiKey by mutableStateOf("")
    private var secretKey by mutableStateOf("")

    companion object {
        private const val TAG = "CarBuddy"
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

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)
            finish()
        }

        val sharedPrefs = getSharedPreferences("CarBuddyPrefs", MODE_PRIVATE)
        emoji80 = sharedPrefs.getString("emoji80", "😈") ?: "😈"
        emoji60 = sharedPrefs.getString("emoji60", "🤘") ?: "🤘"
        emoji40 = sharedPrefs.getString("emoji40", "😎") ?: "😎"
        emoji0 = sharedPrefs.getString("emoji0", "🙂") ?: "🙂"
        backgroundColor = Color(sharedPrefs.getInt("background_color", 0xFFE8E13C.toInt()))
        backgroundImageUri = sharedPrefs.getString("background_image_uri", null)

        // Initialize SharedPreferences for API keys
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        apiKey = sharedPreferences.getString("acrcloud_api_key", "") ?: ""
        secretKey = sharedPreferences.getString("acrcloud_secret_key", "") ?: ""

        requestPermissions()
        setupSensors()
        setupLocation()
        setupAudio()

        setContent {
            CarBuddyUI(sharedPrefs)
        }
    }

    @Serializable
    data class SongEntry(val timestamp: String, val result: String)

    // Added helper function for dynamic text color
    private fun getContrastTextColor(backgroundColor: Color): Color {
        return if (backgroundColor.luminance() > 0.5) Color.Black else Color.White
    }

    @Composable
    fun CarBuddyUI(sharedPrefs: android.content.SharedPreferences) {
        var showLicensesDialog by remember { mutableStateOf(false) }
        var showHistoryDialog by remember { mutableStateOf(false) }
        var showPrivacyDialog by remember { mutableStateOf(false) }
        var songResult by remember { mutableStateOf<String?>(null) }
        var isFetchingSong by remember { mutableStateOf(false) }
        val historyJson = sharedPrefs.getString("song_history", null)
        val initialHistory = historyJson?.let { Json.decodeFromString<List<SongEntry>>(it) }?.map { it.timestamp to it.result } ?: emptyList()
        val songHistory = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(initialHistory) } }

        // Calculate text color dynamically
        val textColor = getContrastTextColor(backgroundColor)

        Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
            if (backgroundImageUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(this@MainActivity)
                        .data(backgroundImageUri)
                        .decoderFactory(GifDecoder.Factory())
                        .build(),
                    contentDescription = "Custom Background",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "CarBuddy",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, color = textColor),
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🚗 Speed: ${speed.toInt()} mph", style = MaterialTheme.typography.bodyLarge.copy(color = textColor))
                        Text("🗣️ Low Freq: ${String.format("%.2f", lowFreqAvg)}", style = MaterialTheme.typography.bodyLarge.copy(color = textColor))
                        Text("🔔 High Freq: ${String.format("%.2f", highFreqPeak)}", style = MaterialTheme.typography.bodyLarge.copy(color = textColor))
                        Text("📍 Lat: ${String.format("%.4f", latitude)}", style = MaterialTheme.typography.bodyLarge.copy(color = textColor))
                        Text("📍 Lon: ${String.format("%.4f", longitude)}", style = MaterialTheme.typography.bodyLarge.copy(color = textColor))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        Log.d(TAG, "Customize button clicked")
                        val intent = Intent(this@MainActivity, CustomizeActivity::class.java).apply {
                            putExtra("isCustomizationUnlocked", isCustomizationUnlocked)
                        }
                        startActivity(intent)
                    }) {
                        Text("Customize")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        Log.i("SongID", "Song ID button clicked")
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            Log.i("SongID", "Permission granted, checking API keys")
                            if (apiKey.isEmpty() || secretKey.isEmpty()) {
                                showApiDialog = true
                            } else {
                                isFetchingSong = true
                                identifySong { result ->
                                    songResult = result
                                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                    songHistory.add(0, timestamp to result)
                                    val historyJsonToSave = Json.encodeToString(songHistory.map { SongEntry(it.first, it.second) })
                                    sharedPrefs.edit().putString("song_history", historyJsonToSave).apply()
                                    isFetchingSong = false
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(15_000)
                                        songResult = null
                                    }
                                }
                            }
                        } else {
                            Log.w("SongID", "Permission not granted, requesting")
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Text("Song ID")
                    }
                    Spacer(modifier = Modifier.height(8.dp)) // Restored original spacing
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

            // Song Result Text (Moved to Box with absolute positioning)
            Text(
                text = when {
                    isFetchingSong -> "Fetching Song ID..."
                    songResult != null -> songResult!!
                    else -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFetchingSong) Color.Gray else textColor,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp) // Lifts text ~10% from bottom, adjust as needed
            )

            // Licenses Button (Bottom-Left)
            Text(
                text = "Licenses",
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.Blue),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .clickable {
                        Log.d(TAG, "Licenses clicked")
                        showLicensesDialog = true
                    }
                    .padding(16.dp)
            )

            // Song ID History (Bottom-Right, as Text)
            Text(
                text = "Song ID History",
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.Blue),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clickable {
                        Log.i("SongID", "Song ID History clicked")
                        showHistoryDialog = true
                    }
                    .padding(16.dp)
            )

            // Privacy Policy Button (Bottom-Center)
            Text(
                text = "Privacy Policy",
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.Blue),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clickable {
                        Log.d(TAG, "Privacy Policy clicked")
                        showPrivacyDialog = true
                    }
                    .padding(16.dp)
            )

            // API Key Input Dialog
            if (showApiDialog) {
                AlertDialog(
                    onDismissRequest = { showApiDialog = false },
                    title = { Text("ACRCloud Credentials") },
                    text = {
                        Column {
                            TextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("API Key") }
                            )
                            TextField(
                                value = secretKey,
                                onValueChange = { secretKey = it },
                                label = { Text("Secret Key") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            sharedPreferences.edit()
                                .putString("acrcloud_api_key", apiKey)
                                .putString("acrcloud_secret_key", secretKey)
                                .apply()
                            showApiDialog = false
                            isFetchingSong = true
                            identifySong { result ->
                                songResult = result
                                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                songHistory.add(0, timestamp to result)
                                val historyJsonToSave = Json.encodeToString(songHistory.map { SongEntry(it.first, it.second) })
                                sharedPrefs.edit().putString("song_history", historyJsonToSave).apply()
                                isFetchingSong = false
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(15_000)
                                    songResult = null
                                }
                            }
                        }) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        Button(onClick = { showApiDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        if (showLicensesDialog) {
            AlertDialog(
                onDismissRequest = { showLicensesDialog = false },
                title = { Text("Open Source Licenses") },
                text = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "Google Oboe\nLicense: Apache License 2.0\nCopyright 2015 The Android Open Source Project\n\n" +
                                    "Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                                    "you may not use this file except in compliance with the License.\n" +
                                    "You may obtain a copy of the License at\n\n" +
                                    "    http://www.apache.org/licenses/LICENSE-2.0\n\n" +
                                    "Unless required by applicable law or agreed to in writing, software\n" +
                                    "distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
                                    "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
                                    "See the License for the specific language governing permissions and\n" +
                                    "limitations under the License.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "KissFFT\nLicense: BSD 3-Clause License\nCopyright (c) 2003-2010 Mark Borgerding\n\n" +
                                    "Redistribution and use in source and binary forms, with or without\n" +
                                    "modification, are permitted provided that the following conditions are met:\n\n" +
                                    "1. Redistributions of source code must retain the above copyright notice,\n" +
                                    "   this list of conditions and the following disclaimer.\n" +
                                    "2. Redistributions in binary form must reproduce the above copyright notice,\n" +
                                    "   this list of conditions and the following disclaimer in the documentation\n" +
                                    "   and/or other materials provided with the distribution.\n" +
                                    "3. Neither the name of the copyright holder nor the names of its contributors\n" +
                                    "   may be used to endorse or promote products derived from this software\n" +
                                    "   without specific prior written permission.\n\n" +
                                    "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\"\n" +
                                    "AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE\n" +
                                    "IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE\n" +
                                    "ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE\n" +
                                    "LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR\n" +
                                    "CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF\n" +
                                    "SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS\n" +
                                    "INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN\n" +
                                    "CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)\n" +
                                    "ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE\n" +
                                    "POSSIBILITY OF SUCH DAMAGE.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Coil\nLicense: Apache License 2.0\nCopyright 2020 Coil Contributors\n\n" +
                                    "Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                                    "you may not use this file except in compliance with the License.\n" +
                                    "You may obtain a copy of the License at\n\n" +
                                    "    http://www.apache.org/licenses/LICENSE-2.0\n\n" +
                                    "Unless required by applicable law or agreed to in writing, software\n" +
                                    "distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
                                    "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
                                    "See the License for the specific language governing permissions and\n" +
                                    "limitations under the License.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "App Icon designed by Freepik from Flaticon",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Built with assistance from Grok 3, created by xAI\nThis is a courtesy acknowledgment.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLicensesDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        if (showHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showHistoryDialog = false },
                title = { Text("Song ID History") },
                text = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        if (songHistory.isEmpty()) {
                            Text("No songs identified yet.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            songHistory.forEach { (timestamp, result) ->
                                Text(
                                    text = "$timestamp: $result",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHistoryDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
        if (showPrivacyDialog) {
            AlertDialog(
                onDismissRequest = { showPrivacyDialog = false },
                title = { Text("Privacy Policy") },
                text = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "Car Buddy does not collect, store, or share any personal data. All song identification processing is handled locally or via third-party services (ACRCloud) for audio recognition only. No user data is transmitted or retained beyond the app's immediate use.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPrivacyDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }

    @Composable
    fun DancingStickFigure() {
        Canvas(modifier = Modifier.size(1200.dp)) {
            val centerX = size.width / 2f
            val centerY = size.height * 0.85f

            val animatedX = (motionX * 200f + turnEffect).coerceIn(-size.width / 4f, size.width / 4f)
            val animatedY = (motionY * 200f + bumpEffect).coerceIn(-size.height * 0.8f, size.height * 0.2f)

            drawLine(
                color = Color.Black,
                start = Offset(centerX + animatedX, centerY - 100f + animatedY),
                end = Offset(centerX + animatedX, centerY + 100f + animatedY),
                strokeWidth = 40f
            )

            val totalFingers = 10
            val fingerSpacing = 20f
            val maxFingerHeight = 600f
            val fingerColors = listOf(
                Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Magenta,
                Color.Magenta, Color.Yellow, Color.Blue, Color.Green, Color.Red
            )

            val minBin = 0
            val maxBin = 278
            val binRange = maxBin - minBin
            val logBase = Math.pow(maxBin.toDouble() / 1.0, 1.0 / 4.0)

            for (i in 0 until totalFingers) {
                val pairIndex = if (i < 5) i else (9 - i)
                val binIndex = if (pairIndex == 0) {
                    minBin
                } else {
                    (minBin + (binRange * (Math.pow(logBase, pairIndex.toDouble()) - 1) / (Math.pow(logBase, 4.0) - 1))).toInt()
                }.coerceIn(minBin, maxBin)

                val sensitivity = when (i) {
                    0, 9 -> 200f
                    1, 8 -> 250f
                    2, 7 -> 300f
                    3, 6 -> 350f
                    4, 5 -> 400f
                    else -> 300f
                }
                val fingerHeight = (50f + highFreqData[binIndex] * sensitivity).coerceAtMost(maxFingerHeight)
                val isLeft = i < 5
                val fingerX = if (isLeft) {
                    centerX + animatedX - 80f - ((4 - i) * fingerSpacing)
                } else {
                    centerX + animatedX + 80f + ((i - 5) * fingerSpacing)
                }
                val fingerBaseY = centerY + animatedY

                drawLine(
                    color = Color.Gray,
                    start = Offset(centerX + animatedX, centerY + animatedY),
                    end = Offset(fingerX, fingerBaseY),
                    strokeWidth = 5f
                )
                drawLine(
                    color = fingerColors[i],
                    start = Offset(fingerX, fingerBaseY),
                    end = Offset(fingerX, fingerBaseY - fingerHeight),
                    strokeWidth = 10f
                )
            }

            val maxLegHeight = 400f
            val bassBin = 0
            val legHeight = (20f + lowFreqData[bassBin] * 10f).coerceAtMost(maxLegHeight)
            val leftLegX = centerX + animatedX - 20f
            val rightLegX = centerX + animatedX + 20f
            val legBaseY = centerY + 100f + animatedY

            drawLine(color = Color.Gray, start = Offset(centerX + animatedX, centerY + 100f + animatedY), end = Offset(leftLegX, legBaseY), strokeWidth = 5f)
            drawLine(color = Color.Gray, start = Offset(centerX + animatedX, centerY + 100f + animatedY), end = Offset(rightLegX, legBaseY), strokeWidth = 5f)
            drawLine(color = Color.Black, start = Offset(leftLegX, legBaseY), end = Offset(leftLegX, legBaseY + legHeight), strokeWidth = 20f)
            drawLine(color = Color.Black, start = Offset(rightLegX, legBaseY), end = Offset(rightLegX, legBaseY + legHeight), strokeWidth = 20f)

            val headEmoji = when {
                bumpEffect > 300f -> "😮"
                abs(leanAngle) > 25f -> "😮"
                speed >= 80f -> emoji80
                speed >= 60f -> emoji60
                speed >= 40f -> emoji40
                lowFreqAvg > 0.8f -> "😃"
                else -> emoji0
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
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
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
        val locationRequest = LocationRequest.Builder(LocationRequest.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    latitude = location.latitude
                    longitude = location.longitude
                    speed = location.speed * 2.23694f
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        }
    }

    private fun setupAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Record audio permission not granted, skipping audio setup")
            return
        }

        stopAudioEngineSafe()

        lowFreqData.fill(0f)
        highFreqData.fill(0f)
        lowFreqAvg = 0f
        highFreqPeak = 0f
        smoothedLowFreqAvg = 0f
        smoothedHighFreqPeak = 0f
        Log.d(TAG, "Audio buffers reset: lowFreqData[0]=${lowFreqData[0]}, highFreqData[0]=${highFreqData[0]}")

        val ptrArray = LongArray(1)
        Log.d(TAG, "Attempting to start AudioEngine with instance=${hashCode().toLong()}")
        try {
            audioEnginePtr = startAudioEngine(hashCode().toLong(), ptrArray)
            if (audioEnginePtr == 0L) {
                Log.e(TAG, "Failed to start AudioEngine, ptr remains 0")
                return
            }
            Log.d(TAG, "AudioEngine started successfully, ptr=$audioEnginePtr, ptrArray[0]=${ptrArray[0]}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioEngine: ${e.message}", e)
            audioEnginePtr = 0L
            return
        }

        if (!::audioScope.isInitialized || !audioScope.isActive) {
            audioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            Log.d(TAG, "Initialized new audioScope for audio processing")
        }

        audioScope.launch {
            delay(500)
            var prevLowFreqAvg = 0f
            var prevHighFreqPeak = 0f
            val smoothingFactor = 0.7f
            Log.d(TAG, "Audio processing coroutine started")
            while (isActive && audioEnginePtr != 0L) {
                try {
                    if (audioEnginePtr != 0L) { // Double-check engine state
                        Log.d(TAG, "Calling updateFrequencies with ptr=$audioEnginePtr")
                        updateFrequencies(hashCode().toLong(), audioEnginePtr, lowFreqData, highFreqData)
                        lowFreqAvg = lowFreqData.map { abs(it) }
                            .filter { it.isFinite() && it <= 1000f }
                            .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
                        highFreqPeak = highFreqData.map { abs(it) }
                            .filter { it.isFinite() && it <= 1000f }
                            .takeIf { it.isNotEmpty() }?.maxOrNull() ?: 0f
                        smoothedLowFreqAvg = (smoothingFactor * prevLowFreqAvg) + ((1f - smoothingFactor) * lowFreqAvg)
                        smoothedHighFreqPeak = (smoothingFactor * prevHighFreqPeak) + ((1f - smoothingFactor) * highFreqPeak)
                        prevLowFreqAvg = smoothedLowFreqAvg
                        prevHighFreqPeak = smoothedHighFreqPeak
                        Log.d("AudioDebug", "LowFreqAvg: $lowFreqAvg, HighFreqPeak: $highFreqPeak")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio update loop: ${e.message}", e)
                }
                delay(50)
            }
            Log.d(TAG, "Audio coroutine stopped")
        }
    }

    private fun stopAudioEngineSafe() {
        if (::audioScope.isInitialized && audioScope.isActive) {
            audioScope.cancel("Audio engine stopping")
            Log.d(TAG, "audioScope canceled during stopAudioEngineSafe")
        }
        if (audioEnginePtr != 0L) {
            Log.d(TAG, "Safely stopping AudioEngine, ptr=$audioEnginePtr")
            try {
                stopAudioEngine(hashCode().toLong(), audioEnginePtr)
                Log.d(TAG, "AudioEngine stopped successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioEngine: ${e.message}", e)
            }
            audioEnginePtr = 0L
        } else {
            Log.d(TAG, "No AudioEngine to stop (ptr=0)")
        }
    }

    private fun identifySong(callback: (String) -> Unit) {
        Log.i("SongID", "Starting song identification")
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            try {
                Log.i("SongID", "Recording audio")
                val audioData = recordAudio()
                Log.i("SongID", "Audio recorded, size=${audioData.size} bytes")
                val result = withContext(Dispatchers.IO) {
                    Log.i("SongID", "Sending to ACRCloud")
                    sendToAcrCloud(audioData)
                }
                Log.i("SongID", "Result received: $result")
                callback(result)
            } catch (e: Exception) {
                Log.e("SongID", "Error - ${e.message}", e)
                callback("Error identifying song: ${e.message}")
            }
        }
    }

    private fun recordAudio(): ByteArray {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is required to identify songs.")
        }

        val outputFile = File(cacheDir, "temp_audio.wav")
        val mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.DEFAULT)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }

        Thread.sleep(10_000)
        mediaRecorder.stop()
        mediaRecorder.release()

        val audioData = FileInputStream(outputFile).use { it.readBytes() }
        outputFile.delete()
        Log.i("SongID", "Recorded WAV audio, size=${audioData.size} bytes")
        return audioData
    }

    private suspend fun sendToAcrCloud(audioData: ByteArray): String {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        if (networkInfo == null || !networkInfo.isConnected) {
            Log.e("SongID", "No internet connection")
            return "No internet connection"
        }
        Log.i("SongID", "Network connected, proceeding with API call")

        val accessKey = apiKey
        val secretKey = this.secretKey
        val host = "identify-us-west-2.acrcloud.com"

        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signature = generateSignature(accessKey, secretKey, timestamp)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("sample", "audio.wav", audioData.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("access_key", accessKey)
            .addFormDataPart("data_type", "audio")
            .addFormDataPart("signature_version", "1")
            .addFormDataPart("signature", signature)
            .addFormDataPart("sample_bytes", audioData.size.toString())
            .addFormDataPart("timestamp", timestamp)
            .build()

        val request = Request.Builder()
            .url("https://$host/v1/identify")
            .post(requestBody)
            .build()

        Log.i("SongID", "Executing request to https://$host/v1/identify")
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e("SongID", "Network call failed: ${e.message}", e)
            return "Network error: ${e.message}"
        }
        val responseBody = response.body?.string() ?: "No response body"
        Log.i("SongID", "ACRCloud raw response: code=${response.code}, body=$responseBody")

        return when {
            response.code == 200 && (responseBody.contains("\"music\":[]") || !responseBody.contains("\"music\"")) -> {
                Log.i("SongID", "No song identified in response")
                "No song identified"
            }
            response.code == 200 -> {
                val title = responseBody.substringAfter("\"title\":\"").substringBefore("\"")
                val artist = responseBody.substringAfter("\"artists\":[{\"name\":\"").substringBefore("\"")
                if (title.isNotEmpty() && artist.isNotEmpty()) "$title by $artist" else "Song not identified"
            }
            response.code == 3014 -> {
                Log.e("SongID", "Invalid signature detected")
                "Error: Invalid signature"
            }
            else -> {
                Log.e("SongID", "Request failed with code: ${response.code}")
                "Error: ${response.code} - $responseBody"
            }
        }
    }

    private fun generateSignature(accessKey: String, secretKey: String, timestamp: String): String {
        val stringToSign = "POST\n/v1/identify\n$accessKey\naudio\n1\n$timestamp"
        Log.i("SongID", "Signature string: $stringToSign")

        val hmacSha1 = Mac.getInstance("HmacSHA1")
        val secretKeySpec = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1")
        hmacSha1.init(secretKeySpec)
        val signatureBytes = hmacSha1.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        Log.i("SongID", "Generated signature: $signature")

        return signature
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val currentTime = System.currentTimeMillis()
            val dt = if (lastUpdateTime == 0L) 0f else (currentTime - lastUpdateTime) / 1000f
            lastUpdateTime = currentTime

            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val accelX = it.values[0]
                    val accelY = it.values[1]
                    val accelZ = it.values[2]

                    val accelRoll = Math.toDegrees(Math.atan2(accelX.toDouble(), Math.sqrt((accelY * accelY + accelZ * accelZ).toDouble()))).toFloat()
                    val accelPitch = Math.toDegrees(Math.atan2(accelY.toDouble(), Math.sqrt((accelX * accelX + accelZ * accelZ).toDouble()))).toFloat()

                    if (dt > 0f) {
                        val alpha = 0.98f
                        fusedRoll = alpha * (fusedRoll + rollAngle * dt) + (1 - alpha) * accelRoll
                        fusedPitch = alpha * (fusedPitch + leanAngle * dt) + (1 - alpha) * accelPitch
                    } else {
                        fusedRoll = accelRoll
                        fusedPitch = accelPitch
                    }

                    motionX = -accelX / 9.81f
                    motionY = -accelZ / 9.81f
                    bumpEffect = if (abs(accelY) > 1.0f) (-accelY) * 75f else 0f
                    turnEffect = -accelX * 20f
                }
                Sensor.TYPE_GYROSCOPE -> {
                    leanAngle = it.values[1] * 57.2958f
                    rollAngle = it.values[0] * 57.2958f
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Stopping sensors and audio")
        sensorManager.unregisterListener(this)
        accelerometer = null
        gyroscope = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        stopAudioEngineSafe()
        lowFreqData.fill(0f)
        highFreqData.fill(0f)
        Log.d(TAG, "onPause: Audio stopped, buffers reset")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Restarting sensors and audio")
        val sharedPrefs = getSharedPreferences("CarBuddyPrefs", MODE_PRIVATE)
        emoji80 = sharedPrefs.getString("emoji80", "😈") ?: "😈"
        emoji60 = sharedPrefs.getString("emoji60", "🤘") ?: "🤘"
        emoji40 = sharedPrefs.getString("emoji40", "😎") ?: "😎"
        emoji0 = sharedPrefs.getString("emoji0", "🙂") ?: "🙂"
        backgroundColor = Color(sharedPrefs.getInt("background_color", 0xFFE8E13C.toInt()))
        backgroundImageUri = sharedPrefs.getString("background_image_uri", null)

        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, storagePermission) != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(storagePermission)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted on resume, requesting again")
            requestPermissions()
        } else {
            setupAudio()
        }

        setupSensors()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(
                LocationRequest.Builder(LocationRequest.PRIORITY_HIGH_ACCURACY, 1000)
                    .setMinUpdateIntervalMillis(500)
                    .build(),
                locationCallback,
                null
            )
        }
        lowFreqData.fill(0f)
        highFreqData.fill(0f)
        Log.d(TAG, "onResume: Setup audio completed")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Cleaning up")
        sensorManager.unregisterListener(this)
        accelerometer = null
        gyroscope = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        stopAudioEngineSafe()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Final cleanup")
        sensorManager.unregisterListener(this)
        accelerometer = null
        gyroscope = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        stopAudioEngineSafe()
    }
}