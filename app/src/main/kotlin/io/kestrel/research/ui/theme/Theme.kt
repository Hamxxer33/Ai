package io.kestrel.research.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0F766E)
private val TealLight = Color(0xFF5EEAD4)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF475569),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFB91C1C),
)

private val Dark = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF94A3B8),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFF87171),
)

object Signal {
    val ok = Color(0xFF16A34A)
    val warn = Color(0xFFD97706)
    val bad = Color(0xFFDC2626)
}

@Composable
fun KestrelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
