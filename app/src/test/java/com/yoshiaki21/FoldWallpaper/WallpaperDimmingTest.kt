package com.yoshiaki21.FoldWallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperDimmingTest {

    @Test
    fun `values outside the allowed range are clamped`() {
        assertEquals(WallpaperDimming.MAX_PERCENT, WallpaperDimming.clamp(100))
        assertEquals(WallpaperDimming.MAX_PERCENT, WallpaperDimming.clamp(Int.MAX_VALUE))
        assertEquals(WallpaperDimming.MIN_PERCENT, WallpaperDimming.clamp(-1))
        assertEquals(WallpaperDimming.MIN_PERCENT, WallpaperDimming.clamp(Int.MIN_VALUE))
    }

    @Test
    fun `values inside the range are kept`() {
        assertEquals(0, WallpaperDimming.clamp(0))
        assertEquals(35, WallpaperDimming.clamp(35))
        assertEquals(WallpaperDimming.MAX_PERCENT, WallpaperDimming.clamp(WallpaperDimming.MAX_PERCENT))
    }

    @Test
    fun `zero percent draws no overlay`() {
        assertEquals(0, WallpaperDimming.alpha(0))
        assertEquals(0f, WallpaperDimming.alphaFraction(0), 0.0001f)
    }

    @Test
    fun `alpha never reaches fully opaque black`() {
        // 上限が 100% 未満である限り、壁紙が完全に黒くなることはない。
        assertTrue(WallpaperDimming.MAX_PERCENT < 100)
        assertTrue(WallpaperDimming.alpha(WallpaperDimming.MAX_PERCENT) < 255)
        assertTrue(WallpaperDimming.alphaFraction(WallpaperDimming.MAX_PERCENT) < 1f)
    }

    @Test
    fun `alpha increases with the percentage`() {
        val alphas = (WallpaperDimming.MIN_PERCENT..WallpaperDimming.MAX_PERCENT)
            .map { WallpaperDimming.alpha(it) }
        assertEquals(alphas.sorted(), alphas)
        assertTrue(alphas.last() > alphas.first())
    }

    @Test
    fun `alpha stays within the 8 bit range`() {
        listOf(-50, 0, 40, WallpaperDimming.MAX_PERCENT, 200).forEach { percent ->
            val alpha = WallpaperDimming.alpha(percent)
            assertTrue("percent=$percent alpha=$alpha", alpha in 0..255)
        }
    }

    @Test
    fun `the default keeps the wallpaper at its original brightness`() {
        assertEquals(WallpaperDimming.MIN_PERCENT, WallpaperDimming.DEFAULT_PERCENT)
    }
}
