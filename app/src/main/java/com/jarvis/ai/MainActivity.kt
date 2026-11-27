package com.jarvis.ai

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

data class UpdateInfo(
    val available: Boolean,
    val title: String,
    val apkUrl: String,
    val updatedAt: String
)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                JarvisApp(
                    onSpeak = { text -> speakHindi(text) },
                    onStopSpeak = { tts?.stop() }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(0.95f)
            isTtsReady = true
        }
    }

    private fun speakHindi(text: String) {
        if (isTtsReady) {
            val cleanText = text.replace("*", "").replace("#", "")
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun JarvisApp(
    onSpeak: (String) -> Unit,
    onStopSpeak: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var showSettings by remember { mutableStateOf(apiKey.isBlank()) }

    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var voiceOutputEnabled by remember { mutableStateOf(true) }
    var selectedMode by remember { mutableStateOf("JARVIS") }
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }

    val messages = remember {
        mutableStateListOf(
            ChatMessage("नमस्ते लेखराम जी! मैं JARVIS हूँ। Auto-Update और स्मार्ट कंट्रोल सिस्टम चालू है।", isUser = false)
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Automatic Update Check on Startup
    LaunchedEffect(Unit) {
        val lastInstalledDate = prefs.getString("last_installed_update", "") ?: ""
        val update = checkGitHubUpdate(lastInstalledDate)
        if (update != null && update.available) {
            pendingUpdate = update
            messages.add(ChatMessage("सर, JARVIS का नया अपडेट (${update.title}) उपलब्ध है! ऊपर अपडेट आइकन या डायलॉग से 1-क्लिक में इंस्टॉल करें।", isUser = false))
        }
    }

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> "GOOD MORNING, LEKHRAM"
            hour < 17 -> "GOOD AFTERNOON, LEKHRAM"
            else -> "GOOD EVENING, LEKHRAM"
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "core")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = if (isListening || isThinking) 1.15f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 500 else 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    fun triggerAutoUpdate(update: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "कृपया 'Install unknown apps' की अनुमति दें", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
            return
        }
        isDownloadingUpdate = true
        Toast.makeText(context, "नया अपडेट डाउनलोड हो रहा है...", Toast.LENGTH_SHORT).show()
        downloadAndInstallApk(context, update.apkUrl) { success ->
            isDownloadingUpdate = false
            if (success) {
                prefs.edit().putString("last_installed_update", update.updatedAt).apply()
                pendingUpdate = null
            }
        }
    }

    fun sendQuery(query: String) {
        if (query.isBlank()) return
        messages.add(ChatMessage(query, isUser = true))
        inputText = ""

        val qLower = query.lowercase(Locale.getDefault())
        if (qLower.contains("update") || qLower.contains("अपडेट")) {
            coroutineScope.launch {
                isThinking = true
                val update = checkGitHubUpdate("")
                isThinking = false
                if (update != null && update.apkUrl.isNotBlank()) {
                    pendingUpdate = update
                    val msg = "जी लेखराम जी, लेटेस्ट वर्जन (${update.title}) मिल गया है। डाउनलोड शुरू कर रहा हूँ।"
                    messages.add(ChatMessage(msg, isUser = false))
                    if (voiceOutputEnabled) onSpeak(msg)
                    triggerAutoUpdate(update)
                } else {
                    val msg = "आपका JARVIS पहले से ही लेटेस्ट वर्जन पर है सर।"
                    messages.add(ChatMessage(msg, isUser = false))
                    if (voiceOutputEnabled) onSpeak(msg)
                }
                listState.animateScrollToItem(messages.size - 1)
            }
            return
        }

        // Check for Phase 2 Phone Automation Commands
        val deviceReply = handleDeviceCommand(context, query)
        if (deviceReply != null) {
            messages.add(ChatMessage(deviceReply, isUser = false))
            coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
            if (voiceOutputEnabled) onSpeak(deviceReply)
            return
        }

        if (apiKey.isBlank()) {
            showSettings = true
            Toast.makeText(context, "कृपया पहले Gemini API Key डालें", Toast.LENGTH_SHORT).show()
            return
        }

        isThinking = true

        coroutineScope.launch {
            listState.animateScrollToItem(messages.size - 1)
            val reply = askGemini(apiKey, query, selectedMode)
            isThinking = false
            messages.add(ChatMessage(reply, isUser = false))
            listState.animateScrollToItem(messages.size - 1)
            if (voiceOutputEnabled) {
                onSpeak(reply)
            }
        }
    }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) {
        onDispose { speechRecognizer.destroy() }
    }

    fun startVoiceRecognition() {
        onStopSpeak()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                Toast.makeText(context, "आवाज़ साफ नहीं आई, फिर से बोलें", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    sendQuery(spoken)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) inputText = partial
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecognition()
        else Toast.makeText(context, "माइक की परमिशन ज़रूरी है", Toast.LENGTH_SHORT).show()
    }

    val background = Color(0xFF050B16)
    val cyan = Color(0xFF45E6FF)

    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF10243A), background, Color(0xFF02050B))
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("J.A.R.V.I.S", color = cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(greeting, color = Color.LightGray, fontSize = 10.sp, letterSpacing = 1.5.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isDownloadingUpdate) "UPDATING..." else if (isListening) "LISTENING..." else if (isThinking) "THINKING..." else "ONLINE",
                        color = if (isListening || isDownloadingUpdate) Color(0xFFFF6B6B) else Color(0xFF8EF7C5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        coroutineScope.launch {
                            Toast.makeText(context, "अपडेट चेक कर रहा है...", Toast.LENGTH_SHORT).show()
                            val upd = checkGitHubUpdate("")
                            if (upd != null && upd.apkUrl.isNotBlank()) {
                                pendingUpdate = upd
                            } else {
                                Toast.makeText(context, "कोई नया अपडेट नहीं है", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = "Check Update",
                            tint = if (pendingUpdate != null) Color(0xFFFFD54F) else cyan
                        )
                    }
                    IconButton(onClick = {
                        voiceOutputEnabled = !voiceOutputEnabled
                        if (!voiceOutputEnabled) onStopSpeak()
                    }) {
                        Icon(
                            if (voiceOutputEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Voice Toggle",
                            tint = cyan
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = cyan)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("JARVIS" to "🤖 AI Brain", "STUDY" to "📚 Study", "TRANSLATOR" to "🌐 Translate").forEach { (mode, label) ->
                    val isSelected = selectedMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) cyan.copy(alpha = 0.25f) else Color(0xFF0D1B2A),
                                RoundedCornerShape(12.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) cyan else Color(0xFF1E3A5F),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedMode = mode }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (isSelected) cyan else Color.LightGray, fontSize = 12.sp)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(105.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(pulseScale)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    if (isListening) Color(0x88FF5252) else Color(0x8845E6FF),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                        .clickable {
                            if (isListening) {
                                speechRecognizer.stopListening()
                                isListening = false
                            } else {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    startVoiceRecognition()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "◉",
                        color = if (isListening) Color(0xFFFF6B6B) else cyan,
                        fontSize = 52.sp
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 290.dp)
                                .background(
                                    if (msg.isUser) Color(0xFF087E9B) else Color(0xFF10243A),
                                    RoundedCornerShape(16.dp)
                                )
                                .border(
                                    1.dp,
                                    if (msg.isUser) cyan.copy(alpha = 0.5f) else Color(0xFF1E3A5F),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = msg.text,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("JARVIS से पूछें या कमांड दें...", color = Color.Gray, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cyan,
                        unfocusedBorderColor = Color(0xFF1E3A5F),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        if (isListening) {
                            speechRecognizer.stopListening()
                            isListening = false
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startVoiceRecognition()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(if (isListening) Color(0xFFD32F2F) else Color(0xFF10243A), CircleShape)
                        .border(1.dp, cyan, CircleShape)
                ) {
                    Icon(
                        if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = "Mic",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = { sendQuery(inputText) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF087E9B), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }

    // 1-Click Auto Update Dialog
    pendingUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { pendingUpdate = null },
            title = { Text("🚀 नया JARVIS अपडेट उपलब्ध!", color = cyan) },
            text = {
                Text(
                    "वर्जन: ${update.title}\nक्या आप अभी 1-क्लिक में नया अपडेट डाउनलोड और इंस्टॉल करना चाहते हैं?",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(onClick = { triggerAutoUpdate(update) }) {
                    Text("Update Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUpdate = null }) {
                    Text("बाद में", color = Color.Gray)
                }
            }
        )
    }

    if (showSettings) {
        var tempKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { if (apiKey.isNotBlank()) showSettings = false },
            title = { Text("JARVIS Brain Setup", color = cyan) },
            text = {
                Column {
                    Text(
                        "अपनी मुफ्त Google Gemini API Key यहाँ पेस्ट करें (aistudio.google.com से बनाएं):",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        label = { Text("Gemini API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    apiKey = tempKey.trim()
                    prefs.edit().putString("gemini_api_key", apiKey).apply()
                    showSettings = false
                    Toast.makeText(context, "API Key सेव हो गई!", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Save")
                }
            }
        )
    }
}

suspend fun checkGitHubUpdate(lastInstalledAt: String): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
        val url = "https://api.github.com/repos/meenalekhram98-source/JARVIS-AI-Android/releases/latest"
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val json = JSONObject(res.body?.string() ?: "")
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk")) {
                    val apkUrl = asset.optString("browser_download_url")
                    val updatedAt = asset.optString("updated_at")
                    val body = json.optString("body", "Latest Build")
                    val isNew = lastInstalledAt.isBlank() || updatedAt != lastInstalledAt
                    return@withContext UpdateInfo(isNew, body, apkUrl, updatedAt)
                }
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}

fun downloadAndInstallApk(context: Context, apkUrl: String, onDone: (Boolean) -> Unit) {
    try {
        val fileName = "jarvis_update.apk"
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("JARVIS AI Update")
            setDescription("Downloading latest JARVIS version...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                if (id == downloadId) {
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    onDone(true)
                    val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(installIntent)
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    } catch (e: Exception) {
        Toast.makeText(context, "अपडेट डाउनलोड एरर: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        onDone(false)
    }
}

fun handleDeviceCommand(context: Context, rawQuery: String): String? {
    val q = rawQuery.lowercase(Locale.getDefault()).trim()

    // 1. Flashlight / Torch Control
    if (q.contains("torch") || q.contains("flashlight") || q.contains("टॉर्च") || q.contains("फ्लैशलाइट") || q.contains("लाइट")) {
        val turnOn = q.contains("on") || q.contains("चालू") || q.contains("जला") || q.contains("ओपन") || q.contains("खोल")
        val turnOff = q.contains("off") || q.contains("बंद") || q.contains("बुझा")
        if (turnOn || turnOff) {
            return try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, turnOn)
                    if (turnOn) "जी लेखराम जी, टॉर्च ऑन कर दी गई है।" else "जी लेखराम जी, टॉर्च बंद कर दी गई है।"
                } else {
                    "माफ़ कीजिए सर, इस फोन में टॉर्च कंट्रोल उपलब्ध नहीं है।"
                }
            } catch (e: Exception) {
                "टॉर्च चालू करने में समस्या आई: ${e.localizedMessage}"
            }
        }
    }

    // 2. Settings Shortcuts
    if (q.contains("wifi") || q.contains("wi-fi") || q.contains("वाईफाई")) {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "जी सर, Wi-Fi सेटिंग्स खोल रहा हूँ।"
    }
    if (q.contains("bluetooth") || q.contains("ब्लूटूथ")) {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "जी सर, Bluetooth सेटिंग्स खोल रहा हूँ।"
    }
    if (q.contains("settings") || q.contains("सेटिंग")) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "जी सर, फोन की Settings खोल रहा हूँ।"
    }

    // 3. WhatsApp Direct Message
    if ((q.contains("whatsapp") || q.contains("व्हाट्सएप")) && (q.contains("भेज") || q.contains("send") || q.contains("message") || q.contains("मैसेज"))) {
        val digits = rawQuery.filter { it.isDigit() }
        if (digits.length >= 10) {
            val phone = if (digits.length == 10) "91$digits" else digits
            val msgText = rawQuery.replace(Regex("(?i)(whatsapp|व्हाट्सएप|पर|को|मैसेज|भेजो|भेज|send|message)"), "")
                .replace(Regex("[0-9+\\-\\s]{10,}"), "")
                .trim()
                .ifBlank { "नमस्ते" }
            return try {
                val url = "https://api.whatsapp.com/send?phone=$phone&text=${URLEncoder.encode(msgText, "UTF-8")}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "जी लेखराम जी, $digits के लिए WhatsApp मैसेज तैयार कर दिया है।"
            } catch (_: Exception) {
                "WhatsApp खोलने में समस्या आई।"
            }
        }
    }

    // 4. Phone Call
    if (q.contains("call") || q.contains("कॉल") || q.contains("फोन लगा")) {
        val digits = rawQuery.filter { it.isDigit() }
        if (digits.length >= 10) {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "जी सर, $digits पर कॉल लगा रहा हूँ।"
        } else {
            val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "जी सर, फोन डायलर खोल रहा हूँ।"
        }
    }

    // 5. Open Popular Apps
    val openKeywords = listOf("open", "खोलो", "खोल", "चलाओ", "ओपन")
    if (openKeywords.any { q.contains(it) }) {
        if (q.contains("camera") || q.contains("कैमरा")) {
            context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "जी लेखराम जी, कैमरा खोल रहा हूँ।"
        }
        val appPackages = mapOf(
            listOf("youtube", "यूट्यूब") to ("YouTube" to "com.google.android.youtube"),
            listOf("whatsapp", "व्हाट्सएप") to ("WhatsApp" to "com.whatsapp"),
            listOf("chrome", "क्रोम", "browser") to ("Chrome" to "com.android.chrome"),
            listOf("maps", "मैप", "नक्शा") to ("Google Maps" to "com.google.android.apps.maps"),
            listOf("instagram", "इंस्टाग्राम") to ("Instagram" to "com.instagram.android"),
            listOf("facebook", "फेसबुक") to ("Facebook" to "com.facebook.katana"),
            listOf("calculator", "कैलकुलेटर") to ("Calculator" to "com.google.android.calculator")
        )
        for ((keys, appInfo) in appPackages) {
            if (keys.any { q.contains(it) }) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(appInfo.second)
                return if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    "जी लेखराम जी, ${appInfo.first} खोल रहा हूँ।"
                } else {
                    "माफ़ कीजिए सर, आपके फोन में ${appInfo.first} इंस्टॉल नहीं है।"
                }
            }
        }
    }

    return null
}

suspend fun findActiveModel(client: OkHttpClient, cleanKey: String): String? {
    return try {
        val listUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey"
        val req = Request.Builder().url(listUrl).get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val json = JSONObject(res.body?.string() ?: "")
            val models = json.optJSONArray("models") ?: return null
            val validModels = mutableListOf<String>()
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                val name = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent") && name.contains("gemini") && !name.contains("embedding") && !name.contains("image")) {
                    validModels.add(name)
                }
            }
            validModels.firstOrNull { it.contains("flash") } ?: validModels.firstOrNull()
        }
    } catch (_: Exception) {
        null
    }
}

suspend fun askGemini(apiKey: String, userMessage: String, mode: String): String = withContext(Dispatchers.IO) {
    val cleanKey = apiKey.trim()
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val detectedModel = findActiveModel(client, cleanKey)
    val modelsToTry = listOfNotNull(detectedModel, "gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-flash-preview").distinct()

    val ownerIdentity = "Your creator and owner (malik) is Lekhram Meena (लेखराम मीना). Always address him respectfully as 'लेखराम जी' or 'सर' when appropriate. If anyone asks who your owner/creator is or asks 'mera naam kya hai', clearly state that his name is Lekhram Meena and he is your owner and creator."

    val systemInstruction = when (mode) {
        "STUDY" -> "$ownerIdentity You are JARVIS in Study Mode. Explain concepts clearly in simple Hindi/Hinglish with short examples like a great tutor."
        "TRANSLATOR" -> "$ownerIdentity You are JARVIS Translator. If the user writes in Hindi, translate it to natural English. If in English or another language, translate it to clear Hindi."
        else -> "$ownerIdentity You are JARVIS, a smart, loyal, and polite personal AI assistant. Answer concisely in natural Hindi/Hinglish. Keep answers brief so they sound good when spoken aloud."
    }

    var lastError = ""
    for (model in modelsToTry) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
            val jsonBody = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                }))
            }

            val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseText)
                    val candidates = json.optJSONArray("candidates")
                    val content = candidates?.optJSONObject(0)?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val reply = parts?.optJSONObject(0)?.optString("text")?.trim()
                    if (!reply.isNullOrBlank()) return@withContext reply
                } else {
                    val errMsg = try {
                        JSONObject(responseText).optJSONObject("error")?.optString("message") ?: responseText
                    } catch (_: Exception) { responseText }
                    lastError = "Error ${response.code}: $errMsg"
                }
            }
        } catch (e: Exception) {
            lastError = e.localizedMessage ?: "Network error"
        }
    }
    return@withContext "समस्या आई ($lastError)"
}

