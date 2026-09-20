package com.yoshiaki21.FoldWallpaper

import com.yoshiaki21.FoldWallpaper.wallpaper.WallpaperRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperRendererTest {

    @Test
    fun `no downsampling when the source barely covers the target`() {
        assertEquals(1, WallpaperRenderer.sampleSizeFor(2152, 2076, 2152, 2076))
        assertEquals(1, WallpaperRenderer.sampleSizeFor(1080, 2424, 1080, 2424))
    }

    @Test
    fun `source is never sampled below the target size`() {
        val sourceWidth = 8000
        val sourceHeight = 6000
        val targetWidth = 1080
        val targetHeight = 2424

        val sampleSize = WallpaperRenderer.sampleSizeFor(
            sourceWidth, sourceHeight, targetWidth, targetHeight,
        )

        assertTrue(sourceWidth / sampleSize >= targetWidth)
        assertTrue(sourceHeight / sampleSize >= targetHeight)
    }

    @Test
    fun `large source is downsampled by a power of two`() {
        val sampleSize = WallpaperRenderer.sampleSizeFor(8000, 8000, 1000, 1000)
        assertEquals(8, sampleSize)
    }

    @Test
    fun `invalid sizes fall back to no downsampling`() {
        assertEquals(1, WallpaperRenderer.sampleSizeFor(0, 100, 100, 100))
        assertEquals(1, WallpaperRenderer.sampleSizeFor(100, 100, 0, 100))
        assertEquals(1, WallpaperRenderer.sampleSizeFor(-1, -1, -1, -1))
    }
}
