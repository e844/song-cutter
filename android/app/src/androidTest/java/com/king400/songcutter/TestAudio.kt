package com.king400.songcutter

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** קובץ WAV לבדיקה: "שיר" עם עוצמה משתנה */
object TestAudio {
    fun wav(file: File, seconds: Int, rate: Int = 48000, channels: Int = 2): File {
        val frames = seconds * rate
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val env = 0.35 + 0.65 * abs(sin(PI * t / 3))
            for (c in 0 until channels) {
                val f = if (c == 0) 220.0 else 330.0
                data.putShort((sin(2 * PI * f * t) * env * 0.6 * 32767).toInt().toShort())
            }
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.capacity()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(rate)
        h.putInt(rate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.capacity())
        FileOutputStream(file).use { it.write(h.array()); it.write(data.array()) }
        return file
    }
}
