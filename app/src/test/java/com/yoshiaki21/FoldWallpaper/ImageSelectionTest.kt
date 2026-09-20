package com.yoshiaki21.FoldWallpaper

import com.yoshiaki21.FoldWallpaper.wallpaper.ImageSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageSelectionTest {

    @Test
    fun `empty folder yields nothing`() {
        assertNull(ImageSelection.pickNext(emptyList(), current = null))
        assertNull(ImageSelection.pickNext(emptyList(), current = "a"))
    }

    @Test
    fun `single image folder keeps showing that image`() {
        assertEquals("a", ImageSelection.pickNext(listOf("a"), current = null))
        assertEquals("a", ImageSelection.pickNext(listOf("a"), current = "a"))
    }

    @Test
    fun `the current image is never picked again when alternatives exist`() {
        val candidates = listOf("a", "b", "c")
        repeat(200) { seed ->
            val picked = ImageSelection.pickNext(candidates, current = "b", random = Random(seed))
            assertNotEquals("b", picked)
            assertTrue(picked in candidates)
        }
    }

    @Test
    fun `every alternative is reachable`() {
        val candidates = listOf("a", "b", "c", "d")
        val picked = (0 until 200)
            .mapNotNull { ImageSelection.pickNext(candidates, current = "a", random = Random(it)) }
            .toSet()
        assertEquals(setOf("b", "c", "d"), picked)
    }

    @Test
    fun `a current image that is no longer in the folder is ignored`() {
        val candidates = listOf("a", "b")
        val picked = ImageSelection.pickNext(candidates, current = "deleted", random = Random(0))
        assertTrue(picked in candidates)
    }
}
