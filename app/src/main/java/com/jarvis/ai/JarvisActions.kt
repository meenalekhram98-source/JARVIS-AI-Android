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

fun devanagariToRomanKey(input: String): String {
    val normalized = input
        .replace("ड़", "र")
        .replace("ढ़", "ढ")
        .replace("क़", "क")
        .replace("ख़", "ख")
        .replace("ग़", "ग")
        .replace("ज़", "ज")
        .replace("फ़", "फ")

    val map = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ं' to "n", 'ँ' to "n",
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
    )
    val sb = StringBuilder()
    var i = 0
    while (i < normalized.length) {
        val c = normalized[i]
        if (c == '्') {
            i++
            continue
        }
        val mapped = map[c]
        if (mapped != null) {
            sb.append(mapped)
            val isConsonant = c in 'क'..'ह'
            if (isConsonant && i + 1 < normalized.length) {
                val next = normalized[i + 1]
                val nextIsMatraOrHalant = next in listOf('ा', 'ि', 'ी', 'ु', 'ू', 'े', 'ै', 'ो', 'ौ', '्')
                if (!nextIsMatraOrHalant && next != ' ') {
                    sb.append('a')
                }
            }
        } else if (c.isLetterOrDigit() || c == ' ') {
            sb.append(c.lowercaseChar())
        }
        i++
    }
    return sb.toString()
        .replace("ph", "f")
        .replace("w", "v")
        .replace("ee", "i")
        .replace("oo", "u")
        .replace("aa", "a")
        .replace("d", "r")
        .replace(Regex("(.)\\1+"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun findContactPhoneByName(context: Context, searchName: String): Pair<String, String>? {
    val target = searchName.trim().lowercase(Locale.getDefault())
    if (target.length < 2) return null
    val targetRoman = devanagariToRomanKey(target)

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
            val nameRoman = devanagariToRomanKey(nameLower)

            if (nameLower == target || (targetRoman.length >= 2 && nameRoman == targetRoman)) {
                return name to number
            }
            if (nameLower.contains(target) || target.contains(nameLower) ||
                (targetRoman.length >= 2 && (nameRoman.contains(targetRoman) || targetRoman.contains(nameRoman)))) {
                partialMatch = name to number
            }
        }
        return partialMatch
    }
    return null
}

fun handleDeviceCommand(context: Context, rawQuery: String): String? {
    val q = rawQuery.lowercase(Locale.getDefault()).trim()

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
            "जी लेखराम जी, $amount$unitLabel का टाइमर सेट कर रहा हूँ।"
        } catch (_: Exception) {
            "टाइमर सेट करने में समस्या आई।"
        }
    }

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
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${URLEncoder.encode(searchQuery, "UTF-8")}")).addFlags(
