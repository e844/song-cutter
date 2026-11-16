package com.king400.songcutter

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.king400.songcutter.ui.Txt
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** הממשק: פתיחת שיר, גרירת סמנים, השמעה, רינגטון והיסטוריה + צילומי מסך */
@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val bmp: Bitmap = inst.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After fun close() { scenario?.close() }

    @Test fun startScreen() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText(Txt.PICK_TITLE).assertIsDisplayed()
        compose.onNodeWithTag("credit").assertIsDisplayed()
        shot("android-1-start")
    }

    @Test fun cutFlow() {
        val src = TestAudio.wav(File(ctx.cacheDir, "שיר לדוגמה.wav"), 60, rate = 44100)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.open(Uri.fromFile(src)) } // כמו בחירת קובץ (בלי חלון המערכת)
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("file_name")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("שיר לדוגמה").assertIsDisplayed()

        // גרירת הסמנים
        val before = compose.onNodeWithTag("sel_len").fetchSemanticsNode().config.toString()
        compose.onNodeWithTag("wave").performTouchInput {
            swipe(Offset(left + 2f, centerY), Offset(left + width * 0.25f, centerY), 400)
        }
        compose.onNodeWithTag("wave").performTouchInput {
            swipe(Offset(right - 2f, centerY), Offset(left + width * 0.7f, centerY), 400)
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("sel_len").fetchSemanticsNode().config.toString()
        assertNotEquals(before, after)
        shot("android-2-editor")

        compose.onNodeWithTag("play").performScrollTo().performClick()
        Thread.sleep(800)
        shot("android-3-playing")
        compose.onNodeWithTag("play").performClick()

        // רינגטון
        compose.onNodeWithTag("ringtone").performScrollTo().performClick()
        compose.onNodeWithText(Txt.RING_TITLE).assertIsDisplayed()
        shot("android-4-ringtone")
        compose.onNodeWithTag("ring_save").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(Txt.RING_SAVED)).fetchSemanticsNodes().isNotEmpty() }

        // היסטוריה
        compose.onNodeWithTag("history_btn").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("history_item")).fetchSemanticsNodes().isNotEmpty() }
        shot("android-5-history")
    }
}
