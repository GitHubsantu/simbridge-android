package `in`.imdevops.simbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001B4D),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE4E8F3),
    onSurfaceVariant = Color(0xFF44485A),
    outline = Color(0xFF747890),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3FA),
    surfaceContainer = Color(0xFFEBEEF7),
    surfaceContainerHigh = Color(0xFFE6E9F4),
    surfaceContainerHighest = Color(0xFFE1E5F1),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF0B3FB5),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF7FD6C8),
    onSecondary = Color(0xFF003731),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFE3E4EB),
    surface = Color(0xFF0F1117),
    onSurface = Color(0xFFE3E4EB),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFC4C7D6),
    outline = Color(0xFF8E92A6),
    surfaceContainerLowest = Color(0xFF0A0C11),
    surfaceContainerLow = Color(0xFF161922),
    surfaceContainer = Color(0xFF1B1F29),
    surfaceContainerHigh = Color(0xFF252A36),
    surfaceContainerHighest = Color(0xFF2F3442),
    error = Color(0xFFFFB4AB),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun SimBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
