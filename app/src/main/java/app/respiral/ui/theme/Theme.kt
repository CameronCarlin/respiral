package app.respiral.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val RespiralColorScheme = lightColorScheme(
    primary = Sage,
    onPrimary = Espresso,
    primaryContainer = Paper,
    onPrimaryContainer = Espresso,
    secondary = Terracotta,
    onSecondary = Paper,
    secondaryContainer = Mustard,
    onSecondaryContainer = Espresso,
    background = Paper,
    onBackground = Espresso,
    surface = Paper,
    onSurface = Espresso,
)

@Composable
fun RespiralTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RespiralColorScheme,
        content = content,
    )
}
