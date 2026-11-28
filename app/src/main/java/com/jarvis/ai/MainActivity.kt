mkdir -p /sdcard/Android/data/com.jarvis.ai/files && cp /sdcard/Download/jarvis_offline.bin /sdcard/Android/data/com.jarvis.ai/files/jarvis_offline.bin && python -c '
p = "/data/data/com.termux/files/home/code.kt"
c = open(p).read()
old_fn = """val dl = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "jarvis_offline.bin")"""
new_fn = """val extPriv = java.io.File(context.getExternalFilesDir(null), "jarvis_offline.bin")
        if (extPriv.exists() && extPriv.length() > 50_000_000L) return extPriv
        val dl = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "jarvis_offline.bin")"""
assert old_fn in c, "Path block not found"
open(p, "w").write(c.replace(old_fn, new_fn, 1))
print("PRIVATE_STORAGE_PATH_OK!")
' && python ~/push.py $(cat ~/.gh_token)
