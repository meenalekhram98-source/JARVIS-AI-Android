package com.jarvis.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import org.json.JSONObject

object DynamicExecutor {

    fun executeJsonAction(context: Context, jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr.trim())
            val type = obj.optString("type").uppercase()

            when (type) {
                "BATTERY" -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    "जी लेखराम जी, फोन की बैटरी अभी $pct% है" + if (isCharging) " और चार्ज हो रही है।" else "।"
                }

                "STORAGE" -> {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val freeGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
                    val totalGb = (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
                    String.format("जी सर, फोन में %.1f GB स्टोरेज खाली है (कुल %.1f GB में से)।", freeGb, totalGb)
                }

                "DEVICE_INFO" -> {
                    "यह ${Build.MANUFACTURER.uppercase()} ${Build.MODEL} है, जो Android ${Build.VERSION.RELEASE} पर चल रहा है।"
                }

                "VOLUME" -> {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val action = obj.optString("action").lowercase()
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val target = when (action) {
                        "up" -> (current + (max / 4)).coerceAtMost(max)
                        "down" -> (current - (max / 4)).coerceAtLeast(0)
                        "mute" -> 0
                        "max" -> max
                        else -> ((obj.optInt("percent", 50) * max) / 100).coerceIn(0, max)
                    }
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    "जी लेखराम जी, मीडिया वॉल्यूम सेट कर दिया गया है।"
                }

                "BRIGHTNESS" -> {
                    val level = obj.optInt("percent", 50).coerceIn(1, 100)
                    if (Settings.System.canWrite(context)) {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (level * 255) / 100)
                        "जी लेखराम जी, स्क्रीन की ब्राइटनेस $level% कर दी गई है।"
                    } else {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "सर, ब्राइटनेस कंट्रोल करने के लिए कृपया 'Modify system settings' की अनुमति ऑन कर दें।"
                    }
                }

                "INTENT" -> {
                    val actionName = obj.optString("action")
                    val uriData = obj.optString("uri")
                    val pkg = obj.optString("package")
                    val intent = if (uriData.isNotBlank()) {
                        Intent(actionName.ifBlank { Intent.ACTION_VIEW }, Uri.parse(uriData))
                    } else {
                        Intent(actionName)
                    }
                    if (pkg.isNotBlank()) intent.setPackage(pkg)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    obj.optString("reply", "जी लेखराम जी, कमांड पूरा कर दिया गया है।")
                }

                "OPEN_APP" -> {
                    val pkg = obj.optString("package")
                    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        obj.optString("reply", "जी लेखराम जी, ऐप खोल दिया गया है।")
                    } else {
                        "माफ़ कीजिए सर, यह ऐप फोन में इंस्टॉल नहीं है।"
                    }
                }

                else -> "कमांड समझ नहीं आया।"
            }
        } catch (e: Exception) {
            "सिस्टम कमांड चलाने में समस्या आई: ${e.localizedMessage}"
        }
    }
}
