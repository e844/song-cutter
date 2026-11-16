package com.king400.songcutter.audio

import java.io.Closeable
import java.io.OutputStream

/** גשר ל-LAME (קוד C שנבנה עם האפליקציה). */
object Mp3Encoder {
    init { System.loadLibrary("lamejni") }

    @JvmStatic external fun nativeInit(inRate: Int, channels: Int, kbps: Int, quality: Int): Long
    @JvmStatic external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int, channels: Int, out: ByteArray): Int
    @JvmStatic external fun nativeFlush(handle: Long, out: ByteArray): Int
    @JvmStatic external fun nativeClose(handle: Long)
}

/**
 * כותב MP3 בקצב קבוע של 320kbps (CBR) מ-PCM 16bit משולב (מונו או סטריאו).
 * LAME ממיר בעצמו לתדירות 44.1kHz אם המקור שונה (למשל 48kHz בסרטונים).
 */
class Mp3Writer(private val inRate: Int, private val channels: Int, private val out: OutputStream, kbps: Int = 320) : Closeable {
    private var handle: Long = Mp3Encoder.nativeInit(inRate, channels, kbps, 2)
    private var buf = ByteArray(16384)

    init {
        require(channels == 1 || channels == 2) { "channels" }
        check(handle != 0L) { "lame init failed" }
    }

    fun write(pcm: ShortArray, frames: Int) {
        if (frames <= 0) return
        // הגודל המקסימלי לפי LAME: ‎1.25 × דגימות + 7200 – לפי מספר הדגימות *אחרי* ההמרה ל-44.1kHz
        val need = (frames * 1.25 * maxOf(1.0, 44100.0 / inRate)).toInt() + 7200
        if (buf.size < need) buf = ByteArray(need)
        val n = Mp3Encoder.nativeEncode(handle, pcm, frames, channels, buf)
        check(n >= 0) { "lame encode failed: $n" }
        if (n > 0) out.write(buf, 0, n)
    }

    fun finish() {
        val n = Mp3Encoder.nativeFlush(handle, buf)
        if (n > 0) out.write(buf, 0, n)
        out.flush()
    }

    override fun close() {
        if (handle != 0L) { Mp3Encoder.nativeClose(handle); handle = 0L }
    }
}
