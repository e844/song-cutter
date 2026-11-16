package com.king400.songcutter.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutTest {
    @Test fun formatAndParseTime() {
        assertEquals("0:00.0", Cut.formatTime(0.0))
        assertEquals("1:15.3", Cut.formatTime(75.34))
        assertEquals("1:02:05.0", Cut.formatTime(3725.0))
        assertEquals("1:00.0", Cut.formatTime(59.96))
        assertEquals(75.3, Cut.parseTime("1:15.3")!!, 1e-9)
        assertEquals(75.5, Cut.parseTime("75,5")!!, 1e-9)
        assertEquals(3725.0, Cut.parseTime("1:02:05")!!, 1e-9)
        assertNull(Cut.parseTime("abc"))
        assertNull(Cut.parseTime(""))
    }

    @Test fun selectionStaysValid() {
        assertEquals(Cut.Selection(10.0, 30.0), Cut.normalize(50.0, 10.0, 30.0))
        assertEquals(Cut.Selection(29.9, 30.0), Cut.normalize(30.0, 30.0, 30.0))
        val s = Cut.Selection(10.0, 20.0)
        assertEquals(Cut.Selection(19.9, 20.0), Cut.move(s, true, 25.0, 60.0))
        assertEquals(Cut.Selection(10.0, 10.1), Cut.move(s, false, 5.0, 60.0))
        assertEquals(Cut.Selection(10.0, 60.0), Cut.move(s, false, 99.0, 60.0))
        assertEquals(Cut.Selection(0.0, 20.0), Cut.move(s, true, -3.0, 60.0))
    }

    @Test fun fades() {
        assertEquals(0f, Cut.fadeGain(0.0, 10.0, 2.0, 2.0), 1e-6f)
        assertEquals(0.5f, Cut.fadeGain(1.0, 10.0, 2.0, 2.0), 1e-6f)
        assertEquals(1f, Cut.fadeGain(5.0, 10.0, 2.0, 2.0), 1e-6f)
        assertEquals(0.25f, Cut.fadeGain(9.5, 10.0, 2.0, 2.0), 1e-6f)
        assertEquals(1f, Cut.fadeGain(0.0, 10.0, 0.0, 0.0), 1e-6f)
        assertEquals(0.5f, Cut.fadeGain(1.0, 4.0, 5.0, 0.0), 1e-6f) // עמעום לא ארוך מחצי קטע
    }

    @Test fun names() {
        assertEquals("שיר טוב (קטע 0.30.0-1.00.0).mp3", Cut.suggestName("שיר: \"טוב\".flac", Cut.Selection(30.0, 60.0), false))
        assertEquals("song (רינגטון 0.00.0-0.25.0).mp3", Cut.suggestName("song.m4a", Cut.Selection(0.0, 25.0), true))
    }

    @Test fun envelope() {
        val e = EnvelopeBuilder(8000, 2)
        val pcm = ShortArray(8000 * 2) { i -> if (i / 2 < 4000) (if (i % 2 == 0) 16384 else -8192).toShort() else 0 }
        e.push(pcm.copyOfRange(0, 1000), 500)
        e.push(pcm.copyOfRange(1000, pcm.size), 7500)
        val env = e.finish()
        assertEquals(8000L, e.frames)
        assertEquals(100, env.size)
        assertEquals(0.5f, env[0], 1e-6f)
        assertEquals(0f, env[99], 1e-6f)
        assertEquals(0.5f, EnvelopeBuilder.peak(env, 0.0, 1.0), 1e-6f)
        assertEquals(0f, EnvelopeBuilder.peak(env, 0.6, 1.0), 1e-6f)
        assertArrayEquals(FloatArray(0), EnvelopeBuilder(44100, 1).finish(), 0f)
    }

    @Test fun historyRoundTripAndBadData() {
        var h = emptyList<HistoryItem>()
        for (i in 0 until 205) h = History.add(h, HistoryItem("$i", "content://s/$i", "שיר $i.mp3", "content://o/$i", "קטע $i.mp3", i.toDouble(), i + 1.5, i % 2 == 0, 1.0, 0.0, 1000L + i))
        assertEquals(History.MAX, h.size)
        assertEquals("204", h[0].id)
        val back = History.decode(History.encode(h))
        assertEquals(h, back)
        assertTrue(History.decode("not json").isEmpty())
        assertEquals(1, History.decode("""[{"id":"x"},{"id":"1","sourceUri":"a","sourceName":"b","outputUri":"c","outputName":"d","start":1,"end":2,"ringtone":true,"at":5}]""").size)
    }
}
