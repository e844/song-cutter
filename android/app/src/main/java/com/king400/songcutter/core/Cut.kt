package com.king400.songcutter.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** הלוגיקה של החיתוך – בלי אנדרואיד, כדי שאפשר יהיה לבדוק אותה. */
object Cut {
    const val BITRATE_KBPS = 320
    const val MIN_LENGTH = 0.1
    const val ENVELOPE_RATE = 100 // נקודות לשנייה בגל הקול

    /** 75.3 → "1:15.3" ; 3725 → "1:02:05.0" */
    fun formatTime(sec: Double): String {
        val s0 = if (sec.isFinite() && sec > 0) sec else 0.0
        val tenths = (s0 * 10).roundToLong()
        val t = tenths % 10
        val total = tenths / 10
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d.%d", h, m, s, t)
        else String.format(Locale.ROOT, "%d:%02d.%d", m, s, t)
    }

    /** "1:15.3" / "75.3" / "1:02:05" / "1,5" → שניות, או null */
    fun parseTime(text: String): Double? {
        val s = text.trim().replace(',', '.')
        if (!Regex("^\\d+(:\\d{1,2}){0,2}(\\.\\d+)?$").matches(s)) return null
        var sec = 0.0
        for (p in s.split(':')) sec = sec * 60 + (p.toDoubleOrNull() ?: return null)
        return sec
    }

    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    data class Selection(val start: Double, val end: Double) {
        val length get() = end - start
    }

    fun normalize(start: Double, end: Double, duration: Double, minLen: Double = MIN_LENGTH): Selection {
        val d = max(0.0, duration)
        var a = start.coerceIn(0.0, d)
        var b = end.coerceIn(0.0, d)
        if (b < a) { val t = a; a = b; b = t }
        if (b - a < minLen) {
            b = min(d, a + minLen)
            a = max(0.0, b - minLen)
        }
        return Selection(r3(a), r3(b))
    }

    /** הזזת סמן אחד בלי לעבור את השני */
    fun move(sel: Selection, startMarker: Boolean, value: Double, duration: Double, minLen: Double = MIN_LENGTH): Selection {
        val v = value.coerceIn(0.0, max(0.0, duration))
        return if (startMarker) Selection(r3(min(v, sel.end - minLen).coerceAtLeast(0.0)), sel.end)
        else Selection(sel.start, r3(max(v, sel.start + minLen).coerceAtMost(duration)))
    }

    /** עוצמה (0..1) בנקודה t בתוך הקטע – לעמעום בהתחלה ובסוף */
    fun fadeGain(t: Double, length: Double, fadeIn: Double, fadeOut: Double): Float {
        val fi = min(max(0.0, fadeIn), length / 2)
        val fo = min(max(0.0, fadeOut), length / 2)
        var g = 1.0
        if (fi > 0 && t < fi) g = min(g, max(0.0, t / fi))
        if (fo > 0 && t > length - fo) g = min(g, max(0.0, (length - t) / fo))
        return g.toFloat()
    }

    /** שם מוצע: "שיר (קטע 0.30.0-1.00.0).mp3" – בלי תווים אסורים */
    fun suggestName(source: String, sel: Selection, ringtone: Boolean): String {
        val base = source.replace(Regex("\\.[^.]+$"), "").replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { "שיר" }
        val tag = if (ringtone) "רינגטון" else "קטע"
        val range = "${formatTime(sel.start)}-${formatTime(sel.end)}".replace(':', '.')
        return "$base ($tag $range).mp3"
    }

    fun baseName(name: String) = name.replace(Regex("\\.[^.]+$"), "")
}

/**
 * גל הקול: לכל 10ms – העוצמה המקסימלית (0..1). מקבל PCM 16bit משולב (interleaved) בהמשכים.
 */
class EnvelopeBuilder(sampleRate: Int, private val channels: Int) {
    private val block = max(1, sampleRate / Cut.ENVELOPE_RATE)
    private var out = FloatArray(1024)
    private var size = 0
    private var cur = 0f
    private var n = 0
    var frames = 0L
        private set

    fun push(pcm: ShortArray, frameCount: Int) {
        var i = 0
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val v = abs(pcm[i++].toInt()) / 32768f
                if (v > cur) cur = v
            }
            if (++n == block) add()
        }
        frames += frameCount
    }

    private fun add() {
        if (size == out.size) out = out.copyOf(out.size * 2)
        out[size++] = cur
        cur = 0f
        n = 0
    }

    fun finish(): FloatArray {
        if (n > 0) add()
        return out.copyOf(size)
    }

    /** מקסימום של טווח בגל (לציור עמודה אחת) */
    companion object {
        fun peak(env: FloatArray, fromSec: Double, toSec: Double): Float {
            if (env.isEmpty()) return 0f
            val i0 = floor(fromSec * Cut.ENVELOPE_RATE).toInt().coerceIn(0, env.size - 1)
            val i1 = floor(toSec * Cut.ENVELOPE_RATE).toInt().coerceIn(i0 + 1, env.size)
            var m = 0f
            for (i in i0 until i1) if (env[i] > m) m = env[i]
            return m
        }
    }
}
