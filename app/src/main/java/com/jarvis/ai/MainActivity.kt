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

private const val GITHUB_OWNER = "meenalekhram98-source"
private const val GITHUB_REPO = "JARVIS-AI-Android"

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
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "jarvis_utt")
        } catch (_: Exception) {}
    }

    private fun speakHindi(text: String) {
        val cleanText = text.replace("*", "").replace("#", "").replace("\"", "").replace("&", "and").replace("<", "").replace(">", "").trim()
        if (cleanText.isEmpty()) return

        stopSciFiAudio()
        try { tts?.stop() } catch (_: Exception) {}

        Thread {
            try {
                val reqId = UUID.randomUUID().toString().replace("-", "")
                val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
                val request = Request.Builder()
                    .url(run {
                        val ticks = (System.currentTimeMillis() / 1000L + 11644473600L)
                        val rounded = ticks - (ticks % 300L)
                        val str = "${rounded * 10000000L}6A5AA1D4EAFF4E9FB37E23D68491D6F4"
                        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray()).joinToString("") { "%02X".format(it) }
                        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4&Sec-MS-GEC=$sha&Sec-MS-GEC-Version=1-130.0.2849.68"
                    })
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0")
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .build()

                val audioBuffer = ByteArrayOutputStream()
                val lock = Object()
                var done = false
                var failed = false

                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val cfg = "Content-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                        webSocket.send(cfg)

                        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='hi-IN'><voice name='hi-IN-MadhurNeural'><prosody pitch='-11Hz' rate='+6%'>$cleanText</prosody></voice></speak>"
                        val msg = "X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\nPath:ssml\r\n\r\n$ssml"
                        webSocket.send(msg)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val arr = bytes.toByteArray()
                        if (arr.size > 2) {
                            val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                            val dataStart = headerLen + 2
                            if (dataStart < arr.size) {
                                audioBuffer.write(arr, dataStart, arr.size - dataStart)
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, textMsg: String) {
                        if (textMsg.contains("Path:turn.end")) {
                            synchronized(lock) { done = true; lock.notifyAll() }
                            webSocket.close(1000, "Done")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        synchronized(lock) { failed = true; done = true; lock.notifyAll() }
                    }
                })

                synchronized(lock) {
                    if (!done) lock.wait(8000)
                }

                val audioBytes = audioBuffer.toByteArray()
                if (failed || audioBytes.isEmpty()) {
                    // Purani offline TTS voice hata di gayi
                    return@Thread
                }

                val outFile = File(cacheDir, "jarvis_scifi.mp3")
                FileOutputStream(outFile).use { it.write(audioBytes) }

                runOnUiThread {
                    try {
                        val mp = MediaPlayer()
                        mp.setDataSource(outFile.absolutePath)
                        mp.prepare()

                        // Sci-Fi Metallic + Holographic Chamber Effect
                        try {
                            val eq = Equalizer(0, mp.audioSessionId)
                            eq.enabled = true
                            val bands = eq.numberOfBands.toInt()
                            if (bands >= 5) {
                                eq.setBandLevel(0, (-300).toShort()) // Low rumble cut (highpass)
                                eq.setBandLevel((bands - 1).toShort(), (400).toShort()) // Crisp metallic treble
                            }
                            sciFiEq = eq

                            val rev = PresetReverb(0, mp.audioSessionId)
                            rev.preset = PresetReverb.PRESET_SMALLROOM
                            rev.enabled = true
                            mp.attachAuxEffect(rev.id)
                            mp.setAuxEffectSendLevel(0.45f)
                            sciFiReverb = rev
                        } catch (_: Exception) {}

                        mp.setOnCompletionListener { stopSciFiAudio() }
                        sciFiPlayer = mp
                        mp.start()
                    } catch (_: Exception) {
                        fallbackOfflineTts(cleanText)
                    }
                }
            } catch (_: Exception) {
                // Purani offline TTS voice hata di gayi
            }
        }.start()
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
                        if (isDownloadingUpdate) "UPDATING..." else if (isListening) "LISTENING..." else if (isThinking) "CODING..." else "ONLINE",
                        color = if (isListening || isDownloadingUpdate) Color(0xFFFF6B6B) else Color(0xFF8EF7C5),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showDirectPush = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Code, contentDescription = "Direct Code Push", tint = cyan)
                    }
                    IconButton(modifier = Modifier.size(34.dp), onClick = {
                        coroutineScope.launch {
                            Toast.makeText(context, "अपडेट चेक कर रहा है...", Toast.LENGTH_SHORT).show()
                            val upd = checkGitHubUpdate("")
                            if (upd != null && upd.apkUrl.isNotBlank()) triggerAutoUpdate(upd)
                            else Toast.makeText(context, "कोई नया अपडेट नहीं है", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = "Check Update", tint = if (pendingUpdate != null) Color(0xFFFFD54F) else cyan)
                    }
                    IconButton(modifier = Modifier.size(34.dp), onClick = {
                        voiceOutputEnabled = !voiceOutputEnabled
                        if (!voiceOutputEnabled) onStopSpeak()
                    }) {
                        Icon(if (voiceOutputEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff, contentDescription = "Voice Toggle", tint = cyan)
                    }
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = cyan)
                    }
                }
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
                        Text(label, color = if (isSelected) cyan else Color.LightGray, fontSize = 12.sp)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(95.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
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
                    Text("◉", color = if (isListening) Color(0xFFFF6B6B) else cyan, fontSize = 48.sp)
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
                    modifier = Modifier.size(48.dp).background(Color(0xFF087E9B), CircleShape)
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
            val json = JSONObject(res.body.string() ?: "")
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
                currentSha = JSONObject(res.body.string() ?: "{}").optString("sha")
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
            if (!res.isSuccessful) return@withContext "GitHub पुश फेल हुआ (${res.code}): ${res.body.string()}"
        }

        withContext(Dispatchers.Main) { onProgress("⚙️ कोड पुश हो गया! अब 90 सेकंड बिल्ड की प्रतीक्षा कर रहा हूँ...") }
        delay(85_000)
        return@withContext "✅ Direct Push और बिल्ड पूरा हो गया लेखराम जी! अब ऊपर अपडेट आइकन दबाएं।"
    } catch (e: Exception) {
        return@withContext "Direct Push एरर: ${e.localizedMessage}"
    }
}

