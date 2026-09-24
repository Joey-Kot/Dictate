package com.joeykot.dictate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayConfigTest {
    @Test
    fun defaultsPreserveExistingOverlayAppearance() {
        val display = DisplayConfig()

        assertEquals(1f, display.buttonScale, 0f)
        assertEquals(1f, display.buttonOpacity, 0f)
        assertEquals(OverlayColorScheme.DEFAULT, display.colorScheme)
        assertEquals(OverlayPalette.DEFAULT, display.effectivePalette())
    }

    @Test
    fun customSchemeUsesAllThreeUserSelectedColors() {
        val palette = OverlayPalette(
            recordingColor = 0x123456,
            pausedColor = 0xABCDEF,
            processingColor = 0x654321,
        )

        assertEquals(
            palette,
            DisplayConfig(colorScheme = OverlayColorScheme.CUSTOM, customPalette = palette)
                .effectivePalette(),
        )
    }

    @Test
    fun presetSchemesResolveToTheirDefinedPalettes() {
        assertEquals(
            OverlayPalette.OCEAN,
            DisplayConfig(colorScheme = OverlayColorScheme.OCEAN).effectivePalette(),
        )
        assertEquals(
            OverlayPalette.SUNSET,
            DisplayConfig(colorScheme = OverlayColorScheme.SUNSET).effectivePalette(),
        )
        assertEquals(
            OverlayPalette.COLOR_BLIND,
            DisplayConfig(colorScheme = OverlayColorScheme.COLOR_BLIND).effectivePalette(),
        )
    }

    @Test
    fun invalidScaleOpacityAndColorsAreReported() {
        val errors = DisplayConfig(
            buttonScale = 2.01f,
            buttonOpacity = 0.29f,
            customPalette = OverlayPalette(recordingColor = 0x1_000000),
        ).validate()

        assertTrue(errors.any { it.contains("按钮大小") })
        assertTrue(errors.any { it.contains("按钮不透明度") })
        assertTrue(errors.any { it.contains("录制颜色") })
    }

    @Test
    fun normalizationClampsPersistedNumericValuesAndRestoresInvalidColors() {
        val normalized = DisplayConfig(
            buttonScale = 8f,
            buttonOpacity = 0.01f,
            customPalette = OverlayPalette(processingColor = -1),
        ).normalized()

        assertEquals(DisplayConfig.MAX_BUTTON_SCALE, normalized.buttonScale, 0f)
        assertEquals(DisplayConfig.MIN_BUTTON_OPACITY, normalized.buttonOpacity, 0f)
        assertEquals(OverlayPalette.DEFAULT_PROCESSING_COLOR, normalized.customPalette.processingColor)
    }
}
