package org.signal.core.ui.compose.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import org.signal.core.ui.CoreUiDependencies
import org.signal.core.ui.compose.ProvideIncognitoKeyboard

private val typography = Typography().run {
  copy(
    headlineLarge = headlineLarge.copy(
      fontSize = 32.sp,
      lineHeight = 40.sp,
      letterSpacing = 0.sp
    ),
    headlineMedium = headlineMedium.copy(
      fontSize = 28.sp,
      lineHeight = 36.sp,
      letterSpacing = 0.sp
    ),
    titleLarge = titleLarge.copy(
      fontSize = 22.sp,
      lineHeight = 28.sp,
      letterSpacing = (-0.22).sp,
      fontWeight = FontWeight.SemiBold
    ),
    titleMedium = titleMedium.copy(
      fontSize = 17.sp,
      lineHeight = 22.sp,
      letterSpacing = 0.sp,
      fontFamily = FontFamily.SansSerif,
      fontStyle = FontStyle.Normal,
      fontWeight = FontWeight.SemiBold
    ),
    titleSmall = titleSmall.copy(
      fontSize = 16.sp,
      lineHeight = 22.sp,
      letterSpacing = 0.0125.sp
    ),
    bodyLarge = bodyLarge.copy(
      fontSize = 16.sp,
      lineHeight = 22.sp,
      letterSpacing = 0.0125.sp
    ),
    bodyMedium = bodyMedium.copy(
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.0107.sp
    ),
    bodySmall = bodySmall.copy(
      fontSize = 13.sp,
      lineHeight = 18.sp,
      letterSpacing = 0.0192.sp
    ),
    labelLarge = labelLarge.copy(
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.0107.sp
    ),
    labelMedium = labelMedium.copy(
      fontSize = 13.sp,
      lineHeight = 16.sp,
      letterSpacing = 0.0192.sp
    ),
    labelSmall = labelSmall.copy(
      fontSize = 11.sp,
      lineHeight = 14.sp,
      letterSpacing = 0.44.sp
    )
  )
}

// Asher palette (light): same hues on a cloud-white base. See brands/asher/design/tokens.json.
private val lightColorScheme = lightColorScheme(
  primary = Color(0xFF2E74AB),
  primaryContainer = Color(0xFFD6E7F5),
  secondary = Color(0xFF4A5D70),
  secondaryContainer = Color(0xFFE3ECF4),
  surface = Color(0xFFFFFFFF),
  surfaceContainerLow = Color(0xFFF4F7FA),
  surfaceContainerHighest = Color(0xFFEEF3F8),
  surfaceVariant = Color(0xFFEEF3F8),
  background = Color(0xFFF4F7FA),
  error = Color(0xFFC94A4A),
  errorContainer = Color(0xFFFBE3E3),
  onPrimary = Color(0xFFFFFFFF),
  onPrimaryContainer = Color(0xFF0B2A45),
  onSecondary = Color(0xFFFFFFFF),
  onSecondaryContainer = Color(0xFF0B1017),
  onSurface = Color(0xFF0B1017),
  onSurfaceVariant = Color(0xFF4A5D70),
  onBackground = Color(0xFF0B1017),
  outline = Color(0xFF8194A6)
)

private val lightExtendedColors = ExtendedColors(
  neutralSurface = Color(0x99FFFFFF),
  neutralFill = Color(0x1A000000),
  colorOnCustom = Color(0xFFFFFFFF),
  colorOnCustomVariant = Color(0xB3FFFFFF),
  colorSurface1 = Color(0xFFF4F7FA),
  colorSurface2 = Color(0xFFEEF3F8),
  colorSurface3 = Color(0xFFE8EFF6),
  colorSurface4 = Color(0xFFE3EBF3),
  colorSurface5 = Color(0xFFDEE7F0),
  colorSurfaceVariantFill = Color(0xCCFFFFFF),
  colorTransparent1 = Color(0x14FFFFFF),
  colorTransparent2 = Color(0x29FFFFFF),
  colorTransparent3 = Color(0x8FFFFFFF),
  colorTransparent4 = Color(0xB8FFFFFF),
  colorTransparent5 = Color(0xF5FFFFFF),
  colorNeutral = Color(0xFFFFFFFF),
  colorNeutralVariant = Color(0xB8FFFFFF),
  colorTransparentInverse1 = Color(0x0A000000),
  colorTransparentInverse2 = Color(0x14000000),
  colorTransparentInverse3 = Color(0x66000000),
  colorTransparentInverse4 = Color(0xB8000000),
  colorTransparentInverse5 = Color(0xE0000000),
  colorNeutralInverse = Color(0xFF0B1017),
  colorNeutralVariantInverse = Color(0xFF4A5D70),
  colorWarning = Color(0x1FB48A28),
  colorOnWarning = Color(0xFF8A6512),
  colorAlert = Color(0xFFC94A4A),
  colorAlertDisabled = Color(0x80C94A4A)
)

