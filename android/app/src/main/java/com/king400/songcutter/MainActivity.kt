package com.king400.songcutter

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.king400.songcutter.core.HistoryItem
import com.king400.songcutter.ui.CutterApp
import com.king400.songcutter.ui.CutterTheme
import com.king400.songcutter.ui.CutterViewModel
import com.king400.songcutter.ui.ScreenActions
import com.king400.songcutter.ui.Txt

class MainActivity : ComponentActivity() {
    private val vm: CutterViewModel by viewModels()
    private var pendingRingtoneDefault = false

    // בחירת קובץ (שיר או סרטון)
    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { /* לא כל ספק מאפשר */ }
            vm.load(uri)
        }
    }

    // "איפה לשמור?" – חלון השמירה של אנדרואיד
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }

    // אנדרואיד 9 ומטה: הרשאת אחסון לשמירה בתיקיית הרינגטונים
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.saveRingtone(pendingRingtoneDefault) else vm.message = Txt.STORAGE_NEEDED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = ScreenActions(
            pickFile = { openDoc.launch(arrayOf("audio/*", "video/*")) },
            saveAs = { name -> createDoc.launch(name) },
            saveRingtone = { setDefault ->
                pendingRingtoneDefault = setDefault
                if (Build.VERSION.SDK_INT < 29 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else vm.saveRingtone(setDefault)
            },
            openWriteSettings = {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            },
            playOutput = { h -> openOutput(h, Intent.ACTION_VIEW) },
            shareOutput = { h -> openOutput(h, Intent.ACTION_SEND) },
        )
        setContent { CutterTheme { CutterApp(vm, actions) } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    /** פתיחת קובץ (גם לבדיקות) */
    fun open(uri: Uri) = vm.load(uri)
    fun lastErrorForTest() = vm.lastError

    /** "פתח באמצעות" / "שתף" מאפליקציה אחרת */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) vm.load(uri)
    }

    private fun openOutput(h: HistoryItem, action: String) {
        val uri = Uri.parse(h.outputUri)
        val i = Intent(action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (action == Intent.ACTION_SEND) i.setType("audio/mpeg").putExtra(Intent.EXTRA_STREAM, uri)
        else i.setDataAndType(uri, "audio/mpeg")
        try {
            startActivity(Intent.createChooser(i, null))
        } catch (e: Exception) {
            vm.message = Txt.ERR_NO_PLAYER
        }
    }
}
