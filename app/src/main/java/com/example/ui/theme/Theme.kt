package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val ForgeDarkColorScheme = darkColorScheme(
    primary = ForgeIndigoBright,
    onPrimary = Color(0xFF0A0D18),
    primaryContainer = Color(0xFF1E2247),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = ForgeCyanSoft,
    onSecondary = Color(0xFF042028),
    secondaryContainer = Color(0xFF0D2E38),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = ForgeEmerald,
    onTertiary = Color(0xFF022C22),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceMuted,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceElevated,
    outline = DarkOutline,
    outlineVariant = DarkOutlineSubtle,
    error = ForgeRose,
    onError = Color.White,
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFFE4E6)
)

private val ForgeLightColorScheme = lightColorScheme(
    primary = ForgeIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = ForgeIndigoDeep,
    secondary = ForgeCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = ForgeEmerald,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceMuted,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurfaceElevated,
    outline = LightOutline,
    outlineVariant = LightOutlineSubtle,
    error = ForgeRose,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337)
)

val ForgeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun ForgeAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ForgeDarkColorScheme
        else -> ForgeLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ForgeTypography,
        shapes = ForgeShapes,
        content = content
    )
}
