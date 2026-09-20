package com.yoshiaki21.FoldWallpaper

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperProfileTest {

    @Test
    fun `only an audible ringer counts as private`() {
        assertEquals(
            WallpaperProfile.PRIVATE,
            ProfileSelection.fromRingerMode(AudioManager.RINGER_MODE_NORMAL),
        )
    }

    @Test
    fun `silenced ringers fall back to the standard profile`() {
        assertEquals(
            WallpaperProfile.STANDARD,
            ProfileSelection.fromRingerMode(AudioManager.RINGER_MODE_VIBRATE),
        )
        assertEquals(
            WallpaperProfile.STANDARD,
            ProfileSelection.fromRingerMode(AudioManager.RINGER_MODE_SILENT),
        )
    }

    @Test
    fun `the mapping matches the documented platform constants`() {
        // 定数の値が変わると判定が逆転しかねないため、リテラルでも固定しておく。
        assertEquals(2, AudioManager.RINGER_MODE_NORMAL)
        assertEquals(1, AudioManager.RINGER_MODE_VIBRATE)
        assertEquals(0, AudioManager.RINGER_MODE_SILENT)

        assertEquals(WallpaperProfile.PRIVATE, ProfileSelection.fromRingerMode(2))
        assertEquals(WallpaperProfile.STANDARD, ProfileSelection.fromRingerMode(1))
        assertEquals(WallpaperProfile.STANDARD, ProfileSelection.fromRingerMode(0))
    }

    @Test
    fun `unreadable or unexpected values never yield private`() {
        // 誤判定の被害は非対称で、他人の目がある場でプライベートが出るのが最悪のケース。
        // 判定できない場合は必ず標準へ倒れること。
        listOf(
            ProfileSelection.UNKNOWN_RINGER_MODE,
            -99,
            3,
            Int.MAX_VALUE,
            Int.MIN_VALUE,
        ).forEach { ringerMode ->
            assertEquals(
                "ringerMode=$ringerMode",
                WallpaperProfile.STANDARD,
                ProfileSelection.fromRingerMode(ringerMode),
            )
        }
    }

    @Test
    fun `the fallback is the standard profile`() {
        assertEquals(WallpaperProfile.STANDARD, ProfileSelection.FALLBACK)
    }

    @Test
    fun `the unknown marker cannot collide with a real ringer mode`() {
        listOf(
            AudioManager.RINGER_MODE_NORMAL,
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_SILENT,
        ).forEach { realMode ->
            assert(ProfileSelection.UNKNOWN_RINGER_MODE != realMode)
        }
    }
}
