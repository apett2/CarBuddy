package com.alexpettit.carbuddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.Context
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.decode.GifDecoder
import android.os.Build
import androidx.compose.ui.layout.ContentScale

class CustomizeActivity : ComponentActivity() {
    private var onPermissionDialogStateChange: (Boolean) -> Unit = {}

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val sharedPrefs = getSharedPreferences("CarBuddyPrefs", Context.MODE_PRIVATE)
            with(sharedPrefs.edit()) {
                putString("background_image_uri", it.toString())
                apply()
            }
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Log.d("CarBuddy", "Persistable URI permission granted for: $it")
            } catch (e: SecurityException) {
                Log.e("CarBuddy", "Failed to take persistable URI permission: ${e.message}")
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            pickImageLauncher.launch("image/*")
        } else {
            onPermissionDialogStateChange(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CustomizeUI(isUnlocked = true)
        }
    }

    @Composable
    fun CustomizeUI(isUnlocked: Boolean) {
        val context = LocalContext.current
        val sharedPrefs = getSharedPreferences("CarBuddyPrefs", Context.MODE_PRIVATE)
        var selectedBackgroundColor by remember { mutableStateOf(Color(sharedPrefs.getInt("background_color", 0xFFE8E13C.toInt()))) }
        var selectedBackgroundImageUri by remember { mutableStateOf<Uri?>(null) }
        var emoji80 by remember { mutableStateOf(sharedPrefs.getString("emoji80", "😈") ?: "😈") }
        var emoji60 by remember { mutableStateOf(sharedPrefs.getString("emoji60", "🤘") ?: "🤘") }
        var emoji40 by remember { mutableStateOf(sharedPrefs.getString("emoji40", "😎") ?: "😎") }
        var emoji0 by remember { mutableStateOf(sharedPrefs.getString("emoji0", "🙂") ?: "🙂") }
        var showPermissionDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            onPermissionDialogStateChange = { newState -> showPermissionDialog = newState }
            val savedUriString = sharedPrefs.getString("background_image_uri", null)
            if (savedUriString != null) {
                selectedBackgroundImageUri = Uri.parse(savedUriString)
            }
        }

        val scrollState = rememberScrollState()

        Box(modifier = Modifier.fillMaxSize().background(selectedBackgroundColor)) {
            if (selectedBackgroundImageUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(selectedBackgroundImageUri)
                        .decoderFactory(GifDecoder.Factory())
                        .build(),
                    contentDescription = "Background Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Customize Car Buddy",
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp)
                )

                Text("Background Color", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.heightIn(min = 240.dp, max = 300.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val paletteColors = listOf(
                        Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFFFFFF00),
                        Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFF800000), Color(0xFF008000),
                        Color(0xFF000080), Color(0xFF808000), Color(0xFF800080), Color(0xFF008080),
                        Color(0xFFFFA500), Color(0xFF4B0082), Color(0xFFEE82EE), Color(0xFF40E0D0)
                    )
                    items(paletteColors.size) { index ->
                        val color = paletteColors[index]
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(color, shape = CircleShape)
                                .clickable(enabled = isUnlocked) {
                                    selectedBackgroundColor = color
                                    selectedBackgroundImageUri = null
                                    sharedPrefs.edit().remove("background_image_uri").apply()
                                }
                                .then(
                                    if (selectedBackgroundColor == color && selectedBackgroundImageUri == null) {
                                        Modifier.border(3.dp, Color.White, CircleShape)
                                    } else Modifier
                                )
                                .padding(2.dp)
                        ) {
                            if (selectedBackgroundColor == color && selectedBackgroundImageUri == null) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    PreconfiguredColorOption(Color(0xFFE8E13C), selectedBackgroundColor == Color(0xFFE8E13C) && selectedBackgroundImageUri == null, isUnlocked) {
                        selectedBackgroundColor = Color(0xFFE8E13C)
                        selectedBackgroundImageUri = null
                        sharedPrefs.edit().remove("background_image_uri").apply()
                    }
                    PreconfiguredColorOption(Color.Blue, selectedBackgroundColor == Color.Blue && selectedBackgroundImageUri == null, isUnlocked) {
                        selectedBackgroundColor = Color.Blue
                        selectedBackgroundImageUri = null
                        sharedPrefs.edit().remove("background_image_uri").apply()
                    }
                    PreconfiguredColorOption(Color.Red, selectedBackgroundColor == Color.Red && selectedBackgroundImageUri == null, isUnlocked) {
                        selectedBackgroundColor = Color.Red
                        selectedBackgroundImageUri = null
                        sharedPrefs.edit().remove("background_image_uri").apply()
                    }
                    PreconfiguredColorOption(Color.Green, selectedBackgroundColor == Color.Green && selectedBackgroundImageUri == null, isUnlocked) {
                        selectedBackgroundColor = Color.Green
                        selectedBackgroundImageUri = null
                        sharedPrefs.edit().remove("background_image_uri").apply()
                    }
                }

                val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                val hasPermission = ContextCompat.checkSelfPermission(context, permissionToRequest) == PackageManager.PERMISSION_GRANTED

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isUnlocked) {
                                if (hasPermission) {
                                    pickImageLauncher.launch("image/*")
                                } else {
                                    requestPermissionLauncher.launch(permissionToRequest)
                                }
                            }
                        },
                        enabled = isUnlocked
                    ) {
                        Text("Choose Image/GIF")
                    }
                    Button(
                        onClick = {
                            if (isUnlocked) {
                                selectedBackgroundImageUri = null
                                with(sharedPrefs.edit()) {
                                    remove("background_image_uri")
                                    apply()
                                }
                                Log.d("CarBuddy", "Removed background image")
                            }
                        },
                        enabled = isUnlocked && selectedBackgroundImageUri != null
                    ) {
                        Text("Remove Image")
                    }
                }

                if (showPermissionDialog) {
                    AlertDialog(
                        onDismissRequest = { showPermissionDialog = false },
                        title = { Text("Permission Required") },
                        text = { Text("This app needs storage permission to access your photos and GIFs.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showPermissionDialog = false
                                requestPermissionLauncher.launch(permissionToRequest)
                            }) { Text("Allow") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Emojis", style = MaterialTheme.typography.bodyLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EmojiSelector("0-39 mph", emoji0, isUnlocked) { if (isUnlocked) emoji0 = it }
                        EmojiSelector("40-59 mph", emoji40, isUnlocked) { if (isUnlocked) emoji40 = it }
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EmojiSelector("60-79 mph", emoji60, isUnlocked) { if (isUnlocked) emoji60 = it }
                        EmojiSelector("80+ mph", emoji80, isUnlocked) { if (isUnlocked) emoji80 = it }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        with(sharedPrefs.edit()) {
                            putInt("background_color", selectedBackgroundColor.hashCode())
                            putString("emoji80", emoji80)
                            putString("emoji60", emoji60)
                            putString("emoji40", emoji40)
                            putString("emoji0", emoji0)
                            Log.d("CarBuddy", "Saving: backgroundColor = $selectedBackgroundColor, emoji80 = $emoji80")
                            apply()
                        }
                        finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF800080))
                ) {
                    Text("Save Changes", color = Color.White)
                }
            }
        }
    }

    @Composable
    fun PreconfiguredColorOption(color: Color, isSelected: Boolean, isUnlocked: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(if (isUnlocked) color else Color.LightGray.copy(alpha = 0.5f), shape = CircleShape)
                .clickable(enabled = isUnlocked, onClick = onClick)
                .then(if (isSelected) Modifier.border(4.dp, Color.White, CircleShape) else Modifier)
                .padding(4.dp)
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    @Composable
    fun EmojiSelector(label: String, currentEmoji: String, isUnlocked: Boolean, onEmojiSelected: (String) -> Unit) {
        val emojiOptions = listOf("😈", "🚗", "🔥", "🤘", "😎", "🙂", "🎉")
        var expanded by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$label: ", style = MaterialTheme.typography.bodyMedium)
                Text(currentEmoji, style = MaterialTheme.typography.bodyLarge, fontSize = 24.sp)
            }
            Box {
                Button(
                    onClick = { if (isUnlocked) expanded = true },
                    enabled = isUnlocked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isUnlocked) MaterialTheme.colorScheme.primary else Color.LightGray
                    )
                ) {
                    Text("Change", color = if (isUnlocked) Color.White else Color.Black)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    emojiOptions.forEach { emoji ->
                        DropdownMenuItem(
                            text = { Text(emoji, fontSize = 24.sp) },
                            onClick = {
                                onEmojiSelected(emoji)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}