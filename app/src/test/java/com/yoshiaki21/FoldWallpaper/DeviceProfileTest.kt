package com.yoshiaki21.FoldWallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun `aspect ratio is orientation independent`() {
        assertEquals(
            DeviceProfile.aspectRatioOf(1080, 2424),
            DeviceProfile.aspectRatioOf(2424, 1080),
            0.0001f,
        )
    }

    @Test
    fun `invalid size falls back instead of crashing`() {
        assertEquals(0f, DeviceProfile.aspectRatioOf(0, 1080), 0.0001f)
        assertEquals(DeviceProfile.FALLBACK_SIDE, DeviceProfile.classify(0, 0))
        assertEquals(DeviceProfile.FALLBACK_SIDE, DeviceProfile.classify(-1, 100))
    }

    @Test
    fun `measured Pixel 11 Pro Fold sizes are classified correctly`() {
        // 実機実測値。内側 1.037 / 外側 2.169
        assertEquals(DisplaySide.INNER, DeviceProfile.classify(2076, 2152))
        assertEquals(DisplaySide.OUTER, DeviceProfile.classify(1080, 2342))

        assertTrue(DeviceProfile.isWithinKnownRange(2076, 2152))
        assertTrue(DeviceProfile.isWithinKnownRange(1080, 2342))
    }

    @Test
    fun `near square displays are classified as inner`() {
        // Pixel Fold 2208x1840 = 1.20 / Pixel 9 Pro Fold 2152x2076 = 1.04
        assertEquals(DisplaySide.INNER, DeviceProfile.classify(1840, 2208))
        assertEquals(DisplaySide.INNER, DeviceProfile.classify(2076, 2152))
    }

    @Test
    fun `tall displays are classified as outer`() {
        // Pixel Fold 2092x1080 = 1.94 / Pixel 9 Pro Fold 2424x1080 = 2.24
        assertEquals(DisplaySide.OUTER, DeviceProfile.classify(1080, 2092))
        assertEquals(DisplaySide.OUTER, DeviceProfile.classify(1080, 2424))
    }

    @Test
    fun `sizes between the known ranges still resolve via the threshold`() {
        val belowThreshold = 1000 to (1000 * 1.50f).toInt()
        val aboveThreshold = 1000 to (1000 * 1.60f).toInt()

        assertFalse(DeviceProfile.isWithinKnownRange(belowThreshold.first, belowThreshold.second))
        assertFalse(DeviceProfile.isWithinKnownRange(aboveThreshold.first, aboveThreshold.second))

        assertEquals(
            DisplaySide.INNER,
            DeviceProfile.classify(belowThreshold.first, belowThreshold.second),
        )
        assertEquals(
            DisplaySide.OUTER,
            DeviceProfile.classify(aboveThreshold.first, aboveThreshold.second),
        )
    }

    @Test
    fun `known ranges do not overlap and sit on opposite sides of the threshold`() {
        assertTrue(
            DeviceProfile.INNER_ASPECT_RATIO_RANGE.endInclusive <
                DeviceProfile.OUTER_ASPECT_RATIO_RANGE.start,
        )
        assertTrue(
            DeviceProfile.INNER_ASPECT_RATIO_RANGE.endInclusive <
                DeviceProfile.CLASSIFICATION_THRESHOLD,
        )
        assertTrue(
            DeviceProfile.CLASSIFICATION_THRESHOLD <
                DeviceProfile.OUTER_ASPECT_RATIO_RANGE.start,
        )
    }

}
