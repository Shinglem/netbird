package dev.shinglem.netbird.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 可选的 M3 配色。
 *
 * [MATERIAL] 是 M3 基线色板；其余几套每套只给 9 个关键色，`on*` 与各级 surface 容器
 * 由 [Palette.scheme] 按明度派生 —— 否则每加一套配色就要手工维护二十多个互相制约的十六进制值。
 */
enum class ThemeStyle(val id: String, val label: String) {
    MATERIAL("material", "默认"),
    OCEAN("ocean", "海洋"),
    FOREST("forest", "森林"),
    SUNSET("sunset", "落日"),
    ROSE("rose", "蔷薇"),
    SLATE("slate", "石板"),
    HIGH_CONTRAST("contrast", "高对比"),
    ;

    companion object {
        fun fromId(id: String?): ThemeStyle = entries.firstOrNull { it.id == id } ?: MATERIAL
    }
}

/** 明暗模式。默认跟随系统，用户也可以钉死一种。 */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** 设置页画色板预览需要的那两个色。 */
data class ThemePreview(val primary: Color, val surface: Color)

@Composable
fun NetBirdTheme(
    style: ThemeStyle,
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = style.colorScheme(dark), content = content)
}

fun ThemeStyle.colorScheme(dark: Boolean): ColorScheme = when (this) {
    ThemeStyle.MATERIAL -> if (dark) darkColorScheme() else lightColorScheme()
    ThemeStyle.OCEAN -> pick(oceanLight, oceanDark, dark)
    ThemeStyle.FOREST -> pick(forestLight, forestDark, dark)
    ThemeStyle.SUNSET -> pick(sunsetLight, sunsetDark, dark)
    ThemeStyle.ROSE -> pick(roseLight, roseDark, dark)
    ThemeStyle.SLATE -> pick(slateLight, slateDark, dark)
    ThemeStyle.HIGH_CONTRAST -> pick(highContrastLight, highContrastDark, dark)
}

private fun pick(light: Palette, dark: Palette, useDark: Boolean): ColorScheme =
    (if (useDark) dark else light).scheme(useDark)

fun ThemeStyle.preview(dark: Boolean): ThemePreview {
    val scheme = colorScheme(dark)
    return ThemePreview(primary = scheme.primary, surface = scheme.surface)
}

/**
 * 一套配色的关键色。
 *
 * 明暗两套各自给 surface 值：深色不是把浅色调暗 —— 那样明度压下来的同时饱和度还在，
 * 整屏会发闷。每套配色的深色 surface 都是单独挑的低饱和深色。
 */
private class Palette(
    val primary: Long,
    val primaryContainer: Long,
    val secondary: Long,
    val secondaryContainer: Long,
    val tertiary: Long,
    val tertiaryContainer: Long,
    val surface: Long,
    val surfaceContainer: Long,
    val outline: Long,
)

