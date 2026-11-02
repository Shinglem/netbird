package dev.shinglem.netbird.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeTest {

    @Test
    fun everyStyleHasUniqueIdAndLabel() {
        val ids = ThemeStyle.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "主题 id 必须唯一")
        ThemeStyle.entries.forEach { style ->
            assertTrue(style.label.isNotBlank(), "${style.id} 缺少名称")
        }
        assertTrue(ThemeStyle.entries.size >= 6, "至少要提供 6 套配色")
    }

    @Test
    fun unknownIdsFallBackToDefaults() {
        assertEquals(ThemeStyle.MATERIAL, ThemeStyle.fromId(null))
        assertEquals(ThemeStyle.MATERIAL, ThemeStyle.fromId("nope"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromId("dark"))
    }

    @Test
    fun foregroundsStayReadableOnEverySurface() {
        ThemeStyle.entries.forEach { style ->
            listOf(false, true).forEach { dark ->
                val scheme = style.colorScheme(dark)
                val mode = if (dark) "dark" else "light"
                assertReadable(style.id, mode, "onSurface/surface", scheme.onSurface, scheme.surface)
                assertReadable(style.id, mode, "onPrimary/primary", scheme.onPrimary, scheme.primary)
                assertReadable(
                    style.id,
                    mode,
                    "onPrimaryContainer/primaryContainer",
                    scheme.onPrimaryContainer,
                    scheme.primaryContainer,
                )
                assertReadable(
                    style.id,
                    mode,
                    "onSurfaceVariant/surfaceVariant",
                    scheme.onSurfaceVariant,
                    scheme.surfaceVariant,
                    minimum = 3.0,
                )
            }
        }
    }

    @Test
    fun darkAndLightSurfacesDiffer() {
        ThemeStyle.entries.forEach { style ->
            val light = style.colorScheme(dark = false).surface.luminance()
            val dark = style.colorScheme(dark = true).surface.luminance()
            assertTrue(
                light > dark + 0.3f,
                "${style.id} 的明暗 surface 太接近（light=$light, dark=$dark）",
            )
        }
    }

    private fun assertReadable(
        style: String,
        mode: String,
        label: String,
        foreground: Color,
        background: Color,
        minimum: Double = 4.5,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(ratio >= minimum, "$style/$mode 的 $label 对比度只有 $ratio，要求 ≥ $minimum")
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        val high = max(a, b)
        val low = min(a, b)
        return (high + 0.05) / (low + 0.05)
    }
}
