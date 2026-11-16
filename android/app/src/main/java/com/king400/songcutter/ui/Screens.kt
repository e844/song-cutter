package com.king400.songcutter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.king400.songcutter.core.Cut
import com.king400.songcutter.core.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** פעולות שהמסך מבקש מה-Activity (חלונות המערכת לבחירת קובץ ולשמירה) */
class ScreenActions(
    val pickFile: () -> Unit,
    val saveAs: (suggested: String) -> Unit,
    val saveRingtone: (setDefault: Boolean) -> Unit,
    val openWriteSettings: () -> Unit,
    val playOutput: (HistoryItem) -> Unit,
    val shareOutput: (HistoryItem) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutterApp(vm: CutterViewModel, actions: ScreenActions) {
    val snackbar = remember { SnackbarHostState() }
    var showHistory by remember { mutableStateOf(false) }
    var showRingtone by remember { mutableStateOf(false) }

    // מאפסים את ההודעה רק אחרי שהוצגה – איפוס מוקדם מבטל את ההצגה
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        if (vm.message == m) vm.message = null
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(Txt.APP, fontWeight = FontWeight.SemiBold)
                            Text(Txt.CREDIT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("credit"))
                        }
                    },
                    actions = {
                        TextButton(onClick = { showHistory = true }, modifier = Modifier.testTag("history_btn")) { Text("🕘 היסטוריה") }
                        if (vm.analysis != null) TextButton(onClick = actions.pickFile) { Text("📂") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                val a = vm.analysis
                when {
                    vm.loading != null && a == null -> Loading(vm.loading ?: 0f)
                    a == null -> Empty(actions.pickFile)
                    else -> Editor(vm, actions) { showRingtone = true }
                }
                if (vm.loading != null && a != null) Loading(vm.loading ?: 0f)
            }
        }

        if (showRingtone) RingtoneDialog(vm, onDismiss = { showRingtone = false }) { setDefault ->
            showRingtone = false
            actions.saveRingtone(setDefault)
        }
        vm.exporting?.let { p ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(if (vm.exportingRingtone) Txt.SAVING_RINGTONE else Txt.SAVING) },
                text = {
                    Column {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("${(p * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { vm.cancelWork() }) { Text(Txt.CANCEL) } },
            )
        }
        if (vm.askWriteSettings) AlertDialog(
            onDismissRequest = { vm.askWriteSettings = false },
            title = { Text(Txt.RING_PERMISSION_TITLE) },
            text = { Text(Txt.RING_PERMISSION_BODY) },
            confirmButton = { Button(onClick = { vm.askWriteSettings = false; actions.openWriteSettings() }) { Text(Txt.OPEN_SETTINGS) } },
            dismissButton = { TextButton(onClick = { vm.askWriteSettings = false }) { Text(Txt.CANCEL) } },
        )
        if (showHistory) HistorySheet(vm, actions) { showHistory = false }
    }
}