private fun Palette.scheme(dark: Boolean): ColorScheme {
    val surfaceColor = Color(surface)
    val container = Color(surfaceContainer)
    val onSurface = readableOn(surfaceColor)

    if (dark) {
        return darkColorScheme(
            primary = Color(primary),
            onPrimary = readableOn(Color(primary)),
            primaryContainer = Color(primaryContainer),
            onPrimaryContainer = readableOn(Color(primaryContainer)),
            inversePrimary = Color(primaryContainer),
            secondary = Color(secondary),
            onSecondary = readableOn(Color(secondary)),
            secondaryContainer = Color(secondaryContainer),
            onSecondaryContainer = readableOn(Color(secondaryContainer)),
            tertiary = Color(tertiary),
            onTertiary = readableOn(Color(tertiary)),
            tertiaryContainer = Color(tertiaryContainer),
            onTertiaryContainer = readableOn(Color(tertiaryContainer)),
            background = surfaceColor,
            onBackground = onSurface,
            surface = surfaceColor,
            onSurface = onSurface,
            surfaceVariant = container,
            onSurfaceVariant = mutedOn(container),
            surfaceDim = lerp(surfaceColor, Color.Black, 0.18f),
            surfaceBright = lerp(surfaceColor, Color.White, 0.08f),
            surfaceContainerLowest = lerp(surfaceColor, Color.Black, 0.10f),
            surfaceContainerLow = lerp(surfaceColor, Color.White, 0.03f),
            surfaceContainer = container,
            surfaceContainerHigh = lerp(container, Color.White, 0.06f),
            surfaceContainerHighest = lerp(container, Color.White, 0.12f),
            inverseSurface = onSurface,
            inverseOnSurface = surfaceColor,
            outline = Color(outline),
            outlineVariant = lerp(container, Color(outline), 0.45f),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )
    }

    return lightColorScheme(
        primary = Color(primary),
        onPrimary = readableOn(Color(primary)),
        primaryContainer = Color(primaryContainer),
        onPrimaryContainer = readableOn(Color(primaryContainer)),
        inversePrimary = Color(primary),
        secondary = Color(secondary),
        onSecondary = readableOn(Color(secondary)),
        secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = readableOn(Color(secondaryContainer)),
        tertiary = Color(tertiary),
        onTertiary = readableOn(Color(tertiary)),
        tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = readableOn(Color(tertiaryContainer)),
        background = surfaceColor,
        onBackground = onSurface,
        surface = surfaceColor,
        onSurface = onSurface,
        surfaceVariant = container,
        onSurfaceVariant = mutedOn(container),
        surfaceDim = lerp(surfaceColor, Color.Black, 0.08f),
        surfaceBright = lerp(surfaceColor, Color.White, 0.40f),
        surfaceContainerLowest = lerp(surfaceColor, Color.White, 0.60f),
        surfaceContainerLow = lerp(surfaceColor, Color.White, 0.35f),
        surfaceContainer = container,
        surfaceContainerHigh = lerp(container, Color.Black, 0.04f),
        surfaceContainerHighest = lerp(container, Color.Black, 0.08f),
        inverseSurface = onSurface,
        inverseOnSurface = surfaceColor,
        outline = Color(outline),
        outlineVariant = lerp(container, Color(outline), 0.45f),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )
}

private fun readableOn(background: Color): Color =
    if (background.luminance() > 0.45f) Color(0xFF1A1C19) else Color(0xFFFDFDF7)

private fun mutedOn(background: Color): Color = lerp(readableOn(background), background, 0.28f)

// 手工调色：主色比 M3 自动生成的结果略低饱和，长时间盯着不刺眼。
// 明暗两套是各自独立的色板 —— 深色主题的主色必须是浅色调，不能拿浅色主题的 primary 直接用。
private val oceanLight = Palette(
    primary = 0xFF00639B, primaryContainer = 0xFFCDE5FF,
    secondary = 0xFF50606E, secondaryContainer = 0xFFD4E4F6,
    tertiary = 0xFF00696E, tertiaryContainer = 0xFF9CF1F7,
    surface = 0xFFF8F9FF, surfaceContainer = 0xFFECEFF5,
    outline = 0xFF74777F,
)

private val oceanDark = Palette(
    primary = 0xFF95CCFF, primaryContainer = 0xFF004A77,
    secondary = 0xFFB8C8DA, secondaryContainer = 0xFF384856,
    tertiary = 0xFF80D4DA, tertiaryContainer = 0xFF004F53,
    surface = 0xFF101418, surfaceContainer = 0xFF1C2024,
    outline = 0xFF8E9099,
)

private val forestLight = Palette(
    primary = 0xFF2E6B4F, primaryContainer = 0xFFB1F1CB,
    secondary = 0xFF4F6355, secondaryContainer = 0xFFD1E8D6,
    tertiary = 0xFF3A6470, tertiaryContainer = 0xFFBEEAF8,
    surface = 0xFFF7FBF5, surfaceContainer = 0xFFEAF0EA,
    outline = 0xFF737973,
)

