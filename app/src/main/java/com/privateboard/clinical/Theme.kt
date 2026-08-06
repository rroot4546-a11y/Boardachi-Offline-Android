package com.privateboard.clinical

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val Ink=Color(0xFF102A2A); val Teal=Color(0xFF006B64); val Mint=Color(0xFFBDEFE4); val Aqua=Color(0xFF59D2C4)
val Paper=Color(0xFFF7FAF9); val Amber=Color(0xFFF7B84B); val Red=Color(0xFFBA3B46); val Night=Color(0xFF091C1D)
private val LightColors=lightColorScheme(primary=Teal,onPrimary=Color.White,primaryContainer=Mint,onPrimaryContainer=Ink,secondary=Color(0xFF50615E),tertiary=Color(0xFF755B00),background=Paper,surface=Color.White,surfaceVariant=Color(0xFFE4EFEC),onSurface=Ink,error=Red)
private val DarkColors=darkColorScheme(primary=Aqua,onPrimary=Color(0xFF003732),primaryContainer=Color(0xFF005049),onPrimaryContainer=Mint,secondary=Color(0xFFB5CCC7),background=Night,surface=Color(0xFF102829),surfaceVariant=Color(0xFF29413F),onSurface=Color(0xFFE3F4F0),error=Color(0xFFFFB3B8))

@Composable
fun ClinicalTheme(dark: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        view.context.findActivity()?.window?.let { window ->
            window.statusBarColor = if (dark) Night.value.toInt() else Paper.value.toInt()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
