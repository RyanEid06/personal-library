package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalAppDarkTheme = staticCompositionLocalOf { false }

private val DarkColorScheme = darkColorScheme(
    primary = Amber500,
    onPrimary = Slate900,
    primaryContainer = Color(0xFF111820),
    onPrimaryContainer = Amber100,
    secondary = Teal500,
    onSecondary = Slate900,
    secondaryContainer = Color(0xFF111820),
    onSecondaryContainer = Teal100,
    tertiary = Indigo600,
    background = Color(0xFF05080C),
    onBackground = Slate50,
    surface = Color(0xFF0C1117),
    onSurface = Slate50,
    surfaceVariant = Color(0xFF111820),
    surfaceDim = Color(0xFF05080C),
    surfaceBright = Color(0xFF111820),
    surfaceContainerLowest = Color(0xFF05080C),
    surfaceContainerLow = Color(0xFF080C11),
    surfaceContainer = Color(0xFF0C1117),
    surfaceContainerHigh = Color(0xFF111820),
    surfaceContainerHighest = Color(0xFF111820),
    outline = Slate400,
    outlineVariant = Color(0xFF202A33),
    onSurfaceVariant = Slate200,
    error = Rose600,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Slate800,
    onPrimary = Color.White,
    primaryContainer = Slate100,
    onPrimaryContainer = Slate900,
    secondary = Amber600,
    onSecondary = Color.White,
    secondaryContainer = Amber100,
    onSecondaryContainer = Slate900,
    tertiary = Teal600,
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate700,
    error = Rose600,
    onError = Color.White
)

@Composable
fun PersonalLibraryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(LocalAppDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