private val darkExtendedColors = ExtendedColors(
  neutralSurface = Color(0x14FFFFFF),
  neutralFill = Color(0x33FFFFFF),
  colorOnCustom = Color(0xFFFFFFFF),
  colorOnCustomVariant = Color(0x18FFFFFF),
  colorSurface1 = Color(0xFF111823),
  colorSurface2 = Color(0xFF131C28),
  colorSurface3 = Color(0xFF16202D),
  colorSurface4 = Color(0xFF1B2533),
  colorSurface5 = Color(0xFF1E2A3A),
  colorSurfaceVariantFill = Color(0x33FFFFFF),
  colorTransparent1 = Color(0x0AFFFFFF),
  colorTransparent2 = Color(0x1FFFFFFF),
  colorTransparent3 = Color(0x29FFFFFF),
  colorTransparent4 = Color(0x7AFFFFFF),
  colorTransparent5 = Color(0xB8FFFFFF),
  colorNeutral = Color(0xFF05070B),
  colorNeutralVariant = Color(0xFF5F7387),
  colorTransparentInverse1 = Color(0x0A000000),
  colorTransparentInverse2 = Color(0x14000000),
  colorTransparentInverse3 = Color(0x29000000),
  colorTransparentInverse4 = Color(0xB8000000),
  colorTransparentInverse5 = Color(0xF5000000),
  colorNeutralInverse = Color(0xE0FFFFFF),
  colorNeutralVariantInverse = Color(0xA3FFFFFF),
  colorWarning = Color(0x1FE0B45A),
  colorOnWarning = Color(0xFFE0B45A),
  colorAlert = Color(0xFFE06B6B),
  colorAlertDisabled = Color(0x80E06B6B)
)

// Asher palette (dark, the default): void surfaces, one atmosphere-blue accent. See brands/asher/design/tokens.json.
private val darkColorScheme = darkColorScheme(
  primary = Color(0xFF3D8FCF),
  primaryContainer = Color(0xFF1E466B),
  secondary = Color(0xFF9BB0C3),
  secondaryContainer = Color(0xFF16202D),
  surface = Color(0xFF0B1017),
  surfaceContainerLow = Color(0xFF111823),
  surfaceContainerHighest = Color(0xFF1B2533),
  surfaceVariant = Color(0xFF111823),
  background = Color(0xFF05070B),
  error = Color(0xFFE06B6B),
  errorContainer = Color(0xFF5C1F24),
  onPrimary = Color(0xFFF4F9FD),
  onPrimaryContainer = Color(0xFFCFE6F7),
  onSecondary = Color(0xFF0B1017),
  onSecondaryContainer = Color(0xFFE8EEF4),
  onSurface = Color(0xFFE8EEF4),
  onSurfaceVariant = Color(0xFF9BB0C3),
  onBackground = Color(0xFFE8EEF4),
  outline = Color(0xFF27344A)
)

private val lightSnackbarColors = SnackbarColors(
  color = darkColorScheme.surface,
  contentColor = darkColorScheme.onSurface,
  actionColor = darkColorScheme.primary,
  actionContentColor = darkColorScheme.primary,
  dismissActionContentColor = darkColorScheme.onSurface
)

private val darkSnackbarColors = SnackbarColors(
  color = darkColorScheme.surfaceVariant,
  contentColor = darkColorScheme.onSurfaceVariant,
  actionColor = darkColorScheme.primary,
  actionContentColor = darkColorScheme.primary,
  dismissActionContentColor = darkColorScheme.onSurfaceVariant
)

@Composable
fun SignalTheme(
  isDarkMode: Boolean = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
  incognitoKeyboardEnabled: Boolean = CoreUiDependencies.isIncognitoKeyboardEnabled,
  content: @Composable () -> Unit
) {
  val extendedColors = if (isDarkMode) darkExtendedColors else lightExtendedColors
  val snackbarColors = if (isDarkMode) darkSnackbarColors else lightSnackbarColors

  ProvideIncognitoKeyboard(enabled = incognitoKeyboardEnabled) {
    CompositionLocalProvider(LocalExtendedColors provides extendedColors, LocalSnackbarColors provides snackbarColors) {
      MaterialTheme(
        colorScheme = if (isDarkMode) darkColorScheme else lightColorScheme,
        typography = typography,
        content = content
      )
    }
  }
}

/**
 * Applies the light color scheme to [content] regardless of the ambient theme, leaving typography and shapes untouched.
 */
@Composable
fun ForceLightColors(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalExtendedColors provides lightExtendedColors) {
    MaterialTheme(
      colorScheme = lightColorScheme,
      content = content
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun TypographyPreview() {
  SignalTheme(
    isDarkMode = false,
    incognitoKeyboardEnabled = false
  ) {
    Column {
      Text(
        text = "Headline Small",
        style = MaterialTheme.typography.headlineLarge
      )
      Text(
        text = "Headline Small",
        style = MaterialTheme.typography.headlineMedium
      )
      Text(
        text = "Headline Small",
        style = MaterialTheme.typography.headlineSmall
      )
      Text(
        text = "Title Large",
        style = MaterialTheme.typography.titleLarge
      )
      Text(
        text = "Title Medium",
        style = MaterialTheme.typography.titleMedium
      )
      Text(
        text = "Title Small",
        style = MaterialTheme.typography.titleSmall
      )
      Text(
        text = "Body Large",
        style = MaterialTheme.typography.bodyLarge
      )
      Text(
        text = "Body Medium",
        style = MaterialTheme.typography.bodyMedium
      )
      Text(
        text = "Body Small",
        style = MaterialTheme.typography.bodySmall
      )
      Text(
        text = "Label Large",
        style = MaterialTheme.typography.labelLarge
      )
      Text(
        text = "Label Medium",
        style = MaterialTheme.typography.labelMedium
      )
      Text(
        text = "Label Small",
        style = MaterialTheme.typography.labelSmall
      )
    }
  }
}

object SignalTheme {
  val colors: ExtendedColors
    @Composable
    get() = LocalExtendedColors.current
}