suspend fun callLLMCodeWriter(client: OkHttpClient, apiKey: String, customModel: String, prompt: String): String? = withContext(Dispatchers.IO) {
    val cleanKey = apiKey.trim()
    var rawOut: String? = null

    if (false) {
        val models = listOf(customModel, "gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-pro").distinct()
        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                val req = Request.Builder().url(url).header("x-goog-api-key", apiKey.trim()).post(body.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val txt = JSONObject(res.body.string() ?: "{}").optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "")
                        if (!txt.isNullOrBlank()) {
                            rawOut = txt.replace(Regex("^```[a-zA-Z]*\\n?"), "").replace(Regex("\\n?```$"), "").trim()
                        }
                    }
                }
                if (rawOut != null) break
            } catch (_: Exception) {}
        }
    }

    if (rawOut == null) {
        try {
            val fbBody = JSONObject().put("model", "openai").put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Return ONLY complete valid Kotlin code without markdown fences.

$prompt")))
                val fbReq = Request.Builder().url("https://text.pollinations.ai/openai").post(fbBody.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(fbReq).execute().use { res ->
                if (res.isSuccessful) {
                    val rawRes = res.body.string() ?: ""; val txt = if (rawRes.trim().startsWith("{")) JSONObject(rawRes).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: "" else rawRes
                    if (txt.isNotBlank()) {
                        rawOut = txt.replace(Regex("^```[a-zA-Z]*\\n?"), "").replace(Regex("\\n?```$"), "").trim()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    rawOut
}
suspend fun selfCodeAndHealGitHub(
    apiKey: String,
    githubToken: String,
    customModel: String,
    filePath: String,
    instruction: String,
    onProgress: suspend (String) -> Unit
    val token = githubToken.trim()
    if (token.isBlank()) return@withContext "❌ कृपया Settings (⚙️) में अपना GitHub Token डालें।"
    val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    val targetPath = filePath.ifBlank { "app/src/main/java/com/jarvis/ai/MainActivity.kt" }
    val apiUrl = "https://api.github.com/repos/meenalekhram98-source/JARVIS-AI-Android/contents/$targetPath"
    try {
        onProgress("🔍 GitHub से $targetPath पढ़ रहा हूँ...")
        val getReq = Request.Builder().url(apiUrl).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").build()
        val getJson = JSONObject(client.newCall(getReq).execute().use { it.body.string() })
        val sha = getJson.optString("sha", "")
        val currentCode = String(Base64.decode(getJson.getString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)

        onProgress("🧠 नया और एरर-फ्री कोड लिख रहा हूँ...")
        val idx = currentCode.indexOf("J.A.R.V.I.S").let { if (it > 300) it - 300 else 0 }; val snippet = currentCode.substring(idx, minOf(currentCode.length, idx + 3500))
        val prompt = """Modify JARVIS AI $targetPath for User Request: $instruction
Output ONLY the exact replacement block in this format (do NOT output the whole file):
<<<SEARCH>>>
exact existing lines (5 to 20 lines, 100% identical)
<<<REPLACE>>>
new updated lines
<<<END>>>

File content:
$snippet"""

        val aiOut = callLLMCodeWriter(client, apiKey, customModel, prompt) ?: ""
        val updatedCode = if (aiOut.contains("<<<SEARCH>>>") && aiOut.contains("<<<REPLACE>>>")) {
            val s = aiOut.substringAfter("<<<SEARCH>>>").substringBefore("<<<REPLACE>>>").trim('\r', '\n')
            val r = aiOut.substringAfter("<<<REPLACE>>>").substringBefore("<<<END>>>").trim('\r', '\n')
            if (s.isNotBlank() && currentCode.contains(s)) currentCode.replaceFirst(s, r) else ""
        } else if (aiOut.contains("package com.jarvis.ai") && aiOut.length > 5000) aiOut else ""

        if (updatedCode.isBlank() || !isBalancedCode(updatedCode)) {
            return@withContext "❌ कोड जेनरेट नहीं हो सका—कमांड थोड़ा और स्पष्ट लिखें।"
        }

        onProgress("🚀 नया कोड GitHub पर पुश कर रहा हूँ...")
        val encoded = Base64.encodeToString(updatedCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val putBody = JSONObject().put("message", "JARVIS Self-Code Update").put("content", encoded).apply { if (sha.isNotBlank()) put("sha", sha) }
        val putReq = Request.Builder().url(apiUrl).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .put(putBody.toString().toRequestBody("application/json".toMediaType())).build()
        val pushErr = client.newCall(putReq).execute().use { res ->
            if (!res.isSuccessful) "❌ GitHub push fail (${res.code}): ${res.body.string().take(120)}" else ""
        }
        if (pushErr.isNotEmpty()) return@withContext pushErr
        return@withContext "✅ कोड GitHub पर पुश हो गया! 90 सेकंड बाद ऊपर Update (⬇️) आइकन से नया वर्जन इंस्टॉल करें।"
    } catch (e: Exception) {
        return@withContext "❌ सेल्फ-कोडिंग त्रुटि: ${e.message}"
    }
}

            .put(putBody.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(putReq).execute().use { res ->
            if (!res.isSuccessful) return "❌ GitHub push fail (${res.code}): ${res.body.string()?.take(150)}"
        }
        "✅ कोड सफलतापूर्वक GitHub पर पुश हो गया है! 90 सेकंड बाद ऊपर Update (⬇️) आइकन से नया वर्जन इंस्टॉल कर लें।"
    } catch (e: Exception) {
        "❌ सेल्फ-कोडिंग में त्रुटि आई: ${e.message}"
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
    Regex("\\[SELFCODE:(.*?)\\]").find(reply)?.let {
        val jsonStr = it.groupValues[1].trim()
        reply = reply.replace(Regex("\\[SELFCODE:.*?\\]"), "").trim()
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
    val cleanKey = apiKey.trim()
    val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
    val learnedFacts = prefs.getString("learned_facts", "") ?: ""
    val learnedSkills = prefs.getString("learned_skills", "") ?: ""
    val recentHistory = prefs.getString("recent_chat", "") ?: ""

    val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()
    val modelsToTry = if (cleanKey.isBlank()) emptyList() else listOf(
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
                        val updatedHistory = (recentHistory + "\nUser: $userMessage\nJARVIS: $cleanReply").takeLast(1500)
                        prefs.edit().putString("recent_chat", updatedHistory).apply()

                        if (selfCodeTask != null) {
                            withContext(Dispatchers.Main) { onProgress(cleanReply) }
                            return@withContext selfCodeAndHealGitHub(cleanKey, githubToken, customModel, selfCodeTask.first, selfCodeTask.second) { prog ->
                                withContext(Dispatchers.Main) { onProgress(prog) }
                            }
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
        val fallbackReq = Request.Builder()
            .url("https://text.pollinations.ai/$encodedPrompt")
            .get()
            .build()
        val res = client.newCall(fallbackReq).execute()
            val rawReply = res.body?.string() ?: ""
                res.close()
            if (res.isSuccessful && rawReply.isNotBlank()) {
                val (cleanReply, selfCodeTask) = applyAllTags(context, prefs, rawReply, learnedFacts, learnedSkills)
                val updatedHistory = (recentHistory + "\nUser: $userMessage\nJARVIS: $cleanReply").takeLast(1500)
                prefs.edit().putString("recent_chat", updatedHistory).apply()

                if (selfCodeTask != null) {
                    withContext(Dispatchers.Main) { onProgress(cleanReply) }
                    return@withContext selfCodeAndHealGitHub(cleanKey, githubToken, customModel, selfCodeTask.first, selfCodeTask.second) { prog ->
                        withContext(Dispatchers.Main) { onProgress(prog) }
                    }
                }
                return@withContext cleanReply
            } else {
                lastError = if (lastError.isBlank()) "Fallback HTTP ${res.code}" else "$lastError | Fallback ${res.code}"
            }
        }
    } catch (e: Exception) {
        lastError = if (lastError.isBlank()) (e.localizedMessage ?: "Connection failed") else "$lastError | ${e.localizedMessage}"
    }

    return@withContext "समस्या आई (${lastError.ifBlank { "इंटरनेट या API Key जांचें" }})"
}
}
