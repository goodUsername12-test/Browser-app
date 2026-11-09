package com.fable.browser.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.fable.browser.data.model.ThemeMode

private val InkTeal = Color(0xFF0E6E63)
private val InkTealLight = Color(0xFF63D8C7)
private val Violet = Color(0xFF8B7BFF)
private val VioletDeep = Color(0xFF5B44E6)
private val Slate950 = Color(0xFF0B0D10)
private val Slate900 = Color(0xFF14171C)
private val Slate800 = Color(0xFF1E232B)
private val Slate200 = Color(0xFFDDE1E6)
private val Paper = Color(0xFFF7F7F4)
private val PaperRaised = Color(0xFFFFFFFF)
private val Ink = Color(0xFF15181C)
private val Warn = Color(0xFFB3261E)

private val LightScheme = lightColorScheme(
    primary = InkTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEDE7),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A5A63),
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6E7E2),
    onSurfaceVariant = Color(0xFF44474B),
    surfaceContainer = PaperRaised,
    surfaceContainerHigh = Color(0xFFEFEFEB),
    outline = Color(0xFF74777B),
    outlineVariant = Color(0xFFC4C7C9),
    error = Warn,
    onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = InkTealLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF7FF5E3),
    secondary = Color(0xFFB1CBD3),
    onSecondary = Color(0xFF1B343B),
    background = Slate950,
    onBackground = Slate200,
    surface = Slate950,
    onSurface = Slate200,
    surfaceVariant = Slate800,
    onSurfaceVariant = Color(0xFFB9C0C8),
    surfaceContainer = Slate900,
    surfaceContainerHigh = Slate800,
    outline = Color(0xFF6D757E),
    outlineVariant = Color(0xFF3A4048),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val IncognitoScheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF11072E),
    primaryContainer = VioletDeep,
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFFC9BFFF),
    onSecondary = Color(0xFF1E1345),
    background = Color(0xFF08060F),
    onBackground = Color(0xFFE6E1F2),
    surface = Color(0xFF08060F),
    onSurface = Color(0xFFE6E1F2),
    surfaceVariant = Color(0xFF241C3D),
    onSurfaceVariant = Color(0xFFC7C0DC),
    surfaceContainer = Color(0xFF120E22),
    surfaceContainerHigh = Color(0xFF1B1533),
    outline = Color(0xFF8A81A6),
    outlineVariant = Color(0xFF3A3159),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val FableTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun FableTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    incognito: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = when {
        incognito -> IncognitoScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    val view = LocalContext.current
    SideEffect {
        (view as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !dark && !incognito
        }
    }
    MaterialTheme(colorScheme = scheme, typography = FableTypography, content = content)
}
