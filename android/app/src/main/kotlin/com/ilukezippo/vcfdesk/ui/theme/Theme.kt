package com.ilukezippo.vcfdesk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    primaryContainer = Sel,
    onPrimaryContainer = TextMain,
    secondary = AccentHover,
    onSecondary = Ink,
    secondaryContainer = Sel,
    onSecondaryContainer = TextMain,
    background = Bg,
    onBackground = TextMain,
    surface = Panel,
    onSurface = TextMain,
    surfaceVariant = Card,
    onSurfaceVariant = Muted,
    surfaceContainer = Panel,
    surfaceContainerHigh = Card,
    surfaceContainerHighest = Field,
    outline = Border,
    outlineVariant = Border,
    error = Danger,
    onError = Ink,
)

/** The app is dark-only, like the desktop app. */
@Composable
fun VcfDeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