private val forestDark = Palette(
    primary = 0xFF95D5AE, primaryContainer = 0xFF0F5137,
    secondary = 0xFFB6CCB9, secondaryContainer = 0xFF374B3E,
    tertiary = 0xFFA2CEDC, tertiaryContainer = 0xFF1F4C57,
    surface = 0xFF0F1511, surfaceContainer = 0xFF1B211C,
    outline = 0xFF8A938B,
)

private val sunsetLight = Palette(
    primary = 0xFF9A4600, primaryContainer = 0xFFFFDBC5,
    secondary = 0xFF745944, secondaryContainer = 0xFFF2DFCC,
    tertiary = 0xFF5C6238, tertiaryContainer = 0xFFE1E7B6,
    surface = 0xFFFFF8F4, surfaceContainer = 0xFFF1E8E2,
    outline = 0xFF85736A,
)

private val sunsetDark = Palette(
    primary = 0xFFFFB77C, primaryContainer = 0xFF7A3300,
    secondary = 0xFFD8C0A8, secondaryContainer = 0xFF57432F,
    tertiary = 0xFFC5CB9C, tertiaryContainer = 0xFF444A20,
    surface = 0xFF17120E, surfaceContainer = 0xFF241E19,
    outline = 0xFF9F8D83,
)

private val roseLight = Palette(
    primary = 0xFF9C4146, primaryContainer = 0xFFFFDADA,
    secondary = 0xFF775656, secondaryContainer = 0xFFF0DEDD,
    tertiary = 0xFF725B2E, tertiaryContainer = 0xFFF5E0B3,
    surface = 0xFFFFF8F7, surfaceContainer = 0xFFF7E9E8,
    outline = 0xFF877271,
)

private val roseDark = Palette(
    primary = 0xFFFFB3B3, primaryContainer = 0xFF7C2B31,
    secondary = 0xFFE6BEBE, secondaryContainer = 0xFF5D3F3F,
    tertiary = 0xFFE0C58F, tertiaryContainer = 0xFF59441A,
    surface = 0xFF191112, surfaceContainer = 0xFF261C1D,
    outline = 0xFFA08C8B,
)

private val slateLight = Palette(
    primary = 0xFF455E6E, primaryContainer = 0xFFC7E6F8,
    secondary = 0xFF556069, secondaryContainer = 0xFFD8E4EE,
    tertiary = 0xFF436460, tertiaryContainer = 0xFFC6E9E4,
    surface = 0xFFF8FAFB, surfaceContainer = 0xFFEBEEF0,
    outline = 0xFF72787C,
)

private val slateDark = Palette(
    primary = 0xFFA9CFE3, primaryContainer = 0xFF27485A,
    secondary = 0xFFBCC9D3, secondaryContainer = 0xFF3B4852,
    tertiary = 0xFFAACDC8, tertiaryContainer = 0xFF2B4B47,
    surface = 0xFF101416, surfaceContainer = 0xFF1B2022,
    outline = 0xFF8C9296,
)

private val highContrastLight = Palette(
    primary = 0xFF000000, primaryContainer = 0xFFE2E2E2,
    secondary = 0xFF3F3F3F, secondaryContainer = 0xFFE8E8E8,
    tertiary = 0xFF004C99, tertiaryContainer = 0xFFD6E3FF,
    surface = 0xFFFFFFFF, surfaceContainer = 0xFFF2F2F2,
    outline = 0xFF202020,
)

private val highContrastDark = Palette(
    primary = 0xFFFFFFFF, primaryContainer = 0xFF3A3A3A,
    secondary = 0xFFD0D0D0, secondaryContainer = 0xFF333333,
    tertiary = 0xFFA8C7FF, tertiaryContainer = 0xFF00325B,
    surface = 0xFF0A0A0A, surfaceContainer = 0xFF1C1C1C,
    outline = 0xFFB0B0B0,
)
