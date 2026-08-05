package com.joeykot.dictate.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayBoundsTest {
    private val bounds = OverlayBounds(
        minX = 24,
        minY = 80,
        maxX = 920,
        maxY = 1_760,
    )

    @Test
    fun keepsPositionAlreadyInsideVisibleArea() {
        assertEquals(OverlayCoordinates(400, 900), bounds.clamp(400, 900))
    }

    @Test
    fun clipsPositionAtTopLeftInsets() {
        assertEquals(OverlayCoordinates(24, 80), bounds.clamp(-200, -300))
    }

    @Test
    fun clipsPositionAtBottomRightInsets() {
        assertEquals(OverlayCoordinates(920, 1_760), bounds.clamp(2_000, 3_000))
    }
}
