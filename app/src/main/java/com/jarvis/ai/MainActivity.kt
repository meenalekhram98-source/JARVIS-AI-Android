package com.jarvis.ai

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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

data class ChatMessage(val text: String, val isUser: Boolean)

data class UpdateInfo(val available: Boolean, val title: String, val apkUrl: String, val updatedAt: String)
var onSpeechFinishedCallback: (() -> Unit)? = null

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            tts = TextToSpeech(this, this)
        } catch (_: Exception) {}

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                JarvisApp(
                    onSpeak = { text -> speakHindi(text) },
                    onStopSpeak = { try { tts?.stop() } catch (_: Exception) {} }
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
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { onSpeechFinishedCallback?.invoke() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
            isTtsReady = true
        }
    }

    private fun speakHindi(text: String) {
        if (!isTtsReady) return
        try {
            val prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
            tts?.setPitch(prefs.getFloat("voice_pitch", 0.95f))
            tts?.setSpeechRate(prefs.getFloat("voice_rate", 1.05f))
            val cleanText = text.replace("*", "").replace("#", "").trim()
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_utt")
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "jarvis_utt")
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
@Composable
fun JarvisApp(onSpeak: (String) -> Unit, onStopSpeak: () -> Unit) {
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
    var pendingContactQuery by remember { mutableStateOf<String?>(null) }

    val messages = remember {
        mutableStateListOf(
            ChatMessage("नमस्ते लेखराम जी! मैं JARVIS हूँ। अब मैं बिना किसी सेटअप के खुद नई स्किल्स और बातें सीख सकता हूँ।", isUser = false)
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    fun triggerAutoUpdate(update: UpdateInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "कृपया 'Install unknown apps' की अनुमति दें", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
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
        } catch (e: Exception) {
            isDownloadingUpdate = false
            Toast.makeText(context, "अपडेट एरर: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        try {
            val lastInstalledDate = prefs.getString("last_installed_update", "") ?: ""
            val update = checkGitHubUpdate(lastInstalledDate)
            if (update != null && update.available) {
                pendingUpdate = update
                messages.add(ChatMessage("सर, JARVIS का नया अपडेट (${update.title}) उपलब्ध है! डाउनलोड शुरू कर रहा हूँ...", isUser = false))
                triggerAutoUpdate(update)
            }
        } catch (_: Exception) {}
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

    var retryQueryCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val savedQuery = pendingContactQuery
        pendingContactQuery = null
        if (granted && savedQuery != null) {
            retryQueryCallback?.invoke(savedQuery)
        } else if (!granted) {
            val msg = "कॉन्टैक्ट नाम से कॉल या मैसेज करने के लिए Contacts परमिशन ज़रूरी है सर।"
            messages.add(ChatMessage(msg, isUser = false))
            if (voiceOutputEnabled) onSpeak(msg)
        }
    }

    fun sendQuery(query: String) {
        if (query.isBlank()) return
        val lastMsg = messages.lastOrNull()
        if (lastMsg == null || lastMsg.text != query || !lastMsg.isUser) {
            messages.add(ChatMessage(query, isUser = true))
        }
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

        val cmdResult = try {
            handleDeviceCommand(context, query)
        } catch (e: Exception) {
            "कमांड चलाने में समस्या आई: ${e.localizedMessage}"
        }

        if (cmdResult == "NEED_CONTACTS_PERMISSION") {
            pendingContactQuery = query
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        if (cmdResult != null) {
            messages.add(ChatMessage(cmdResult, isUser = false))
            coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
            if (voiceOutputEnabled) onSpeak(cmdResult)
            return
        }

        

        isThinking = true
        coroutineScope.launch {
            listState.animateScrollToItem(messages.size - 1)
            val reply = askGemini(context, apiKey, query, selectedMode)
            isThinking = false
            messages.add(ChatMessage(reply, isUser = false))
            listState.animateScrollToItem(messages.size - 1)
            if (voiceOutputEnabled) onSpeak(reply)
        }
    }

    LaunchedEffect(Unit) {
        retryQueryCallback = { q -> sendQuery(q) }
    }
    val speechRecognizer = remember {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else null
        } catch (_: Exception) { null }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
        }
    }

    fun startVoiceRecognition() {
        val sr = speechRecognizer ?: return
        onStopSpeak()
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            sr.setRecognitionListener(object : RecognitionListener {
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
                    if (!spoken.isNullOrBlank()) sendQuery(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) inputText = partial
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(intent)
        } catch (_: Exception) {
            isListening = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecognition()
        else Toast.makeText(context, "माइक की परमिशन ज़रूरी है", Toast.LENGTH_SHORT).show()
    }

          LaunchedEffect(Unit) {
     onSpeechFinishedCallback = {
         if (prefs.getBoolean("auto_listen", false) && !isListening) startVoiceRecognition()
     }
          } 

     

    val background = Color(0xFF050B16)
    val cyan = Color(0xFF45E6FF)

    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF10243A), background, Color(0xFF02050B))))
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
                            if (upd != null && upd.apkUrl.isNotBlank()) triggerAutoUpdate(upd)
                            else Toast.makeText(context, "कोई नया अपडेट नहीं है", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = "Check Update", tint = if (pendingUpdate != null) Color(0xFFFFD54F) else cyan)
                    }
                    IconButton(onClick = {
                        voiceOutputEnabled = !voiceOutputEnabled
                        if (!voiceOutputEnabled) onStopSpeak()
                    }) {
                        Icon(if (voiceOutputEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff, contentDescription = "Voice Toggle", tint = cyan)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = cyan)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("JARVIS" to "🤖 AI Brain", "STUDY" to "📚 Study", "TRANSLATOR" to "🌐 Translate").forEach { (mode, label) ->
                    val isSelected = selectedMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isSelected) cyan.copy(alpha = 0.25f) else Color(0xFF0D1B2A), RoundedCornerShape(12.dp))
                            .border(1.dp, if (isSelected) cyan else Color(0xFF1E3A5F), RoundedCornerShape(12.dp))
                            .clickable { selectedMode = mode }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (isSelected) cyan else Color.LightGray, fontSize = 12.sp)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(105.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(pulseScale)
                        .background(Brush.radialGradient(listOf(if (isListening) Color(0x88FF5252) else Color(0x8845E6FF), Color.Transparent)), CircleShape)
                        .clickable {
                            if (isListening) {
                                try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                                isListening = false
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startVoiceRecognition()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("◉", color = if (isListening) Color(0xFFFF6B6B) else cyan, fontSize = 52.sp)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 290.dp)
                                .background(if (msg.isUser) Color(0xFF087E9B) else Color(0xFF10243A), RoundedCornerShape(16.dp))
                                .border(1.dp, if (msg.isUser) cyan.copy(alpha = 0.5f) else Color(0xFF1E3A5F), RoundedCornerShape(16.dp))
                                .padding(12.dp)
                        ) {
                            Text(text = msg.text, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("JARVIS से पूछें या नई स्किल सिखाएं...", color = Color.Gray, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = cyan, unfocusedBorderColor = Color(0xFF1E3A5F), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (isListening) {
                            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                            isListening = false
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startVoiceRecognition()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.size(48.dp).background(if (isListening) Color(0xFFD32F2F) else Color(0xFF10243A), CircleShape).border(1.dp, cyan, CircleShape)
                ) {
                    Icon(if (isListening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = "Mic", tint = Color.White)
                }
                IconButton(
                    onClick = { sendQuery(inputText) },
                    modifier = Modifier.size(48.dp).background(Color(0xFF087E9B), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
        
        }
    }

    if (showSettings) {
        var tempKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { if (apiKey.isNotBlank()) showSettings = false },
            title = { Text("JARVIS Brain Setup", color = cyan) },
            text = {
                Column {
                    Text("अपनी मुफ्त Google Gemini API Key यहाँ पेस्ट करें (aistudio.google.com से बनाएं):", fontSize = 13.sp, color = Color.LightGray)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = tempKey, onValueChange = { tempKey = it }, label = { Text("Gemini API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    apiKey = tempKey.trim()
                    prefs.edit().putString("gemini_api_key", apiKey).apply()
                    showSettings = false
                    Toast.makeText(context, "API Key सेव हो गई!", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
            }
        )
    }
}
}
suspend fun checkGitHubUpdate(lastInstalledAt: String): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("https://api.github.com/repos/meenalekhram98-source/JARVIS-AI-Android/releases/latest").header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val json = JSONObject(res.body?.string() ?: "")
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk")) {
                    val apkUrl = asset.optString("browser_download_url")
                    val updatedAt = asset.optString("updated_at")
                    val body = json.optString("body", "Latest Build")
                    return@withContext UpdateInfo(lastInstalledAt.isBlank() || updatedAt != lastInstalledAt, body, apkUrl, updatedAt)
                }
            }
            null
        }
    } catch (_: Exception) { null }
}

fun downloadAndInstallApk(context: Context, apkUrl: String, onDone: (Boolean) -> Unit) {
    try {
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "jarvis_update.apk")
        if (destFile.exists()) destFile.delete()
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("JARVIS AI Update")
            setDescription("Downloading latest JARVIS version...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "jarvis_update.apk")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    onDone(true)
                    val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    } catch (e: Exception) {
        Toast.makeText(context, "अपडेट डाउनलोड एरर: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        onDone(false)
    }
}

suspend fun findActiveModel(client: OkHttpClient, cleanKey: String): String? {
    return try {
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey").get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val models = JSONObject(res.body?.string() ?: "").optJSONArray("models") ?: return null
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
    } catch (_: Exception) { null }
}

suspend fun askGemini(context: Context, apiKey: String, userMessage: String, mode: String): String = withContext(Dispatchers.IO) {
    val cleanKey = apiKey.trim()
    val prefs = context.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)
    val learnedFacts = prefs.getString("learned_facts", "") ?: ""
    val learnedSkills = prefs.getString("learned_skills", "") ?: ""
    val recentHistory = prefs.getString("recent_chat", "") ?: ""

    val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
           val modelsToTry = if (cleanKey.isBlank()) emptyList() else listOf(

        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite"
    )      


    val ownerIdentity = """
        Your creator and owner (malik) is Lekhram Meena (लेखराम मीना). Always address him respectfully as 'लेखराम जी' or 'सर'.
        You are an INSTANT SELF-EVOLVING, self-learning AI assistant.
        
        Learned facts & personal memory about Lekhram ji:
        $learnedFacts
        
        Custom skills & abilities you have taught yourself:
        $learnedSkills
        
        Recent conversation context:
        $recentHistory
        
        SELF-EVOLUTION & ACTION TAGS (Append at the very end of your reply when needed; never mention tags aloud):
        1. If the user shares any new personal preference, habit, person, or fact: append [LEARN: short fact]
        2. If the user asks you to add a new skill, rule, behavior, or capability (e.g. "नया फीचर जोड़ो", "ये सीख लो", "अब से ऐसा करना"): append [SKILL: clear rule/capability] and confirm you have upgraded yourself immediately!
        3. If the user wants to search anything live on Google or open a web service/site to fulfill a task: append [SEARCH: search query]
                4. For ANY phone hardware, system setting, volume, brightness, battery, storage, or Android Intent action, append:
        [ACTION: {"type":"BATTERY"}] or {"type":"STORAGE"} or {"type":"DEVICE_INFO"} or {"type":"VOLUME","action":"up/down/mute/max","percent":70} or {"type":"BRIGHTNESS","percent":50} or {"type":"INTENT","action":"android.settings.AIRPLANE_MODE_SETTINGS","uri":"","package":"","reply":"जी लेखराम जी, सेटिंग खोल दी है।"} or {"type":"OPEN_APP","package":"com.package.name","reply":"जी सर, खोल रहा हूँ।"}
    
    5. If the user asks to change your voice, pitch, speed, or hands-free/auto-listen mode: append [VOICE: male], [VOICE: female], [VOICE: fast], [VOICE: normal], [AUTOLISTEN: on], or [AUTOLISTEN: off] at the end.
""".trimIndent()

        val systemInstruction = when (mode) {
        "STUDY" -> "$ownerIdentity You are JARVIS in STUDY MODE. Explain concepts clearly in simple Hindi/Hinglish with bullet points and examples."
        "TRANSLATOR" -> "$ownerIdentity You are JARVIS in TRANSLATOR MODE. Translate user input cleanly between Hindi and English without extra commentary."
        else -> "$ownerIdentity You are JARVIS, a smart, loyal, and concise personal AI assistant speaking natural Hindi/Hinglish."
    }

    var lastError = ""
    for (model in modelsToTry) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
            val jsonBody = JSONObject().apply {
                if (model.startsWith("gemma")) {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", "$systemInstruction\n\nUser: $userMessage")))
                    }))
                } else {
                    put("system_instruction", JSONObject().apply { put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))) })
                    put("contents", JSONArray().put(JSONObject().apply { put("parts", JSONArray().put(JSONObject().put("text", userMessage))) }))
                }
            }
            val request = Request.Builder().url(url).post(jsonBody.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                if (response.isSuccessful) {
                                        var reply = JSONObject(responseText).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    if (!reply.isNullOrBlank()) {
                        val learnRegex = Regex("\\[LEARN:(.*?)\\]")
                        learnRegex.find(reply!!)?.let {
                            val updatedFacts = (learnedFacts + "\n- " + it.groupValues[1].trim()).trim()
                            prefs.edit().putString("learned_facts", updatedFacts).apply()
                            reply = reply!!.replace(learnRegex, "").trim()
                        }
                        val skillRegex = Regex("\\[SKILL:(.*?)\\]")


                        skillRegex.find(reply!!)?.let {
                            val updatedSkills = (learnedSkills + "\n- " + it.groupValues[1].trim()).takeLast(2500)
                            prefs.edit().putString("learned_skills", updatedSkills).apply()
                            reply = reply!!.replace(skillRegex, "").trim()
                        }

                        val searchRegex = Regex("\\[SEARCH:(.*?)\\]")
                        searchRegex.find(reply!!)?.let {
                            val queryToSearch = it.groupValues[1].trim()
                            reply = reply!!.replace(searchRegex, "").trim()
                            withContext(Dispatchers.Main) {
                                try {
                                    val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(queryToSearch, "UTF-8")}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(searchIntent)
                                } catch (_: Exception) {}
                            }
                        }
                                                val actionRegex = Regex("\\[ACTION:(.*?)\\]")
                        actionRegex.find(reply!!)?.let {
                            val actionJson = it.groupValues[1].trim()
                            reply = reply!!.replace(actionRegex, "").trim()
                            val execResult = DynamicExecutor.executeJsonAction(context, actionJson)
                            reply = if (reply.isNullOrBlank()) execResult else "$reply\n$execResult"
                        }


                        val updatedHistory = (recentHistory + "\nUser: $userMessage\nJARVIS: $reply").takeLast(1500)
                        prefs.edit().putString("recent_chat", updatedHistory).apply()
                        return@withContext reply!!
                    }
                } else {
                    lastError = "Error ${response.code}: ${try { JSONObject(responseText).optJSONObject("error")?.optString("message") ?: responseText } catch (_: Exception) { responseText }}"
                }
            }
        } catch (e: Exception) { lastError = e.localizedMessage ?: "Network error" }
    }
       // Keyless Free Cloud Fallback (बिना API Key और बिना लिमिट के चलेगा)
    try {
        val fallbackBody = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemInstruction))
                put(JSONObject().put("role", "user").put("content", userMessage))
            })
            put("model", "openai")
        }
        val fallbackReq = Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .post(fallbackBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(fallbackReq).execute().use { res ->
            val resText = res.body?.string() ?: ""
            if (res.isSuccessful && resText.isNotBlank()) {
                var reply = JSONObject(resText).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                if (!reply.isNullOrBlank()) {
                    val learnRegex = Regex("\\[LEARN:(.*?)\\]")
                    learnRegex.find(reply!!)?.let {
                        val updatedFacts = (learnedFacts + "\n- " + it.groupValues[1].trim()).trim()
                        prefs.edit().putString("learned_facts", updatedFacts).apply()
                        reply = reply!!.replace(learnRegex, "").trim()
                    }
                    val skillRegex = Regex("\\[SKILL:(.*?)\\]")
                    skillRegex.find(reply!!)?.let {
                        val updatedSkills = (learnedSkills + "\n- " + it.groupValues[1].trim()).trim()
                        prefs.edit().putString("learned_skills", updatedSkills).apply()
                        reply = reply!!.replace(skillRegex, "").trim()
                    }
                                        Regex("\\[VOICE:(.*?)\\]").find(reply!!)?.let {
                        val v = it.groupValues[1].trim().lowercase()
                        val ed = prefs.edit()
                        when {
                            v.contains("male") || v.contains("bhari") -> ed.putFloat("voice_pitch", 0.78f)
                            v.contains("female") || v.contains("ladki") -> ed.putFloat("voice_pitch", 1.25f)
                            v.contains("fast") || v.contains("tez") -> ed.putFloat("voice_rate", 1.25f)
                            else -> { ed.putFloat("voice_pitch", 0.95f); ed.putFloat("voice_rate", 1.05f) }
                        }
                        ed.apply()
                        reply = reply!!.replace(Regex("\\[VOICE:.*?\\]"), "").trim()
                    }
                    Regex("\\[AUTOLISTEN:(.*?)\\]").find(reply!!)?.let {
                        val on = it.groupValues[1].trim().lowercase() == "on"
                        prefs.edit().putBoolean("auto_listen", on).apply()
                        reply = reply!!.replace(Regex("\\[AUTOLISTEN:.*?\\]"), "").trim()
                    }

                    val updatedHistory = (recentHistory + "\nUser: $userMessage\nJARVIS: $reply").takeLast(1500)
                    prefs.edit().putString("recent_chat", updatedHistory).apply()
                    return@withContext reply!!
                }
            }
        }
    } catch (e: Exception) {
        lastError = e.message ?: lastError
    }

    return@withContext "समस्या आई ($lastError)"

}


