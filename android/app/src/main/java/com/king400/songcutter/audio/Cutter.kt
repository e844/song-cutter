package com.king400.songcutter.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.king400.songcutter.core.Cut
import com.king400.songcutter.core.EnvelopeBuilder
import java.io.OutputStream
import kotlin.math.roundToInt

/** פרטי הקובץ שנטען + גל הקול */
class Analysis(
    val name: String,
    val format: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrateKbps: Int?,
    val duration: Double,
    val envelope: FloatArray,
)

object Cutter {

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (e: Exception) { /* אין שם */ }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "שיר"
    }

    private fun sizeOf(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: Exception) { null }

    private fun formatLabel(mime: String, name: String): String = when {
        mime.contains("mpeg") && !mime.contains("mpegh") -> "MP3"
        mime.contains("mp4a") || mime.contains("aac") -> "AAC"
        mime.contains("flac") -> "FLAC"
        mime.contains("opus") -> "OPUS"
        mime.contains("vorbis") -> "OGG"
        mime.contains("raw") || mime.contains("wav") -> "WAV"
        mime.contains("amr") -> "AMR"
        else -> name.substringAfterLast('.', "").uppercase().ifEmpty { mime.substringAfter('/').uppercase() }
    }

    /** פענוח כל הקובץ לגל קול. progress: 0..1 */
    fun analyze(context: Context, uri: Uri, isCancelled: () -> Boolean, progress: (Float) -> Unit): Analysis {
        val dec = PcmDecoder(context, uri)
        val info = dec.info()
        val name = displayName(context, uri)
        var env: EnvelopeBuilder? = null
        var rate = info.sampleRate
        val total = info.durationUs.coerceAtLeast(1)
        var lastP = -1
        dec.decode(isCancelled = isCancelled) { pcm, frames, r, ch, pts ->
            if (env == null) { env = EnvelopeBuilder(r, ch); rate = r }
            env!!.push(pcm, frames)
            val p = ((pts * 100) / total).toInt()
            if (p != lastP) { lastP = p; progress((p / 100f).coerceIn(0f, 1f)) }
            true
        }
        val e = env ?: throw NoAudioException()
        val duration = e.frames.toDouble() / rate
        val kbps = info.bitrate?.let { (it / 1000.0).roundToInt() }
            ?: sizeOf(context, uri)?.let { size -> if (duration > 0) ((size * 8 / duration) / 1000).roundToInt() else null }
        return Analysis(name, formatLabel(info.mime, name), info.sampleRate, info.channels, kbps, duration, e.finish())
    }

    /**
     * שמירת הקטע כ-MP3 ‏320kbps, עם עמעום בהתחלה/בסוף. מחזיר את האורך בשניות.
     */
    fun export(
        context: Context, uri: Uri, sel: Cut.Selection, fadeIn: Double, fadeOut: Double,
        out: OutputStream, isCancelled: () -> Boolean, progress: (Float) -> Unit,
    ): Double {
        var writer: Mp3Writer? = null
        var rate = 44100
        var written = 0L
        val length = sel.length
        try {
            PcmDecoder(context, uri).decode((sel.start * 1e6).toLong(), (sel.end * 1e6).toLong(), isCancelled) { pcm, frames, r, ch, _ ->
                if (writer == null) { writer = Mp3Writer(r, ch, out, Cut.BITRATE_KBPS); rate = r }
                if (fadeIn > 0 || fadeOut > 0) {
                    for (f in 0 until frames) {
                        val g = Cut.fadeGain((written + f).toDouble() / rate, length, fadeIn, fadeOut)
                        if (g < 1f) for (c in 0 until ch) {
                            val i = f * ch + c
                            pcm[i] = (pcm[i] * g).roundToInt().toShort()
                        }
                    }
                }
                writer!!.write(pcm, frames)
                written += frames
                progress((written.toDouble() / rate / length).toFloat().coerceIn(0f, 1f))
                true
            }
            val w = writer ?: throw NoAudioException()
            w.finish()
            return written.toDouble() / rate
        } finally {
            writer?.close()
        }
    }
}
