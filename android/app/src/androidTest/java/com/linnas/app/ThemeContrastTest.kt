package com.linnas.app

import android.content.Context
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeContrastTest {
    @Test
    fun everyThemeMeetsSmallTextContrastOnItsSurfaces() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        ThemeStore.all.forEach { palette ->
            val themed = ContextThemeWrapper(base, palette.style)
            val ink = color(themed, R.attr.lInk)
            val surface = color(themed, R.attr.lSurface)
            val surfaceHigh = color(themed, R.attr.lSurfaceHigh)
            val text = color(themed, R.attr.lText)
            val textDim = color(themed, R.attr.lTextDim)
            val primary = color(themed, R.attr.lPrimary)
            val onPrimary = color(themed, R.attr.lOnPrimary)

            assertContrast("${palette.label} primary text / surface", text, surface)
            assertContrast("${palette.label} primary text / raised surface", text, surfaceHigh)
            assertContrast("${palette.label} secondary text / surface", textDim, surface)
            assertContrast("${palette.label} secondary text / raised surface", textDim, surfaceHigh)
            assertContrast("${palette.label} accent / surface", primary, surface)
            assertContrast("${palette.label} accent / raised surface", primary, surfaceHigh)
            assertContrast("${palette.label} button text / accent", onPrimary, primary)
            assertContrast("${palette.label} primary text / background", text, ink)
        }
    }

    private fun color(context: Context, attribute: Int): Int {
        val value = TypedValue()
        check(context.theme.resolveAttribute(attribute, value, true)) {
            "Theme did not define color attribute $attribute"
        }
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private fun assertContrast(label: String, foreground: Int, background: Int) {
        val ratio = ColorUtils.calculateContrast(foreground, background)
        assertTrue("$label contrast was ${"%.2f".format(ratio)}:1; expected at least 4.5:1", ratio >= 4.5)
    }
}
