package com.king400.songcutter

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.king400.songcutter.audio.Cutter
import com.king400.songcutter.audio.PcmDecoder
import com.king400.songcutter.core.Cut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** חיתוך אמיתי בטלפון: פענוח, חיתוך, עמעום ושמירה ב-MP3 ‏320kbps */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun cutWavToMp3At320() {
        val src = TestAudio.wav(File(ctx.cacheDir, "song.wav"), 10)
        val a = Cutter.analyze(ctx, Uri.fromFile(src), { false }) {}
        assertEquals(10.0, a.duration, 0.05)
        assertEquals(1000.0, a.envelope.size.toDouble(), 2.0)
        assertEquals("WAV", a.format)

        val out = File(ctx.cacheDir, "cut.mp3")
        val len = out.outputStream().use { Cutter.export(ctx, Uri.fromFile(src), Cut.Selection(2.0, 6.5), 1.0, 1.0, it, { false }) {} }
        assertEquals(4.5, len, 0.01)

        // קובץ MP3, באורך הנכון, ובקצב 320kbps (כל הפריימים)
        val ex = MediaExtractor().apply { setDataSource(out.path) }
        val f = ex.getTrackFormat(0)
        assertEquals("audio/mpeg", f.getString(MediaFormat.KEY_MIME))
        assertEquals(44100, f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(4.5, f.getLong(MediaFormat.KEY_DURATION) / 1e6, 0.1)
        ex.release()
        val bytes = out.readBytes()
        var frames = 0
        var i = 0
        while (i + 4 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xFE) == 0xFA) {
                val brIndex = (bytes[i + 2].toInt() and 0xF0) shr 4
                assertEquals("bitrate index at $i", 14, brIndex) // 14 = 320kbps ב-MPEG1 Layer III
                val pad = (bytes[i + 2].toInt() shr 1) and 1
                i += 144 * 320000 / 44100 + pad
                frames++
            } else i++
        }
        assertTrue("frames=$frames", frames > 160)
        assertEquals(4.5 * 320000 / 8, bytes.size.toDouble(), 4.5 * 320000 / 8 * 0.05)

        // העמעום עובד: ההתחלה שקטה מהאמצע
        val env = Cutter.analyze(ctx, Uri.fromFile(out), { false }) {}.envelope
        assertTrue("${env[3]} vs ${env[220]}", env[3] < env[220] * 0.3)
    }

    @Test fun mp3SourceAndMonoWork() {
        val src = TestAudio.wav(File(ctx.cacheDir, "mono.wav"), 6, rate = 22050, channels = 1)
        val mid = File(ctx.cacheDir, "mid.mp3")
        mid.outputStream().use { Cutter.export(ctx, Uri.fromFile(src), Cut.Selection(0.0, 6.0), 0.0, 0.0, it, { false }) {} }
        // חיתוך מתוך קובץ MP3
        val a = Cutter.analyze(ctx, Uri.fromFile(mid), { false }) {}
        assertEquals("MP3", a.format)
        assertTrue(abs(a.duration - 6.0) < 0.15)
        val out = File(ctx.cacheDir, "mid-cut.mp3")
        val len = out.outputStream().use { Cutter.export(ctx, Uri.fromFile(mid), Cut.Selection(1.0, 3.0), 0.0, 0.0, it, { false }) {} }
        assertEquals(2.0, len, 0.05)
        assertEquals(1, PcmDecoder(ctx, Uri.fromFile(out)).info().channels)
    }

    @Test(expected = InterruptedException::class)
    fun cancelStops() {
        val src = TestAudio.wav(File(ctx.cacheDir, "long.wav"), 8)
        var n = 0
        File(ctx.cacheDir, "c.mp3").outputStream().use {
            Cutter.export(ctx, Uri.fromFile(src), Cut.Selection(0.0, 8.0), 0.0, 0.0, it, { n++ > 5 }) {}
        }
    }
}
