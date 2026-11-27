package com.jarvis.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Locale

fun findFlashCameraId(cameraManager: CameraManager): String? {
    var fallbackFlashId: String? = null
    for (id in cameraManager.cameraIdList) {
        val chars = cameraManager.getCameraCharacteristics(id)
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (hasFlash) {
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallbackFlashId == null) fallbackFlashId = id
        }
    }
    return fallbackFlashId
}

fun findContactPhoneByName(context: Context, searchName: String): Pair<String, String>? {
    val target = searchName.trim().lowercase(Locale.getDefault())
    if (target.length < 2) return null
    val cursor = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        ),
        null,
        null,
        null
    )
    cursor?.use {
        var partialMatch: Pair<String, String>? = null
        while (it.moveToNext()) {
            val name = it.getString(0) ?: continue
            val number = it.getString(1)?.replace(Regex("[^0-9+]"), "") ?: continue
            val nameLower = name.lowercase(Locale.getDefault())
            if (nameLower == target) return name to number
            if (nameLower.contains(target) || target.contains(nameLower)) {
                partialMatch = name to number
            }
        }
        return partialMatch
    }
    return null
}

fun handleDeviceCommand(context: Context, rawQuery: String): String? {
    val q = rawQuery.lowercase(Locale.getDefault()).trim()

    // 1. Flashlight / Torch Control
    if (q.contains("torch") || q.contains("flashlight") || q.contains("टॉर्च") || q.contains("फ्लैशलाइट") || q.contains("लाइट")) {
        val turnOn = q.contains("on") || q.contains("ऑन") || q.contains("चालू") || q.contains("जला") || q.contains("ओपन") || q.contains("खोल")
        val turnOff = q.contains("off") || q.contains("ऑफ") || q.contains("बंद") || q.contains("बुझा")
        if (turnOn || turnOff) {
            return try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = findFlashCameraId(cameraManager)
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, turnOn)
                    if (turnOn) "जी लेखराम जी, टॉर्च ऑन कर दी गई है।" else "जी लेखराम जी, टॉर्च बंद कर दी गई है।"
                } else {
                    "माफ़ कीजिए सर, इस फोन में फ्लैशलाइट हार्डवेयर नहीं मिला।"
                }
            } catch (e: Exception) {
                "टॉर्च चालू करने में समस्या आई: ${e.localizedMessage}"
            }
        }
    }

    // 2. Timer Control
    if (q.contains("timer") || q.contains("टाइमर")) {
        val numMatch = Regex("(\\d+)").find(q)
        val amount = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val isSec = q.contains("second") || q.contains("सेकंड")
        val seconds = if (isSec) amount else amount * 60
        val unitLabel = if (isSec) "सेकंड" else "मिनट"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "जी लेखराम जी, $amount $unitLabel का टाइमर सेट कर रहा हूँ।"
        } catch (_: Exception) {
            "टाइमर सेट करने में समस्या आई।"
        }
    }

    // 3. Alarm Control
    if (q.contains("alarm") || q.contains("अलार्म")) {
        val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?").find(q)
        var hour = timeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 7
        val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if ((q.contains("शाम") || q.contains("रात") || q.contains("pm")) && hour < 12) {
            hour += 12
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val minFormatted = String.format(Locale.US, "%02d", minute)
            "जी लेखराम जी, $hour:$minFormatted का अलार्म सेट कर रहा हूँ।"
        } catch (_: Exception) {
            "अलार्म सेट करने में समस्या आई।"
        }
    }

    // 4. YouTube Search / Play Video or Song
    if ((q.contains("youtube") || q.contains("यूट्यूब")) && (q.contains("चला") || q.contains("play") || q.contains("search") || q.contains("सर्च") || q.contains("खोज") || q.contains("गाना") || q.contains("वीडियो"))) {
        val searchQuery = rawQuery
            .replace(Regex("(?i)(youtube|यूट्यूब|पर|में|चलाओ|चला|play|search|सर्च|करो|खोजो|गाना|वीडियो)"), "")
            .trim()
        if (searchQuery.isNotBlank()) {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", searchQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                "जी लेखराम जी, YouTube पर '$searchQuery' चला रहा हूँ।"
            } catch (_: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${URLEncoder.encode(searchQuery, "UTF-8")}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(webIntent)
                "जी सर, YouTube पर '$searchQuery' खोज रहा हूँ।"
            }
        }
    }

    // 5. Settings Shortcuts
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

    // 6. WhatsApp Direct Message (By Number OR Contact Name)
    if ((q.contains("whatsapp") || q.contains("व्हाट्सएप") || q.contains("व्हाट्सऐप")) && (q.contains("भेज") || q.contains("send") || q.contains("message") || q.contains("मैसेज") || q.contains("लिख"))) {
        val digits = rawQuery.filter { it.isDigit() }
        if (digits.length >= 10) {
            val phone = if (digits.length == 10) "91$digits" else digits
            val msgText = rawQuery.replace(Regex("(?i)(whatsapp|व्हाट्सएप|व्हाट्सऐप|पर|को|मैसेज|भेजो|भेज|send|message|लिखो)"), "")
                .replace(Regex("[0-9+\\-\\s]{10,}"), "")
                .trim()
                .ifBlank { "नमस्ते" }
            val url = "https://api.whatsapp.com/send?phone=$phone&text=${URLEncoder.encode(msgText, "UTF-8")}"
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "जी लेखराम जी, $digits के लिए WhatsApp मैसेज तैयार कर दिया है।"
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return "NEED_CONTACTS_PERMISSION"
            }
            val cleaned = rawQuery.replace(Regex("(?i)(whatsapp|व्हाट्सएप|व्हाट्सऐप|पर|मैसेज|भेजो|भेज|send|message|लिखो|कि)"), " ").trim()
            val parts = cleaned.split(Regex("\\s+(को|to)\\s+"), limit = 2)
            val candidateName = parts[0].trim()
            val msgText = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].trim() else "नमस्ते"
            val contact = findContactPhoneByName(context, candidateName)
            if (contact != null) {
                val cleanNum = contact.second.filter { it.isDigit() }
                val phone = if (cleanNum.length == 10) "91$cleanNum" else cleanNum
                val url = "https://api.whatsapp.com/send?phone=$phone&text=${URLEncoder.encode(msgText, "UTF-8")}"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "जी लेखराम जी, ${contact.first} को WhatsApp पर '$msgText' भेजने के लिए चैट खोल दी है।"
            } else {
                return "माफ़ कीजिए सर, कॉन्टैक्ट्स में '$candidateName' नाम का नंबर नहीं मिला।"
            }
        }
    }

    // 7. Phone Call (By Number OR Contact Name)
    if (q.contains("call") || q.contains("कॉल") || q.contains("फोन लगा")) {
        val digits = rawQuery.filter { it.isDigit() }
        if (digits.length >= 10) {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "जी सर, $digits पर कॉल लगा रहा हूँ।"
        } else {
            val contactName = rawQuery
                .replace(Regex("(?i)(call|कॉल|करो|लगाओ|लगा|फोन|को|to|please)"), "")
                .trim()
            if (contactName.isNotBlank()) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    return "NEED_CONTACTS_PERMISSION"
                }
                val contact = findContactPhoneByName(context, contactName)
                if (contact != null) {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.second}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return "जी लेखराम जी, ${contact.first} को कॉल लगा रहा हूँ।"
                } else {
                    return "माफ़ कीजिए सर, कॉन्टैक्ट लिस्ट में '$contactName' नाम नहीं मिला।"
                }
            } else {
                context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "जी सर, फोन डायलर खोल रहा हूँ।"
            }
        }
    }

    // 8. Open Popular Apps
    val openKeywords = listOf("open", "खोलो", "खोल", "चलाओ", "ओपन", "ऑन", "on", "चालू")
    if (openKeywords.any { q.contains(it) }) {
        if (q.contains("camera") || q.contains("कैमरा")) {
            context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "जी लेखराम जी, कैमरा खोल रहा हूँ।"
        }
        val appPackages = mapOf(
            listOf("youtube", "यूट्यूब") to ("YouTube" to "com.google.android.youtube"),
            listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप") to ("WhatsApp" to "com.whatsapp"),
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
