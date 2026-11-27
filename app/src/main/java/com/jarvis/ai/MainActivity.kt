package com.jarvis.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val text: String,
    val isUser: Boolean
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

    val messages = remember {
        mutableStateListOf(
            ChatMessage("नमस्ते! मैं JARVIS हूँ। बताइए, आज मैं आपकी क्या मदद कर सकता हूँ?", isUser = false)
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> "GOOD MORNING"
            hour < 17 -> "GOOD AFTERNOON"
            else -> "GOOD EVENING"
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

    fun sendQuery(query: String) {
        if (query.isBlank()) return
        if (apiKey.isBlank()) {
            showSettings = true
            Toast.makeText(context, "कृपया पहले Gemini API Key डालें", Toast.LENGTH_SHORT).show()
            return
        }

        messages.add(ChatMessage(query, isUser = true))
        inputText = ""
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
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("J.A.R.V.I.S", color = cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(greeting, color = Color.LightGray, fontSize = 10.sp, letterSpacing = 2.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isListening) "LISTENING..." else if (isThinking) "THINKING..." else "ONLINE",
                        color = if (isListening) Color(0xFFFF6B6B) else Color(0xFF8EF7C5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                    .height(110.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
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
                        fontSize = 54.sp
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
                    placeholder = { Text("JARVIS से पूछें...", color = Color.Gray, fontSize = 14.sp) },
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

suspend fun askGemini(apiKey: String, userMessage: String, mode: String): String = withContext(Dispatchers.IO) {
    try {
        val systemInstruction = when (mode) {
            "STUDY" -> "You are JARVIS in Study Mode. Explain concepts clearly in simple Hindi/Hinglish with short examples like a great tutor."
            "TRANSLATOR" -> "You are JARVIS Translator. If the user writes in Hindi, translate it to natural English. If in English or another language, translate it to clear Hindi."
            else -> "You are JARVIS, a smart, loyal, and polite personal AI assistant. Answer concisely in natural Hindi/Hinglish. Keep answers brief so they sound good when spoken aloud."
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            }))
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }

        val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext "API Error (${response.code}): कृपया अपनी API Key चेक करें।"
            }
            val json = JSONObject(responseText)
            val candidates = json.optJSONArray("candidates")
            val content = candidates?.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            return@withContext parts?.optJSONObject(0)?.optString("text")?.trim()
                ?: "माफ़ कीजिए, कोई जवाब नहीं मिला।"
        }
    } catch (e: Exception) {
        return@withContext "इंटरनेट या सर्वर में समस्या है: ${e.localizedMessage}"
    }
}
