package com.king400.songcutter.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class NoAudioException : Exception("no-audio")

/**
 * מפענח כל קובץ שאנדרואיד יודע לקרוא (MP3, AAC/M4A, FLAC, OGG/Opus, WAV, וגם השמע מתוך סרטונים)
 * ל-PCM 16bit משולב, מונו או סטריאו (5.1 מומר לסטריאו). עובד בהמשכים – בלי לטעון את כל השיר לזיכרון.
 */
class PcmDecoder(private val context: Context, private val uri: Uri) {

    data class Info(val mime: String, val sampleRate: Int, val channels: Int, val durationUs: Long, val bitrate: Int?)

    private fun open(): Pair<MediaExtractor, Int> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release(); throw e
        }
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (track == null) { ex.release(); throw NoAudioException() }
        return ex to track
    }

    fun info(): Info {
        val (ex, track) = open()
        try {
            val f = ex.getTrackFormat(track)
            return Info(
                mime = f.getString(MediaFormat.KEY_MIME) ?: "audio/*",
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L,
                bitrate = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) else null,
            )
        } finally {
            ex.release()
        }
    }

    /**
     * מפענח את הטווח [startUs, endUs). onChunk(pcm, frames, sampleRate, channels) – מחזיר false כדי לעצור.
     */
    fun decode(
        startUs: Long = 0L,
        endUs: Long = Long.MAX_VALUE,
        isCancelled: () -> Boolean = { false },
        onChunk: (pcm: ShortArray, frames: Int, sampleRate: Int, channels: Int, ptsUs: Long) -> Boolean,
    ) {
        val (ex, track) = open()
        val format = ex.getTrackFormat(track)
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var stopped = false

            while (!outputDone && !stopped) {
                if (isCancelled()) throw InterruptedException("cancelled")
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || (endUs != Long.MAX_VALUE && ex.sampleTime > endUs + 500_000)) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                } else if (oi >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val (pcm, frames, outCh) = toStereoOrMono(ob, ch, pcmEncoding)
                        val p0 = info.presentationTimeUs
                        // חיתוך מדויק לפי זמן כל דגימה
                        val k0 = ceil((startUs - p0) * rate / 1e6).toLong().coerceIn(0, frames.toLong()).toInt()
                        val k1 = if (endUs == Long.MAX_VALUE) frames else floor((endUs - p0) * rate / 1e6).toLong().coerceIn(0, frames.toLong()).toInt()
                        if (p0 >= endUs) stopped = true
                        else if (k1 > k0) {
                            val slice = if (k0 == 0 && k1 == frames) pcm else pcm.copyOfRange(k0 * outCh, k1 * outCh)
                            if (!onChunk(slice, k1 - k0, rate, outCh, p0 + (k0 * 1e6 / rate).toLong())) stopped = true
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) { /* כבר נעצר */ }
            codec.release()
            ex.release()
        }
    }

    private fun toStereoOrMono(ob: java.nio.ByteBuffer, ch: Int, enc: Int): Triple<ShortArray, Int, Int> {
        val outCh = if (ch == 1) 1 else 2
        val src: ShortArray
        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer()
            src = ShortArray(fb.remaining()) { (fb.get().coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }
        } else {
            val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            src = ShortArray(sb.remaining())
            sb.get(src)
        }
        val frames = src.size / ch
        if (ch <= 2) return Triple(if (src.size == frames * ch) src else src.copyOf(frames * ch), frames, outCh)
        // יותר משני ערוצים (5.1): ממזגים לסטריאו – שמאל/ימין + מרכז + אחוריים
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val b = f * ch
            var l = src[b].toFloat()
            var r = src[b + 1].toFloat()
            if (ch >= 3) { val c = src[b + 2] * 0.707f; l += c; r += c }
            if (ch >= 6) { l += src[b + 4] * 0.707f; r += src[b + 5] * 0.707f }
            val norm = if (ch >= 6) 2.414f else 1.707f
            out[f * 2] = (l / norm).roundToInt().coerceIn(-32768, 32767).toShort()
            out[f * 2 + 1] = (r / norm).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return Triple(out, frames, 2)
    }
}
