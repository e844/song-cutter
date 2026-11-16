package com.king400.songcutter.ui

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.king400.songcutter.audio.Analysis
import com.king400.songcutter.audio.Cutter
import com.king400.songcutter.audio.NoAudioException
import com.king400.songcutter.audio.Ringtones
import com.king400.songcutter.core.Cut
import com.king400.songcutter.core.History
import com.king400.songcutter.core.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class CutterViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val historyFile = File(app.filesDir, "history.json")

    var uri by mutableStateOf<Uri?>(null); private set
    var analysis by mutableStateOf<Analysis?>(null); private set
    var loading by mutableStateOf<Float?>(null); private set
    var sel by mutableStateOf(Cut.Selection(0.0, 0.0)); private set
    var viewStart by mutableStateOf(0.0); private set
    var viewDur by mutableStateOf(1.0); private set
    var pos by mutableStateOf(0.0); private set
    var playing by mutableStateOf(false); private set
    var fadeIn by mutableStateOf(0.0)
    var fadeOut by mutableStateOf(0.0)
    var exporting by mutableStateOf<Float?>(null); private set
    var exportingRingtone by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var history by mutableStateOf<List<HistoryItem>>(emptyList()); private set
    var askWriteSettings by mutableStateOf(false)

    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private val cancel = AtomicBoolean(false)
    private var player: MediaPlayer? = null
    private var playerReady = false
    private var playingSel = false
    private var pendingDefault: Uri? = null

    val duration get() = analysis?.duration ?: 0.0
    val zoomed get() = viewDur < duration - 1e-6

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val h = if (historyFile.exists()) History.decode(historyFile.readText()) else emptyList()
            withContext(Dispatchers.Main) { history = h }
        }
        // עדכון נקודת הניגון + עצירה בסוף הקטע + עמעום בהשמעה
        viewModelScope.launch {
            while (isActive) {
                val p = player
                if (p != null && playing) {
                    pos = p.currentPosition / 1000.0
                    if (playingSel) {
                        val v = Cut.fadeGain(pos - sel.start, sel.length, fadeIn, fadeOut)
                        p.setVolume(v, v)
                        if (pos >= sel.end) { pause(); seek(sel.start) }
                    }
                    if (zoomed && (pos > viewStart + viewDur || pos < viewStart)) setView(pos - viewDur * 0.1, viewDur)
                }
                delay(30)
            }
        }
    }

    // ---------- טעינה ----------
    fun load(newUri: Uri, restore: HistoryItem? = null) {
        stopPlayer()
        loadJob?.cancel()
        cancel.set(false)
        loading = 0f
        loadJob = viewModelScope.launch {
            try {
                val a = withContext(Dispatchers.Default) {
                    Cutter.analyze(ctx, newUri, { cancel.get() }) { p -> viewModelScope.launch(Dispatchers.Main) { if (loading != null) loading = p } }
                }
                uri = newUri
                analysis = a
                sel = if (restore != null) Cut.normalize(restore.start, restore.end, a.duration) else Cut.normalize(0.0, a.duration, a.duration)
                if (restore != null) { fadeIn = restore.fadeIn; fadeOut = restore.fadeOut }
                setView(0.0, a.duration)
                pos = sel.start
                preparePlayer(newUri)
            } catch (e: NoAudioException) {
                message = Txt.ERR_NO_AUDIO
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) message = Txt.ERR_READ
            } finally {
                loading = null
            }
        }
    }

    private fun preparePlayer(u: Uri) {
        playerReady = false
        try {
            player = MediaPlayer().apply {
                setDataSource(ctx, u)
                setOnPreparedListener { playerReady = true; seekTo((sel.start * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }
                setOnCompletionListener { this@CutterViewModel.playing = false }
                setOnErrorListener { _, _, _ -> playerReady = false; this@CutterViewModel.playing = false; true }
                prepareAsync()
            }
        } catch (e: Exception) {
            player = null
        }
    }

    private fun stopPlayer() {
        playing = false
        try { player?.release() } catch (e: Exception) { /* ignore */ }
        player = null
        playerReady = false
    }

    // ---------- בחירה וזום ----------
    fun setSelection(s: Cut.Selection) { sel = Cut.normalize(s.start, s.end, duration) }
    fun moveMarker(start: Boolean, value: Double) { sel = Cut.move(sel, start, value, duration) }
    fun nudge(start: Boolean, d: Double) {
        moveMarker(start, (if (start) sel.start else sel.end) + d)
        seek(if (start) sel.start else max(sel.start, sel.end - 2))
    }
    fun markerFromPos(start: Boolean) = moveMarker(start, pos)

    fun setView(start: Double, dur: Double) {
        val d0 = max(duration, 0.5)
        val d = dur.coerceIn(min(0.5, d0), d0)
        viewDur = d
        viewStart = start.coerceIn(0.0, max(0.0, d0 - d))
    }
    fun zoom(factor: Double) {
        val c = viewStart + viewDur / 2
        val nd = viewDur * factor
        setView(c - nd / 2, nd)
    }
    fun zoomAll() = setView(0.0, duration)
    fun zoomSel() = setView(sel.start - sel.length * 0.08, sel.length * 1.16)

    // ---------- השמעה ----------
    fun seek(t: Double) {
        pos = t.coerceIn(0.0, duration)
        if (playerReady) player?.seekTo((pos * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
    }
    fun playSelection() {
        val p = player
        if (p == null || !playerReady) { message = Txt.ERR_PLAY; return }
        if (playing && playingSel) { pause(); return }
        playingSel = true
        seek(sel.start)
        p.setVolume(1f, 1f)
        p.start()
        playing = true
    }
    fun playFromPos() {
        val p = player
        if (p == null || !playerReady) { message = Txt.ERR_PLAY; return }
        if (playing) { pause(); return }
        playingSel = false
        p.setVolume(1f, 1f)
        p.start()
        playing = true
    }
    fun pause() {
        try { player?.pause() } catch (e: Exception) { /* ignore */ }
        player?.setVolume(1f, 1f)
        playing = false
    }
    fun stop() { pause(); playingSel = false; seek(sel.start) }

    // ---------- שמירה ----------
    fun suggestedName(ringtone: Boolean) = Cut.suggestName(analysis?.name ?: "שיר", sel, ringtone)

    /** שמירה למקום שהמשתמש בחר (חלון "שמירה בשם" של אנדרואיד) */
    fun saveTo(out: Uri) = export(out, ringtone = false, setDefault = false)

    /** שמירה כרינגטון בתיקיית Ringtones */
    fun saveRingtone(setDefault: Boolean) {
        val name = suggestedName(true)
        val target = try { Ringtones.create(ctx, name) } catch (e: Exception) { null }
        if (target == null) { message = Txt.ERR_SAVE; return }
        export(target, ringtone = true, setDefault = setDefault)
    }

    private fun export(out: Uri, ringtone: Boolean, setDefault: Boolean) {
        val src = uri ?: return
        val a = analysis ?: return
        pause()
        cancel.set(false)
        exporting = 0f
        exportingRingtone = ringtone
        val s = sel
        val fi = fadeIn
        val fo = fadeOut
        exportJob = viewModelScope.launch {
            try {
                val len = withContext(Dispatchers.Default) {
                    openOut(out).use { os ->
                        Cutter.export(ctx, src, s, fi, fo, os.buffered(1 shl 16), { cancel.get() }) { p ->
                            viewModelScope.launch(Dispatchers.Main) { if (exporting != null) exporting = p }
                        }
                    }
                }
                if (ringtone) Ringtones.publish(ctx, out)
                if (!ringtone) {
                    try {
                        ctx.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } catch (e: Exception) { /* לא כל ספק מאפשר */ }
                }
                val outName = Cutter.displayName(ctx, out)
                addHistory(HistoryItem(
                    id = UUID.randomUUID().toString(), sourceUri = src.toString(), sourceName = a.name,
                    outputUri = out.toString(), outputName = outName, start = s.start, end = s.start + len,
                    ringtone = ringtone, fadeIn = fi, fadeOut = fo, at = System.currentTimeMillis(),
                ))
                message = if (ringtone) {
                    if (setDefault) {
                        if (Ringtones.setDefault(ctx, out)) Txt.RING_SET
                        else { pendingDefault = out; askWriteSettings = true; Txt.RING_SAVED }
                    } else Txt.RING_SAVED
                } else "${Txt.SAVED}: ${Cut.baseName(outName)}"
            } catch (e: Exception) {
                if (ringtone) Ringtones.discard(ctx, out)
                else try { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, out) } catch (x: Exception) { /* ignore */ }
                if (!cancel.get()) message = Txt.ERR_SAVE
            } finally {
                exporting = null
            }
        }
    }

    /** "wt" מקצר קובץ קיים; ספקים שלא תומכים בו – "w" */
    private fun openOut(u: Uri): java.io.OutputStream =
        try { ctx.contentResolver.openOutputStream(u, "wt") } catch (e: Exception) { null }
            ?: ctx.contentResolver.openOutputStream(u, "w") ?: throw java.io.IOException("no output")

    fun cancelWork() { cancel.set(true) }

    /** חזרה מהמסך "שינוי הגדרות מערכת" – מגדירים את הרינגטון אם אושר */
    fun onResume() {
        val p = pendingDefault ?: return
        if (Ringtones.canSetDefault(ctx)) {
            pendingDefault = null
            if (Ringtones.setDefault(ctx, p)) message = Txt.RING_SET
        }
    }

    // ---------- היסטוריה ----------
    private fun addHistory(item: HistoryItem) { history = History.add(history, item); persist() }
    fun removeHistory(id: String) { history = history.filter { it.id != id }; persist() }
    fun clearHistory() { history = emptyList(); persist() }
    private fun persist() {
        val text = History.encode(history)
        viewModelScope.launch(Dispatchers.IO) { historyFile.writeText(text) }
    }

    fun exists(u: String): Boolean = try {
        ctx.contentResolver.openFileDescriptor(Uri.parse(u), "r")?.use { true } ?: false
    } catch (e: Exception) { false }

    fun cutAgain(h: HistoryItem) {
        if (!exists(h.sourceUri)) { message = Txt.ERR_SOURCE_GONE; return }
        load(Uri.parse(h.sourceUri), h)
    }

    override fun onCleared() {
        cancel.set(true)
        stopPlayer()
        super.onCleared()
    }
}
