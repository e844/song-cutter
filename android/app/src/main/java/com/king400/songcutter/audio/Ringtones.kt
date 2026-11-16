package com.king400.songcutter.audio

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/** שמירה בתיקיית הרינגטונים של הטלפון, כך שהקטע מופיע ברשימת הרינגטונים של אנדרואיד. */
object Ringtones {

    /** יוצר קובץ ריק בתיקיית Ringtones ומחזיר את הכתובת שלו (ב-Android 9 ומטה צריך הרשאת אחסון). */
    fun create(context: Context, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.AudioColumns.IS_RINGTONE, true)
            put(MediaStore.Audio.AudioColumns.IS_NOTIFICATION, false)
            put(MediaStore.Audio.AudioColumns.IS_ALARM, false)
            put(MediaStore.Audio.AudioColumns.IS_MUSIC, false)
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES).apply { mkdirs() }
            val file = uniqueFile(dir, name)
            @Suppress("DEPRECATION")
            values.put(MediaStore.MediaColumns.DATA, file.absolutePath)
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            file.createNewFile()
            context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        }
    }

    /** אחרי שהקובץ נכתב – מסמן אותו כמוכן (שיופיע ברשימות) */
    fun publish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, v, null, null)
        }
    }

    fun discard(context: Context, uri: Uri) {
        try { context.contentResolver.delete(uri, null, null) } catch (e: Exception) { /* כבר לא קיים */ }
    }

    fun canSetDefault(context: Context) = Settings.System.canWrite(context)

    /** הגדרה כרינגטון הראשי (דורש אישור "שינוי הגדרות מערכת") */
    fun setDefault(context: Context, uri: Uri): Boolean = try {
        if (!Settings.System.canWrite(context)) false
        else { RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri); true }
    } catch (e: Exception) { false }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        val base = name.substringBeforeLast('.')
        while (f.exists()) f = File(dir, "$base ($i).mp3").also { i++ }
        return f
    }
}
