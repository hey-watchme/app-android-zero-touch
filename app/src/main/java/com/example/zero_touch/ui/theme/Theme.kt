package com.example.zero_touch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ZtColorScheme = lightColorScheme(
    primary = ZtPrimary,
    onPrimary = ZtOnPrimary,
    primaryContainer = ZtPrimaryContainer,
    onPrimaryContainer = ZtPrimary,
    secondary = ZtOnSurfaceVariant,
    onSecondary = ZtOnPrimary,
    tertiary = ZtSuccess,
    background = ZtBackground,
    onBackground = ZtOnBackground,
    surface = ZtSurface,
    onSurface = ZtOnSurface,
    surfaceVariant = ZtSurfaceVariant,
    onSurfaceVariant = ZtOnSurfaceVariant,
    outline = ZtOutline,
    outlineVariant = ZtOutlineVariant,
    error = ZtError,
    onError = ZtOnPrimary,
)

@Composable
fun ZerotouchTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ZtColorScheme,
        typography = ZtTypography,
        content = content
    )
}
