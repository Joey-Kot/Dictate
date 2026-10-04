package com.joeykot.dictate.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMenuPlacementTest {
    @Test
    fun opensInwardsFromBothScreenEdges() {
        val viewport = OverlayMenuRect(8, 24, 344, 680)
        val left = placeOverlayMenu(OverlayMenuRect(8, 300, 64, 64), viewport, 270, 340)
        val right = placeOverlayMenu(OverlayMenuRect(288, 300, 64, 64), viewport, 270, 340)
        assertEquals(8, left.x)
        assertEquals(352, right.x + right.width)
    }

    @Test
    fun keepsLongMenusAboveKeyboardEvenWhenButtonIsBelowIt() {
        val viewport = OverlayMenuRect(8, 24, 344, 260)
        val result = placeOverlayMenu(OverlayMenuRect(300, 640, 64, 64), viewport, 270, 360)
        assertEquals(260, result.height)
        assertEquals(24, result.y)
        assertTrue(result.x >= viewport.x)
        assertTrue(result.x + result.width <= viewport.x + viewport.width)
    }

    @Test
    fun resizesToNarrowLandscapeViewportAndClampsRotatedPosition() {
        val viewport = OverlayMenuRect(40, 12, 180, 120)
        val result = placeOverlayMenu(OverlayMenuRect(800, -50, 64, 64), viewport, 270, 360)
        assertEquals(viewport, result)
    }
}