@Composable
private fun Loading(p: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(28.dp).width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Txt.LOADING, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("${(p * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Empty(pick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = pick,
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth().testTag("pick"),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎵", fontSize = 64.sp)
                Spacer(Modifier.height(10.dp))
                Text(Txt.PICK_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(Txt.PICK_SUB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(Txt.PICK_QUALITY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(18.dp))
                Button(onClick = pick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp, vertical = 14.dp)) { Text(Txt.PICK_BUTTON, fontSize = 17.sp) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(Txt.CREDIT, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(vm: CutterViewModel, actions: ScreenActions, onRingtone: () -> Unit) {
    val a = vm.analysis ?: return
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // הקובץ
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🎧", fontSize = 26.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(Cut.baseName(a.name), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("file_name"))
                    val bits = listOfNotNull(
                        Cut.formatTime(a.duration), a.format, a.bitrateKbps?.let { "${it}kbps" },
                        "%.1fkHz".format(Locale.ROOT, a.sampleRate / 1000.0), if (a.channels == 1) Txt.MONO else Txt.STEREO,
                    )
                    Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = actions.pickFile) { Text(Txt.CHANGE) }
            }
        }

        // גל קול + זום
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalIconButton(onClick = { vm.zoom(1 / 0.6) }) { Text("−", fontSize = 20.sp) }
                    FilledTonalIconButton(onClick = { vm.zoom(0.6) }, modifier = Modifier.testTag("zoom_in")) { Text("+", fontSize = 20.sp) }
                    TextButton(onClick = { vm.zoomAll() }) { Text(Txt.ZOOM_ALL) }
                    TextButton(onClick = { vm.zoomSel() }) { Text(Txt.ZOOM_SEL) }
                }
                Waveform(
                    envelope = a.envelope, duration = a.duration, sel = vm.sel, viewStart = vm.viewStart, viewDur = vm.viewDur, pos = vm.pos,
                    onSelection = { vm.setSelection(it) }, onMove = { s, t -> vm.moveMarker(s, t) }, onSeek = { vm.seek(it) },
                )
                if (vm.zoomed) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        androidx.compose.material3.Slider(
                            value = (vm.viewStart / (a.duration - vm.viewDur).coerceAtLeast(1e-6)).toFloat().coerceIn(0f, 1f),
                            onValueChange = { vm.setView(it * (a.duration - vm.viewDur), vm.viewDur) },
                        )
                    }
                }
                Text(Txt.HINT, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // סמנים
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MarkerCard(Txt.START, MarkerColors.start, vm.sel.start, "start", Modifier.weight(1f),
                onSet = { vm.moveMarker(true, it) }, onNudge = { vm.nudge(true, it) }, onFromPos = { vm.markerFromPos(true) })
            MarkerCard(Txt.END, MarkerColors.end, vm.sel.end, "end", Modifier.weight(1f),
                onSet = { vm.moveMarker(false, it) }, onNudge = { vm.nudge(false, it) }, onFromPos = { vm.markerFromPos(false) })
        }

        // השמעה
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Txt.LENGTH, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(Cut.formatTime(vm.sel.length), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("sel_len"))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    FilledTonalIconButton(onClick = { vm.stop() }, modifier = Modifier.size(52.dp)) { Text("⏹", fontSize = 20.sp) }
                    LargeFloatingActionButton(onClick = { vm.playSelection() }, modifier = Modifier.testTag("play")) {
                        Text(if (vm.playing) "⏸" else "▶", fontSize = 30.sp)
                    }
                    FilledTonalIconButton(onClick = { vm.playFromPos() }, modifier = Modifier.size(52.dp)) { Text("⏯", fontSize = 20.sp) }
                }
                Spacer(Modifier.height(8.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text("${Cut.formatTime(vm.pos)} / ${Cut.formatTime(a.duration)}", color = cs.onSurfaceVariant)
                }
            }
        }

        // עמעום
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.padding(14.dp)) {
                FadeRow(Txt.FADE_IN, vm.fadeIn) { vm.fadeIn = it }
                Spacer(Modifier.height(8.dp))
                FadeRow(Txt.FADE_OUT, vm.fadeOut) { vm.fadeOut = it }
            }
        }

        // שמירה
        Button(
            onClick = { actions.saveAs(vm.suggestedName(false)) },
            modifier = Modifier.fillMaxWidth().height(58.dp).testTag("save"),
            shape = RoundedCornerShape(20.dp),
        ) { Text(Txt.SAVE, fontSize = 17.sp) }
        FilledTonalButton(
            onClick = onRingtone,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("ringtone"),
            shape = RoundedCornerShape(20.dp),
        ) { Text(Txt.SAVE_RINGTONE, fontSize = 16.sp) }
        Text(Txt.CREDIT, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FadeRow(label: String, value: Double, onChange: (Double) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (v in Txt.FADE_CHOICES) FilterChip(selected = value == v, onClick = { onChange(v) }, label = { Text(Txt.fadeLabel(v)) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkerCard(
    title: String, color: androidx.compose.ui.graphics.Color, value: Double, tag: String, modifier: Modifier,
    onSet: (Double) -> Unit, onNudge: (Double) -> Unit, onFromPos: () -> Unit,
) {
    var text by remember(value) { mutableStateOf(Cut.formatTime(value)) }
    var bad by remember(value) { mutableStateOf(false) }
    val apply = { Cut.parseTime(text)?.let { onSet(it) } ?: run { bad = true } }
    Card(modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("◆", color = color)
                Spacer(Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; bad = false },
                singleLine = true,
                isError = bad,
                textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center, textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("${tag}_time")
                    .onFocusChanged { if (!it.isFocused && text != Cut.formatTime(value)) apply() },
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    for (d in listOf(-1.0, -0.1, 0.1, 1.0)) {
                        AssistChip(onClick = { onNudge(d) }, label = { Text(if (d > 0) "+${d.toString().removeSuffix(".0")}" else d.toString().removeSuffix(".0").replace("-", "−")) })
                    }
                }
            }
            TextButton(onClick = onFromPos) { Text(Txt.FROM_POS, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun RingtoneDialog(vm: CutterViewModel, onDismiss: () -> Unit, onSave: (Boolean) -> Unit) {
    var setDefault by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Txt.RING_TITLE) },
        text = {
            Column {
                Text(Txt.RING_BODY)
                Spacer(Modifier.height(8.dp))
                Text(Txt.RING_TIP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.sel.length > 40) Text("${Txt.LENGTH}: ${Cut.formatTime(vm.sel.length)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { setDefault = !setDefault }) {
                    Checkbox(checked = setDefault, onCheckedChange = { setDefault = it })
                    Text(Txt.RING_DEFAULT)
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(setDefault) }, modifier = Modifier.testTag("ring_save")) { Text(Txt.RING_SAVE) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Txt.CANCEL) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HistorySheet(vm: CutterViewModel, actions: ScreenActions, onClose: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("he")) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Txt.HISTORY, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (vm.history.isNotEmpty()) TextButton(onClick = { vm.clearHistory() }) { Text(Txt.HISTORY_CLEAR, color = MaterialTheme.colorScheme.error) }
                }
                if (vm.history.isEmpty()) {
                    Text(Txt.HISTORY_EMPTY, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 40.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    items(vm.history, key = { it.id }) { h ->
                        val exists = remember(h.id) { vm.exists(h.outputUri) }
                        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.testTag("history_item")) {
                            Column(Modifier.padding(12.dp)) {
                                Text(Cut.baseName(h.outputName), fontWeight = FontWeight.SemiBold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (h.ringtone) Txt.TAG_RING else Txt.TAG_MP3, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text("${Cut.formatTime(h.start)} – ${Cut.formatTime(h.end)}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(fmt.format(Date(h.at)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${Txt.FROM}: ${Cut.baseName(h.sourceName)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!exists) Text(Txt.H_MISSING, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                FlowRow {
                                    TextButton(onClick = { actions.playOutput(h) }, enabled = exists) { Text(Txt.H_PLAY) }
                                    TextButton(onClick = { actions.shareOutput(h) }, enabled = exists) { Text(Txt.H_SHARE) }
                                    TextButton(onClick = { onClose(); vm.cutAgain(h) }) { Text(Txt.H_AGAIN) }
                                    TextButton(onClick = { vm.removeHistory(h.id) }) { Text(Txt.H_REMOVE) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
