package com.yoshiaki21.FoldWallpaper.wallpaper

import android.graphics.Bitmap
import com.yoshiaki21.FoldWallpaper.DisplaySide

/**
 * 内側／外側それぞれ最新の1枚だけを保持するビットマップキャッシュ。
 *
 * 端末を開閉するたびに Engine は破棄・再生成されるが、プロセスは生き続ける。
 * 直前に描いた画像をプロセス側に残しておくことで、2回目以降の開閉は
 * デコードを挟まずに描画でき、切替時のちらつきを抑えられる。
 *
 * 保持するのは内側1枚・外側1枚の最大2枚だけで、それ以上は増えない。
 * メモリ逼迫時の破棄は OS に任せる（API 35 以降 onTrimMemory は
 * TRIM_MEMORY_UI_HIDDEN しか通知されず、壁紙サービスには届かないため）。
 *
 * 追い出した Bitmap は [Bitmap.recycle] せずに参照を捨てるだけにする。
 * 描画中のビットマップを recycle すると即クラッシュするため、GC に任せる。
 */
object WallpaperImageCache {

    private data class Key(val width: Int, val height: Int, val stamp: Long)

    private val entries = HashMap<DisplaySide, Pair<Key, Bitmap>>(2)

    @Synchronized
    fun get(side: DisplaySide, width: Int, height: Int, stamp: Long): Bitmap? {
        val (key, bitmap) = entries[side] ?: return null
        if (key != Key(width, height, stamp) || bitmap.isRecycled) return null
        return bitmap
    }

    @Synchronized
    fun put(side: DisplaySide, width: Int, height: Int, stamp: Long, bitmap: Bitmap) {
        entries[side] = Key(width, height, stamp) to bitmap
    }

    @Synchronized
    fun remove(side: DisplaySide) {
        entries.remove(side)
    }
}
