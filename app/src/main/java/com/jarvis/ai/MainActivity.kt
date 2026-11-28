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
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import android.os.Environment
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.media.MediaPlayer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Equalizer
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import android.util.Base64
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

data class ChatMessage(val text: String, val isUser: Boolean)

data class UpdateInfo(val available: Boolean, val title: String, val apkUrl: String, val updatedAt: String)

var onSpeechFinishedCallback: (() -> Unit)? = null

private const val GITHUB_OWNER = "meenalekhram98-source"
private const val GITHUB_REPO = "JARVIS-AI-Android"


object OfflineBrainManager {
    private var llm: LlmInference? = null
    private var loadedPath: String = ""

    fun getModelFile(context: Context): java.io.File {
        val internal = java.io.File(context.filesDir, "jarvis_offline.bin")
        if (internal.exists() && internal.length() > 50_000_000L) return internal
        val dl = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "jarvis_offline.bin")
        if (dl.exists() && dl.length() > 50_000_000L) return dl
        return internal
    }

    fun isReady(context: Context): Boolean = getModelFile(context).let { it.exists() && it.length() > 50_000_000L }

    @Synchronized
    fun generate(context: Context, userPrompt: String): String? {
        val modelFile = getModelFile(context)
        if (!modelFile.exists() || modelFile.length() < 50_000_000L) return null
        return try {
            if (llm == null || loadedPath != modelFile.absolutePath) {
                llm?.close()
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(512)
                    .setTopK(40)
                    .setTemperature(0.6f)
                    .build()
                llm = LlmInference.createFromOptions(context.applicationContext, opts)
                loadedPath = modelFile.absolutePath
            }
            val prompt = "<start_of_turn>user\nYou are JARVIS, an offline AI assistant. Reply concisely in Hindi/Hinglish: $userPrompt<end_of_turn>\n<start_of_turn>model\n"
            llm?.generateResponse(prompt)?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            "Offline Engine Error: ${e.localizedMessage}"
        }
    }
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            var warned15 = false
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, i: android.content.Intent?) {
                    val lvl = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val st = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    if (lvl in 1..15 && !charging && !warned15) {
                        warned15 = true
                        speakHindi("Lekhram Sir, battery $lvl percent bachi hai. Power saving mode on kar doon?")
                    } else if (lvl > 20) warned15 = false
                }
            }, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}
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
            applyVoiceProfile()
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

    private fun applyVoiceProfile() {
        val prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
        val gender = prefs.getString("voice_gender", "male") ?: "male"
        val pitch = prefs.getFloat("voice_pitch", if (gender == "male") 0.74f else 1.22f)
        val rate = prefs.getFloat("voice_rate", 1.03f)
        try {
            tts?.voices?.let { voices ->
                val hindiVoices = voices.filter { it.locale.language == "hi" }
                val target = if (gender == "male") {
                    hindiVoices.firstOrNull { it.name.contains("male", true) || it.name.contains("ind-x-hid", true) || it.name.contains("ind-x-hie", true) }
                } else {
                    hindiVoices.firstOrNull { it.name.contains("female", true) || it.name.contains("ind-x-hia", true) || it.name.contains("ind-x-hic", true) }
                }
                if (target != null) tts?.voice = target
            }
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
        } catch (_: Exception) {}
    }

    private var sciFiPlayer: MediaPlayer? = null
    private var sciFiReverb: PresetReverb? = null
    private var sciFiEq: Equalizer? = null

    private fun stopSciFiAudio() {
        try {
            sciFiReverb?.release(); sciFiReverb = null
            sciFiEq?.release(); sciFiEq = null
            sciFiPlayer?.stop(); sciFiPlayer?.release(); sciFiPlayer = null
        } catch (_: Exception) {}
    }

    private fun fallbackOfflineTts(cleanText: String) {
        if (!isTtsReady) return
        try {
            applyVoiceProfile()
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_utt")
            tts?.setPitch(0.78f)
            tts?.setSpeechRate(0.98f)
            val hasHi = cleanText.any { it in '\u0900'..'\u097F' }; tts?.language = java.util.Locale(if (hasHi) "hi" else "en", "IN"); tts?.setSpeechRate(if (cleanText.length > 140) 1.08f else 0.96f); tts?.setPitch(if (hasHi) 0.98f else 1.04f); tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "jarvis_utt")
        } catch (_: Exception) {}
    }

    private fun speakHindi(text: String) {
        val cleanText = text.replace("*", "").replace("#", "").replace("\"", "").replace("&", "and").replace("<", "").replace(">", "").trim()
        if (cleanText.isEmpty() || !isTtsReady) return
        stopSciFiAudio()
        try {
            tts?.stop()
            val hasHindi = cleanText.any { it in '\u0900'..'\u097F' }
            tts?.language = if (hasHindi) java.util.Locale("hi", "IN") else java.util.Locale("en", "IN")
            tts?.setPitch(0.85f)
            tts?.setSpeechRate(0.98f)
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_utt") }
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
    var githubToken by remember { mutableStateOf(prefs.getString("github_token", "") ?: "") }
    var customModel by remember { mutableStateOf(prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash") }
    var showSettings by remember { mutableStateOf(false) }
    var showDirectPush by remember { mutableStateOf(false) }

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
            ChatMessage("नमस्ते लेखराम जी! मैं JARVIS हूँ। अब मेरे पास Direct Code Push और Auto-Healing इंजन है—अब आपको कभी GitHub खोलने की ज़रूरत नहीं पड़ेगी।", isUser = false)
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
            hour < 17 -> "GOOD AFTERNOON, LEKHRAM • MADE FOR LEKHRAM"
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
            if (selectedMode == "CODE") null else handleDeviceCommand(context, query)
        } catch (e: Exception) {
            "कमांड चलाने में समस्या आई: ${e.localizedMessage}"
        }

        if (cmdResult == "NEED_CONTACTS_PERMISSION") {
            pendingContactQuery = if (selectedMode == "CODE") null else query
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
            val reply = askGemini(context, apiKey, githubToken, customModel, query, selectedMode) { statusMsg ->
                messages.add(ChatMessage(statusMsg, isUser = false))
            }
            isThinking = false
            messages.add(ChatMessage(reply, isUser = false))
            listState.animateScrollToItem(messages.size - 1)
            if (voiceOutputEnabled) onSpeak(reply)
        }
    }

    LaunchedEffect(Unit) {
        retryQueryCallback = { q -> sendQuery(q) }
try{val bm=context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager; val bat=bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY); val hr=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); val s=if(hr<12)"सुबह" else if(hr<17)"दोपहर" else if(hr<21)"शाम" else "रात"; val a=if(bat in 1..25)"बैटरी सिर्फ़ $bat% बची है—चार्जर लगा लें।" else "बैटरी $bat% है और सभी सिस्टम ऑनलाइन हैं।"; val b="⚡ [Proactive Briefing] शुभ $s लेखराम जी! $a आज क्या मिशन है?"; messages.add(ChatMessage(b,false)); if(voiceOutputEnabled)onSpeak(b.replace("⚡ [Proactive Briefing] ",""))}catch(_:Exception){}
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
            if (prefs.getBoolean("auto_listen", false) && !isListening) { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!isListening) startVoiceRecognition() }, 400L) }
        }
    }
    val background = Color(0xFF050B16)
    val cyan = Color(0xFF45E6FF)

    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D1E32), background, Color(0xFF02050B))))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                        Text("J.A.R.V.I.S", color = cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(greeting, color = Color.LightGray, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isDownloadingUpdate) "UPDATING..." else if (isListening) "LISTENING..." else if (isThinking) "CODING..." else "ONLINE",
                        color = if (isListening || isDownloadingUpdate) Color(0xFFFF6B6B) else Color(0xFF8EF7C5),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showDirectPush = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Code, contentDescription = "Direct Code Push", tint = cyan)
                    }
                    IconButton(modifier = Modifier.size(30.dp), onClick = {
                        coroutineScope.launch {
                            Toast.makeText(context, "अपडेट चेक कर रहा है...", Toast.LENGTH_SHORT).show()
                            val upd = checkGitHubUpdate("")
                            if (upd != null && upd.apkUrl.isNotBlank()) triggerAutoUpdate(upd)
                            else Toast.makeText(context, "कोई नया अपडेट नहीं है", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = "Check Update", tint = if (pendingUpdate != null) Color(0xFFFFD54F) else cyan)
                    }
                    IconButton(modifier = Modifier.size(30.dp), onClick = {
                        voiceOutputEnabled = !voiceOutputEnabled
                        if (!voiceOutputEnabled) onStopSpeak()
                    }) {
                        Icon(if (voiceOutputEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff, contentDescription = "Voice Toggle", tint = cyan)
                    }
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = cyan)
                    }
                }
            }

            val hudAccent = when (selectedMode) {
                "CODE" -> Color(0xFF00E676)
                "STUDY" -> Color(0xFFFFB300)
                else -> cyan
            }
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val batPct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
            val batIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val batTemp = (batIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320) / 10f
            val activeEngine = if (prefs.getString("primary_engine", "GEMINI") == "OPENROUTER") "OPENROUTER" else "GEMINI 2.5"
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    .background(Color(0xFF0D1D30), CutCornerShape(8.dp))
                    .border(1.dp, hudAccent.copy(alpha = 0.4f), CutCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔋 $batPct% | ${batTemp}°C", color = Color.White, fontSize = 11.sp)
                Text("📶 5G • ONLINE", color = hudAccent, fontSize = 11.sp)
                Text("⚡ $activeEngine", color = Color(0xFFFFD54F), fontSize = 11.sp)
            }
            
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("JARVIS" to "🤖 AI Brain", "CODE" to "💻 Self-Code", "STUDY" to "📚 Study").forEach { (mode, label) ->
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
                        Text(label, color = if (isSelected) hudAccent else Color.LightGray, fontSize = 12.sp)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .scale(pulseScale)
                        .background(Brush.radialGradient(listOf(if (isListening) Color(0x88FF5252) else hudAccent.copy(alpha = 0.55f), Color.Transparent)), CircleShape)
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
                    val inf=androidx.compose.animation.core.rememberInfiniteTransition("a"); val r1 by inf.animateFloat(0f,360f,androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(9000,easing=androidx.compose.animation.core.LinearEasing)),"1"); val r2 by inf.animateFloat(360f,0f,androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(5500,easing=androidx.compose.animation.core.LinearEasing)),"2"); val col=if(isListening)Color(0xFFFF5252)else if(isThinking)Color(0xFFFFD740)else cyan; androidx.compose.foundation.Canvas(Modifier.size(92.dp)){val r=size.minDimension/2f; drawCircle(col.copy(0.15f),r); for(i in 0..5)drawArc(col.copy(0.85f),r1+i*60f,38f,false,style=androidx.compose.ui.graphics.drawscope.Stroke(3.5.dp.toPx())); for(i in 0..2)drawArc(col,r2+i*120f,80f,false,androidx.compose.ui.geometry.Offset(r*0.24f,r*0.24f),androidx.compose.ui.geometry.Size(r*1.52f,r*1.52f),style=androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx())); val t=androidx.compose.ui.graphics.Path().apply{moveTo(center.x,center.y-r*0.34f);lineTo(center.x+r*0.3f,center.y+r*0.22f);lineTo(center.x-r*0.3f,center.y+r*0.22f);close()}; drawPath(t,col.copy(0.35f)); drawPath(t,col,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())); drawCircle(Color.White,r*0.08f)}
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(0.55f).fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 290.dp)
                                .background(if (msg.isUser) hudAccent.copy(alpha = 0.28f) else Color(0xFF0D1E32), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                                .border(1.dp, if (msg.isUser) hudAccent else hudAccent.copy(alpha = 0.45f), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                                .padding(12.dp)
                        ) {
                            Text(text = msg.text, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("🔋 सिस्टम स्टेटस" to "सिस्टम स्टेटस", "🌐 नेटवर्क चेक" to "नेटवर्क चेक करो", "🛠️ बिल्ड चेक" to "बिल्ड स्टेटस बताओ").forEach { (chipLabel, chipCmd) ->
                    Box(
                        modifier = Modifier.weight(1f)
                            .background(Color(0xFF0F2338), CutCornerShape(8.dp))
                            .border(1.dp, hudAccent.copy(alpha = 0.5f), CutCornerShape(8.dp))
                            .clickable { sendQuery(chipCmd) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(chipLabel, color = Color.White, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("JARVIS से पूछें या खुद को अपग्रेड करने को कहें...", color = Color.Gray, fontSize = 14.sp) },
                    modifier = Modifier.weight(0.55f),
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
                    modifier = Modifier.size(48.dp).background(hudAccent.copy(alpha = 0.28f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }

    if (showDirectPush) {
        var targetFile by remember { mutableStateOf("app/src/main/java/com/jarvis/ai/MainActivity.kt") }
        var pastedCode by remember { mutableStateOf("") }
        var isPushing by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isPushing) showDirectPush = false },
            title = { Text("🚀 Direct Code Push (No GitHub Needed)", color = cyan, fontSize = 16.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("यहाँ सीधे नया कोड पेस्ट करें—JARVIS खुद GitHub पर पुश करके बिल्ड कर देगा:", fontSize = 12.sp, color = Color.LightGray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetFile,
                        onValueChange = { targetFile = it },
                        label = { Text("File Path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pastedCode,
                        onValueChange = { pastedCode = it },
                        label = { Text("Paste Full Kotlin Code Here") },
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !isPushing && pastedCode.isNotBlank(),
                    onClick = {
                        isPushing = true
                        showDirectPush = false
                        messages.add(ChatMessage("🚀 Direct Code Push शुरू कर रहा हूँ ($targetFile)...", isUser = false))
                        coroutineScope.launch {
                            val res = pushRawCodeToGitHub(githubToken, targetFile.trim(), pastedCode) { prog ->
                                messages.add(ChatMessage(prog, isUser = false))
                            }
                            isPushing = false
                            messages.add(ChatMessage(res, isUser = false))
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                ) { Text("Push & Build") }
            },
            dismissButton = {
                TextButton(onClick = { showDirectPush = false }) { Text("Cancel") }
            }
        )
    }

    if (showSettings) {
var tempOrKey by remember { mutableStateOf(prefs.getString("openrouter_api_key", "") ?: "") }
var tempPrimary by remember { mutableStateOf(prefs.getString("primary_engine", "GEMINI") ?: "GEMINI") }
        var tempKey by remember { mutableStateOf(apiKey) }
        var tempGhToken by remember { mutableStateOf(githubToken) }
        var tempModel by remember { mutableStateOf(customModel) }
        AlertDialog(
            onDismissRequest = { if (apiKey.isNotBlank()) showSettings = false },
            title = { Text("JARVIS Brain & Self-Code Setup", color = cyan) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("1. Google Gemini API Key:", fontSize = 12.sp, color = Color.LightGray)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = tempKey, onValueChange = { tempKey = it }, label = { Text("Gemini API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text("2. GitHub Token (ghp_...):", fontSize = 12.sp, color = Color.LightGray)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = tempGhToken, onValueChange = { tempGhToken = it }, label = { Text("GitHub PAT") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text("3. Active Gemini Model (404 आने पर यहाँ बदलें):", fontSize = 12.sp, color = Color.LightGray)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = tempModel, onValueChange = { tempModel = it }, label = { Text("Model Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(10.dp))
Text("4. OpenRouter Key (Fallback):", fontSize = 12.sp, color = Color.LightGray)
Spacer(Modifier.height(4.dp))
OutlinedTextField(value = tempOrKey, onValueChange = { tempOrKey = it }, label = { Text("sk-or-v1-...") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(10.dp))
Row(verticalAlignment = Alignment.CenterVertically) { Text("Primary Engine: ", fontSize = 12.sp, color = Color.LightGray); TextButton(onClick = { tempPrimary = if (tempPrimary == "GEMINI") "OPENROUTER" else "GEMINI" }) { Text(tempPrimary, color = cyan, fontWeight = FontWeight.Bold) } }
                }
            },
            confirmButton = {
                Button(onClick = {
                    apiKey = tempKey.trim()
                    githubToken = tempGhToken.trim()
                    customModel = tempModel.trim().ifBlank { "gemini-2.5-flash" }
                    prefs.edit()
                        .putString("gemini_api_key", apiKey)
                        .putString("github_token", githubToken)
                        .putString("gemini_model", customModel)
.putString("openrouter_api_key", tempOrKey.trim())
.putString("primary_engine", tempPrimary)
                        .apply()
                    showSettings = false
                    Toast.makeText(context, "JARVIS सेटिंग्स सेव हो गईं!", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
            }
        )
    }
}
suspend fun checkGitHubUpdate(lastInstalledAt: String): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest").header("Accept", "application/vnd.github+json").build()
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

fun isBalancedCode(code: String): Boolean {
    var curly = 0
    var round = 0
    var inStr = false
    var prev = ' '
    for (c in code) {
        if (c == '"' && prev != '\\') inStr = !inStr
        if (!inStr) {
            if (c == '{') curly++ else if (c == '}') curly--
            if (c == '(') round++ else if (c == ')') round--
            if (curly < 0 || round < 0) return false
        }
        prev = c
    }
    return curly == 0 && round == 0
}

suspend fun pushRawCodeToGitHub(
    githubToken: String,
    filePath: String,
    newCode: String,
    onProgress: suspend (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val token = githubToken.trim()
    if (token.isBlank()) return@withContext "पहले Settings (⚙️) में अपना GitHub Token डालें सर।"
    if (!isBalancedCode(newCode)) return@withContext "⚠️ कोड में ब्रैकेट `{}` या `()` अधूरे हैं—सुरक्षा के लिए पुश रोका गया।"

    val client = OkHttpClient.Builder().connectTimeout(45, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val fileUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$filePath"

    try {
        val getReq = Request.Builder().url(fileUrl)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        var currentSha = ""
        client.newCall(getReq).execute().use { res ->
            if (res.isSuccessful) {
                currentSha = JSONObject(res.body?.string() ?: "{}").optString("sha")
            }
        }

        val putBody = JSONObject().apply {
            put("message", "JARVIS Direct Push: $filePath")
            put("content", Base64.encodeToString(newCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (currentSha.isNotBlank()) put("sha", currentSha)
            put("branch", "main")
        }
        val putReq = Request.Builder().url(fileUrl)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .put(putBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(putReq).execute().use { res ->
            if (!res.isSuccessful) return@withContext "GitHub पुश फेल हुआ (${res.code}): ${res.body?.string()}"
        }

        withContext(Dispatchers.Main) { onProgress("⚙️ कोड पुश हो गया! अब 90 सेकंड बिल्ड की प्रतीक्षा कर रहा हूँ...") }
        delay(85_000)
        return@withContext "✅ Direct Push और बिल्ड पूरा हो गया लेखराम जी! अब ऊपर अपडेट आइकन दबाएं।"
    } catch (e: Exception) {
        return@withContext "Direct Push एरर: ${e.localizedMessage}"
    }
}

suspend fun callLLMCodeWriter(client: OkHttpClient, apiKey: String, customModel: String, prompt: String): String? = withContext(Dispatchers.IO) {
    val k = apiKey.trim()
    var err = if (k.isBlank()) "KEY_EMPTY" else ""
    if (k.startsWith("sk-or-")) {
        for (m in listOf(customModel, "google/gemini-2.5-flash", "google/gemini-2.0-flash-001", "openai/gpt-4o-mini").filter { it.contains("/") }.distinct()) {
            try {
                val b = JSONObject().put("model", m).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                val r = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "Bearer $k").header("HTTP-Referer", "https://github.com/meenalekhram98-source/JARVIS-AI-Android").header("X-Title", "JARVIS-AI").post(b.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(r).execute().use { res ->
                    val s = res.body?.string() ?: ""
                    if (res.isSuccessful) {
                        val t = JSONObject(s).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                        if (!t.isNullOrBlank()) return@withContext t.trim()
                    } else err = "OR($m):${res.code}:${s.take(80)}"
                }
            } catch (ex: Exception) { err = "OR_EX:${ex.message}" }
        }
    } else if (k.isNotBlank()) {
        val discovered = try {
                val mJson = JSONObject(client.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?key=$k").build()).execute().use { it.body?.string() ?: "{}" })
                val arr = mJson.optJSONArray("models")
                (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }.filter { (it.optJSONArray("supportedGenerationMethods")?.toString() ?: "").contains("generateContent") }.map { it.optString("name", "").removePrefix("models/") }.filter { it.contains("flash") && !it.contains("8b") && !it.contains("image") && !it.contains("audio") }
            } catch (_: Exception) { emptyList() }
            for (m in (listOf(customModel) + discovered + listOf("gemini-3-flash-preview", "gemini-2.5-flash", "gemini-flash-latest")).filter { it.isNotBlank() && !it.contains("/") }.distinct()) {
            try {
                val b = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))).put("generationConfig", JSONObject().put("temperature", 0.15).put("maxOutputTokens", 8192))
                val r = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$k").post(b.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(r).execute().use { res ->
                    val s = res.body?.string() ?: ""
                    if (res.isSuccessful) {
                        val t = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "")
                        if (!t.isNullOrBlank()) return@withContext t.trim()
                    } else err = "GEM($m):${res.code}:${s.take(80)}"
                }
            } catch (ex: Exception) { err = "GEM_EX:${ex.message}" }
        }
    }
    "API_ERR:$err"
}

suspend fun selfCodeAndHealGitHub(
    apiKey: String,
    githubToken: String,
    customModel: String,
    filePath: String,
    instruction: String,
    onProgress: suspend (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val token = githubToken.trim()
    if (token.isBlank()) return@withContext "❌ कृपया Settings (⚙️) में अपना GitHub Token डालें।"
    val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    val targetPath = filePath.ifBlank { "app/src/main/java/com/jarvis/ai/MainActivity.kt" }
    val apiUrl = "https://api.github.com/repos/meenalekhram98-source/JARVIS-AI-Android/contents/$targetPath"
    try {
        onProgress("🔍 GitHub से $targetPath पढ़ रहा हूँ...")
        val getReq = Request.Builder().url(apiUrl).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").build()
        val getJson = JSONObject(client.newCall(getReq).execute().use { it.body?.string() })
        val sha = getJson.optString("sha", "")
        val currentCode = String(Base64.decode(getJson.getString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)

        onProgress("🧠 नया और एरर-फ्री कोड लिख रहा हूँ...")
        val allLines = currentCode.lines()
        val keys = instruction.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 4 }.distinct()
        val picked = linkedSetOf<Int>()
        for (i in 0 until minOf(75, allLines.size)) picked.add(i)
        allLines.forEachIndexed { idxL, ln ->
            val low = ln.lowercase()
            if (keys.any { low.contains(it) }) {
                for (j in maxOf(0, idxL - 12)..minOf(allLines.lastIndex, idxL + 22)) picked.add(j)
            }
        }
        if (picked.size < 120) {
            val mid = allLines.size / 2
            for (j in maxOf(0, mid - 60)..minOf(allLines.lastIndex, mid + 60)) picked.add(j)
        }
        val snippet = picked.sorted().joinToString("\n") { allLines[it] }.take(24000)

        val prompt = """Modify JARVIS AI ($targetPath, total ${allLines.size} lines) for User Request: $instruction
Output ONLY exact surgical replacement blocks (do NOT include line numbers, do NOT output whole file):
<<<SEARCH>>>
exact existing lines (4 to 20 lines)
<<<REPLACE>>>
new updated lines
<<<END>>>

Relevant file slices:
$snippet"""

        val aiOut = callLLMCodeWriter(client, apiKey, customModel, prompt) ?: ""
        var patched = currentCode
        var patchCount = 0
        val regex = Regex("<<<SEARCH>>>\\s*([\\s\\S]*?)\\s*<<<REPLACE>>>\\s*([\\s\\S]*?)\\s*(?:<<<END>>>|$)")
        fun cleanFence(txt: String): String {
                val lines = txt.trim('\r', '\n').lines().toMutableList()
                if (lines.isNotEmpty() && lines.first().trim().startsWith("```")) lines.removeAt(0)
                if (lines.isNotEmpty() && lines.last().trim().startsWith("```")) lines.removeAt(lines.lastIndex)
                return lines.joinToString("\n")
            }
            for (m in regex.findAll(aiOut)) {
                val s = cleanFence(m.groupValues[1])
                val r = cleanFence(m.groupValues[2])
                if (s.isNotBlank() && patched.contains(s)) {
                    patched = patched.replaceFirst(s, r)
                    patchCount++
                } else if (s.isNotBlank()) {
                    val sLines = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val cLines = patched.lines()
                    if (sLines.isNotEmpty()) {
                        for (i in cLines.indices) {
                            if (cLines[i].trim() != sLines[0]) continue
                            var ci = i
                            var si = 0
                            while (ci < cLines.size && si < sLines.size) {
                                val cl = cLines[ci].trim()
                                if (cl.isEmpty()) { ci++; continue }
                                if (cl != sLines[si]) break
                                ci++; si++
                            }
                            if (si == sLines.size) {
                                val exactOld = cLines.subList(i, ci).joinToString("\n")
                                patched = patched.replaceFirst(exactOld, r)
                                patchCount++
                                break
                            }
                        }
                    }
                }
            }
            val updatedCode = if (patchCount > 0) patched else if (aiOut.contains("package com.jarvis.ai") && aiOut.length > 5000) aiOut else ""

        if (updatedCode.isBlank() || !isBalancedCode(updatedCode)) {
            return@withContext "❌ कोड पैच विफल (aiLen=${aiOut.length}, patches=$patchCount, bal=${if (updatedCode.isNotBlank()) isBalancedCode(updatedCode) else false}): ${aiOut.take(180)}"
        }

        onProgress("🚀 नया कोड GitHub पर पुश कर रहा हूँ...")
        val encoded = Base64.encodeToString(updatedCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val putBody = JSONObject().put("message", "JARVIS Self-Code Update").put("content", encoded).apply { if (sha.isNotBlank()) put("sha", sha) }
        val putReq = Request.Builder().url(apiUrl).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .put(putBody.toString().toRequestBody("application/json".toMediaType())).build()
        val pushErr = client.newCall(putReq).execute().use { res ->
            if (!res.isSuccessful) "❌ GitHub push fail (${res.code}): ${res.body?.string()?.take(120)}" else ""
        }
        if (pushErr.isNotEmpty()) return@withContext pushErr
        onProgress("⏳ GitHub APK बिल्ड मॉनिटर कर रहा हूँ (Auto-Healing सक्रिय)...")
            val runsUrl = "https://api.github.com/repos/meenalekhram98-source/JARVIS-AI-Android/actions/runs?per_page=1"
            var buildConclusion = ""
            var lastRunId = 0L
            for (attempt in 1..14) {
                kotlinx.coroutines.delay(6000)
                try {
                    val rReq = Request.Builder().url(runsUrl).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").build()
                    val rJson = JSONObject(client.newCall(rReq).execute().use { it.body?.string() ?: "{}" })
                    val runObj = rJson.optJSONArray("workflow_runs")?.optJSONObject(0)
                    if (runObj != null) {
                        lastRunId = runObj.optLong("id", 0L)
                        val st = runObj.optString("status", "")
                        val conc = runObj.optString("conclusion", "")
                        onProgress("⚙️ बिल्ड स्टेटस: $st ${if (conc.isNotEmpty()) "($conc)" else "(${attempt * 6}s)"}")
                        if (st == "completed") {
                            buildConclusion = conc
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
            if (buildConclusion == "success") {
                return@withContext "✅ मिशन सफल! नया APK तैयार है—ऊपर Update (⬇️) आइकन दबाकर इंस्टॉल करें।"
            } else if (buildConclusion == "failure" && lastRunId > 0L) {
                onProgress("🩺 बिल्ड फेल हुआ! खुद एरर लॉग पढ़कर Auto-Heal कर रहा हूँ...")
                val logUrl = "https://api.github.com/repos/meenalekhram98-source/JARVIS-AI-Android/actions/runs/$lastRunId/logs"
                val logReq = Request.Builder().url(logUrl).header("Authorization", "Bearer $token").build()
                val errSnippet = try {
                    client.newCall(logReq).execute().use { resp ->
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        val zis = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
                        val sb = StringBuilder()
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val txt = zis.bufferedReader().readText()
                            txt.lines().filter { it.contains("e: ") || it.contains("Unresolved") || it.contains("Expecting") }.take(12).forEach { sb.appendLine(it) }
                            entry = zis.nextEntry
                        }
                        sb.toString().take(1500)
                    }
                } catch (ex: Exception) { "Compile error in $targetPath" }
                val healPrompt = "Fix these Kotlin compiler errors in $targetPath:\n$errSnippet\n\nOutput ONLY exact surgical replacement blocks:\n<<<SEARCH>>>\nexact existing lines\n<<<REPLACE>>>\nfixed lines\n<<<END>>>\n\nRelevant code:\n${updatedCode.take(22000)}"
                val healOut = callLLMCodeWriter(client, apiKey, customModel, healPrompt) ?: ""
                var healedCode = updatedCode
                for (hm in regex.findAll(healOut)) {
                    val hs = cleanFence(hm.groupValues[1])
                    val hr = cleanFence(hm.groupValues[2])
                    if (hs.isNotBlank() && healedCode.contains(hs)) healedCode = healedCode.replaceFirst(hs, hr)
                }
                if (healedCode != updatedCode && isBalancedCode(healedCode)) {
                    val reGet = JSONObject(client.newCall(getReq).execute().use { it.body?.string() ?: "{}" })
                    val newSha = reGet.optString("sha", sha)
                    val healEnc = Base64.encodeToString(healedCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val healPut = JSONObject().put("message", "JARVIS Auto-Heal Fix").put("content", healEnc).put("sha", newSha)
                    val healReq = Request.Builder().url(apiUrl).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").put(healPut.toString().toRequestBody("application/json".toMediaType())).build()
                    client.newCall(healReq).execute().close()
                    return@withContext "🩺 Auto-Heal पूरा! एरर खुद ठीक करके दोबारा GitHub पर पुश कर दिया है—60 सेकंड बाद Update (⬇️) दबाएँ।"
                }
                return@withContext "⚠️ बिल्ड एरर मिला:\n${errSnippet.take(300)}"
            }
            return@withContext "✅ कोड पुश हो गया! 60 सेकंड बाद ऊपर Update (⬇️) आइकन से नया वर्ज़न इंस्टॉल करें।"
    } catch (e: Exception) {
        return@withContext "❌ सेल्फ़-कोडिंग त्रुटि: ${e.message}"
    }
}


private fun applyAllTags(
    context: Context,
    prefs: android.content.SharedPreferences,
    rawReply: String,
    learnedFacts: String,
    learnedSkills: String
): Pair<String, Pair<String, String>?> {
    var reply = rawReply
    var selfCodeTask: Pair<String, String>? = null

    Regex("\\[LEARN:(.*?)\\]").find(reply)?.let {
        val updatedFacts = (learnedFacts + "\n- " + it.groupValues[1].trim()).takeLast(2500).trim()
        prefs.edit().putString("learned_facts", updatedFacts).apply()
        reply = reply.replace(Regex("\\[LEARN:.*?\\]"), "").trim()
    }
    Regex("\\[SKILL:(.*?)\\]").find(reply)?.let {
        val updatedSkills = (learnedSkills + "\n- " + it.groupValues[1].trim()).takeLast(2500).trim()
        prefs.edit().putString("learned_skills", updatedSkills).apply()
        reply = reply.replace(Regex("\\[SKILL:.*?\\]"), "").trim()
    }
    Regex("\\[VOICE:(.*?)\\]").find(reply)?.let {
        val v = it.groupValues[1].trim().lowercase()
        val ed = prefs.edit()
        when {
            v.contains("male") || v.contains("bhari") || v.contains("purush") -> {
                ed.putString("voice_gender", "male")
                ed.putFloat("voice_pitch", 0.74f)
            }
            v.contains("female") || v.contains("ladki") || v.contains("mahila") -> {
                ed.putString("voice_gender", "female")
                ed.putFloat("voice_pitch", 1.22f)
            }
            v.contains("fast") || v.contains("tez") -> ed.putFloat("voice_rate", 1.25f)
            else -> {
                ed.putFloat("voice_pitch", 0.95f)
                ed.putFloat("voice_rate", 1.03f)
            }
        }
        ed.apply()
        reply = reply.replace(Regex("\\[VOICE:.*?\\]"), "").trim()
    }
    Regex("\\[AUTOLISTEN:(.*?)\\]").find(reply)?.let {
        val on = it.groupValues[1].trim().lowercase() == "on"
        prefs.edit().putBoolean("auto_listen", on).apply()
        reply = reply.replace(Regex("\\[AUTOLISTEN:.*?\\]"), "").trim()
    }
    Regex("\\[SEARCH:(.*?)\\]").find(reply)?.let {
        val q = it.groupValues[1].trim()
        reply = reply.replace(Regex("\\[SEARCH:.*?\\]"), "").trim()
        try {
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(q, "UTF-8")}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(searchIntent)
        } catch (_: Exception) {}
    }
    Regex("\\[ACTION:(.*?)\\]").find(reply)?.let {
        val actionJson = it.groupValues[1].trim()
        reply = reply.replace(Regex("\\[ACTION:.*?\\]"), "").trim()
        val execResult = try { DynamicExecutor.executeJsonAction(context, actionJson) } catch (e: Exception) { "Action error: ${e.localizedMessage}" }
        reply = if (reply.isBlank()) execResult else "$reply\n$execResult"
    }
    Regex("\\[SELFCODE:([\\s\\S]*?)\\]").find(reply)?.let {
        val jsonStr = it.groupValues[1].trim()
        reply = reply.replace(Regex("\\[SELFCODE:([\\s\\S]*?)\\]"), "").trim()
        try {
            val obj = JSONObject(jsonStr)
            val file = obj.optString("file", "app/src/main/java/com/jarvis/ai/MainActivity.kt")
            val inst = obj.optString("instruction", "")
            if (inst.isNotBlank()) selfCodeTask = Pair(file, inst)
        } catch (_: Exception) {}
    }

    return Pair(reply, selfCodeTask)
}

suspend fun askGemini(
    context: Context,
    apiKey: String,
    githubToken: String,
    customModel: String,
    userMessage: String,
    mode: String,
    onProgress: suspend (String) -> Unit
): String = withContext(Dispatchers.IO) {
    if (mode == "CODE") {
        val targetFile = when {
            userMessage.contains("build.gradle.kts", ignoreCase = true) -> "app/build.gradle.kts"
            userMessage.contains("AndroidManifest.xml", ignoreCase = true) -> "app/src/main/AndroidManifest.xml"
            userMessage.contains(".yml", ignoreCase = true) -> ".github/workflows/android.yml"
            else -> "app/src/main/java/com/jarvis/ai/MainActivity.kt"
        }
        val localPrefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val orKey = localPrefs.getString("openrouter_api_key", "") ?: ""
        val useOr = (localPrefs.getString("primary_engine", "GEMINI") == "OPENROUTER") && orKey.isNotBlank()
        val effectiveKey = if (useOr) orKey else apiKey.ifBlank { orKey }
        return@withContext selfCodeAndHealGitHub(effectiveKey, githubToken, customModel, targetFile, userMessage, onProgress)
    }
    val cleanKey = apiKey.trim()
    val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
    val learnedFacts = prefs.getString("learned_facts", "") ?: ""
    val learnedSkills = prefs.getString("learned_skills", "") ?: ""
    val recentHistory = prefs.getString("recent_chat", "") ?: ""

    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    val preferOr = (prefs.getString("primary_engine", "GEMINI") == "OPENROUTER") && (prefs.getString("openrouter_api_key", "")?.isNotBlank() == true)
val modelsToTry = if (cleanKey.isBlank() || preferOr) emptyList() else listOf(
        customModel,
        "gemini-2.5-flash",
        "gemini-flash-latest",
        "gemini-2.5-pro"
    ).distinct()

    val ownerIdentity = """
        Your creator and owner (malik) is Lekhram Meena (लेखराम मीना). Always address him respectfully as 'लेखराम जी' or 'सर'.
        You are a FULL-STACK SELF-CODING & SELF-HEALING AI assistant. You know all programming languages (Kotlin, Jetpack Compose, Java, Python, C++, JavaScript, HTML/CSS, SQL, Bash) and can modify your own source code on GitHub.
        
        Learned facts about Lekhram ji:
        $learnedFacts
        
        Custom skills & abilities:
        $learnedSkills
        
        Recent chat:
        $recentHistory
        
        ACTION & SELF-CODING TAGS (Append at the very end when triggered; never speak tags aloud):
        1. Personal memory: [LEARN: short fact]
        2. New behavioral skill/rule: [SKILL: clear rule]
        3. Google search: [SEARCH: query]
        4. Device hardware/settings: [ACTION: {"type":"BATTERY"}] or {"type":"STORAGE"} or {"type":"DEVICE_INFO"} or {"type":"VOLUME","action":"up/down/mute/max","percent":70} or {"type":"BRIGHTNESS","percent":50} or {"type":"INTENT","action":"android.settings.AIRPLANE_MODE_SETTINGS","uri":"","package":"","reply":"जी लेखराम जी, सेटिंग खोल दी है।"} or {"type":"OPEN_APP","package":"com.package.name","reply":"जी सर, खोल रहा हूँ।"}
        5. Voice/Auto-listen control: [VOICE: male], [VOICE: female], [VOICE: fast], [VOICE: normal], [AUTOLISTEN: on], or [AUTOLISTEN: off]
        6. SELF-CODING & SELF-HEALING ON GITHUB: Whenever Lekhram ji asks you to code a new app feature, fix your own bug/error, update your UI, or modify your Kotlin files on GitHub, append:
        [SELFCODE: {"file":"app/src/main/java/com/jarvis/ai/MainActivity.kt","instruction":"exact technical description of code change"}]
    """.trimIndent()

    val systemInstruction = when (mode) {
        "CODE" -> "$ownerIdentity You are JARVIS in MASTER SOFTWARE ARCHITECT MODE. Write clean, complete, production-grade code in any language requested, and use [SELFCODE: ...] whenever upgrading yourself."
        "STUDY" -> "$ownerIdentity You are JARVIS in STUDY MODE. Explain concepts clearly in simple Hindi/Hinglish with bullet points and examples."
        else -> "$ownerIdentity You are J.A.R.V.I.S., Tony Stark's calm, witty, hyper-efficient AI digital butler created for Lekhram. Address user respectfully as 'Lekhram Sir' or 'Sir'. Keep answers crisp, direct, confident with subtle dry British wit—never long robotic lectures. Confirm actions like 'Done, Sir.' Match user language (Hinglish/Hindi/English)."
    }

    var lastError = ""
    for (model in modelsToTry) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
            val jsonBody = JSONObject().apply {
                put("system_instruction", JSONObject().apply { put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))) })
                put("contents", JSONArray().put(JSONObject().apply { put("parts", JSONArray().put(JSONObject().put("text", userMessage))) }))
            }
            val request = Request.Builder().url(url).post(jsonBody.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val rawReply = JSONObject(responseText).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    if (!rawReply.isNullOrBlank()) {
                        val (cleanReply, selfCodeTask) = applyAllTags(context, prefs, rawReply, learnedFacts, learnedSkills)
                        val updatedHistory = (recentHistory + "\nUser: $userMessage\nJARVIS: $cleanReply").takeLast(500)
                        prefs.edit().putString("recent_chat", updatedHistory).apply()

                        if (selfCodeTask != null) {
                            withContext(Dispatchers.Main) { onProgress(cleanReply) }
                            return@withContext selfCodeAndHealGitHub(cleanKey, githubToken, customModel, selfCodeTask.first, selfCodeTask.second, { prog -> withContext(Dispatchers.Main) { onProgress(prog) } })
                        }
                        return@withContext cleanReply
                    }
                } else {
                    lastError = "HTTP ${response.code}"
                }
            }
        } catch (e: Exception) {
            lastError = e.localizedMessage ?: "Network error"
        }
    }

    try {
        val fullPrompt = "$systemInstruction\n\nUser: $userMessage"
        val encodedPrompt = URLEncoder.encode(fullPrompt, "UTF-8")
        val orKey = prefs.getString("openrouter_api_key", "")?.trim() ?: ""; val fallbackReq = if (orKey.isNotBlank()) { val orBody = JSONObject().put("model", "openrouter/free").put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", systemInstruction)).put(JSONObject().put("role", "user").put("content", userMessage))).toString().toRequestBody("application/json".toMediaType()); Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").addHeader("Authorization", "Bearer " + orKey).post(orBody).build() } else { val fbPrompt = java.net.URLEncoder.encode("You are JARVIS, personal AI assistant of Lekhram Meena. Reply concisely in Hindi/Hinglish. User: " + userMessage, "UTF-8"); Request.Builder().url("https://text.pollinations.ai/" + fbPrompt).get().build() }
        val res = client.newCall(fallbackReq).execute()
            val rawFb = res.body?.string() ?: ""; val rawReply = if (rawFb.trim().startsWith("{")) JSONObject(rawFb).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: "" else rawFb
                res.close()
            if (res.isSuccessful && rawReply.isNotBlank()) {
                val (cleanReply, selfCodeTask) = applyAllTags(context, prefs, rawReply, learnedFacts, learnedSkills)
                val updatedHistory = (recentHistory + "\nUser: $userMessage\nJARVIS: $cleanReply").takeLast(500)
                prefs.edit().putString("recent_chat", updatedHistory).apply()

                if (selfCodeTask != null) {
                    withContext(Dispatchers.Main) { onProgress(cleanReply) }
                    return@withContext selfCodeAndHealGitHub(cleanKey, githubToken, customModel, selfCodeTask.first, selfCodeTask.second, { prog -> withContext(Dispatchers.Main) { onProgress(prog) } })
                }
                return@withContext cleanReply
            } else {
                lastError = if (lastError.isBlank()) "Fallback HTTP ${res.code}" else "$lastError | Fallback ${res.code}"
            }
        } catch (e: Exception) {
        lastError = if (lastError.isBlank()) (e.localizedMessage ?: "Connection failed") else "$lastError | ${e.localizedMessage}"
    }

    val offlineReply = OfflineBrainManager.generate(context, userMessage)
        if (!offlineReply.isNullOrBlank()) {
            return@withContext "🧠 [OFFLINE BRAIN]\n$offlineReply"
        }
        return@withContext "समस्या आई (${lastError.ifBlank { "इंटरनेट या API Key जांचें" }})"
}
