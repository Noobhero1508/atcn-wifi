package io.github.noobhero1508.atcnwifi.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E9FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF4F616F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E5F5),
    onSecondaryContainer = Color(0xFF0B1D29),
    tertiary = Color(0xFF006B59),
    onTertiary = Color.White,
    error = ErrorRed,
    background = LightBackground,
    onBackground = Color(0xFF191C20),
    surface = LightSurface,
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE3EA),
    onSurfaceVariant = Color(0xFF43474E),
    outline = LightOutline,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF00497F),
    onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = Color(0xFFB7C9D8),
    onSecondary = Color(0xFF22323E),
    secondaryContainer = Color(0xFF384956),
    onSecondaryContainer = Color(0xFFD2E5F5),
    tertiary = Color(0xFF55DBC0),
    onTertiary = Color(0xFF00382E),
    error = ErrorRedDark,
    background = DarkBackground,
    onBackground = Color(0xFFE1E2E8),
    surface = DarkSurface,
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = DarkOutline,
)

@Composable
fun AtcnWifiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep the ATCN identity by default. This can be enabled later if users request Material You.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AtcnTypography,
        content = content,
    )
}
