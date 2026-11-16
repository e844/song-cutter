package com.king400.songcutter.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF5B43C7), onPrimary = Color.White, primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF1C0A5E),
    secondary = Color(0xFF00897B), tertiary = Color(0xFFD8573C),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB9A2FF), onPrimary = Color(0xFF2A1468), primaryContainer = Color(0xFF3E2C86), onPrimaryContainer = Color(0xFFE8DEFF),
    secondary = Color(0xFF6FE0C4), tertiary = Color(0xFFFF9F8A),
)

/** Material You: צבעים מהטפט בטלפונים עם Android 12 ומעלה */
@Composable
fun CutterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** צבעי הסמנים – קבועים, כדי שיהיה ברור מי ההתחלה ומי הסוף */
object MarkerColors {
    val start = Color(0xFF1DB98F)
    val end = Color(0xFFF0694F)
}
