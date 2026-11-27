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
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ChatMessage(val text: String, val isUser: Boolean)

data class UpdateInfo(val available: Boolean, val title: String, val apkUrl: String, val updatedAt: String)

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
                    onSpeak = { text -> speakSmart(text) },
                    onStopSpeak = { try { tts?.stop() } catch (_: Exception) {} }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                val result = tts?.setLanguage(Locale("hi", "IN"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("en", "IN"))
                }
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(0.95f)
                isTtsReady = true
            } catch (_: Exception) {}
        }
    }

    private fun speakSmart(text: String) {
        if (isTtsReady) {
            try {
                val cleanText = text.replace("*", "").replace("#", "")
                tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")
            } catch (_: Exception) {}
        }
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
    var selectedMode by remember { mutableStateOf("HINGLISH") }
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var pendingContactQuery by remember { mutableStateOf<String?>(null) }

    val messages = remember {
        mutableStateListOf(
            ChatMessage("Namaste Lekhram ji! Main JARVIS hoon. Ab main Hindi, English aur Hinglish teenon mein baat aur commands samajh sakta hoon.", isUser = false)
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val lastInstalledDate = prefs.getString("last_installed_update", "") ?: ""
            val update = checkGitHubUpdate(lastInstalledDate)
            if (update != null && update.available) {
                pendingUpdate = update
                messages.add(ChatMessage("Sir, JARVIS ka naya update (${update.title}) available hai! Upar update icon se 1-click mein install karein.", isUser = false))
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

    fun triggerAutoUpdate(update: UpdateInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Kripya 'Install unknown apps' ki permission dein", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            isDownloadingUpdate = true
            Toast.makeText(context, "Naya update download ho raha hai...", Toast.LENGTH_SHORT).show()
            downloadAndInstallApk(context, update.apkUrl) { success ->
                isDownloadingUpdate = false
                if (success) {
                    prefs.edit().putString("last_installed_update", update.updatedAt).apply()
                    pendingUpdate = null
                }
            }
        } catch (e: Exception) {
            isDownloadingUpdate = false
            Toast.makeText(context, "Update error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    var retryQueryCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val savedQuery = pendingContactQuery
        pendingContactQuery = null
        if (granted && savedQuery != null) {
            retryQueryCallback?.invoke(savedQuery)
        } else if (!granted) {
            val msg = "Contact naam se call ya message karne ke liye Contacts permission zaroori hai sir."
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
                    val msg = "Ji Lekhram ji, latest version (${update.title}) mil gaya hai. Download shuru kar raha hoon."
                    messages.add(ChatMessage(msg, isUser = false))
                    if (voiceOutputEnabled) onSpeak(msg)
                    triggerAutoUpdate(update)
                } else {
                    val msg = "Aapka JARVIS pehle se hi latest version par hai sir."
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
            "Command chalane mein samasya aayi: ${e.localizedMessage}"
        
