package com.yoshiaki21.FoldWallpaper.wallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.yoshiaki21.FoldWallpaper.DeviceProfile
import com.yoshiaki21.FoldWallpaper.DisplaySide
import com.yoshiaki21.FoldWallpaper.data.WallpaperStore

/**
 * 開閉に応じて内側／外側それぞれの画像を描くライブ壁紙。
 *
 * 面の判定は [onSurfaceChanged] に渡される実寸で行う。Engine が毎回作り直される機種でも、
 * Engine が生き残ったままサーフェスだけ差し替わる機種でも、同じ経路を通るようにするため。
 */
class FoldWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FoldEngine()

    private inner class FoldEngine : Engine() {

        private val store = WallpaperStore(this@FoldWallpaperService)

        private var surfaceWidth = 0
        private var surfaceHeight = 0

        /**
         * 設定画面で画像が差し替わったら描き直す。
         * SharedPreferences はリスナーを弱参照で持つので、フィールドとして保持する必要がある。
         */
        private val preferenceListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                currentSide()?.let(WallpaperImageCache::remove)
                drawFrame()
            }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            store.registerListener(preferenceListener)
        }

        override fun onDestroy() {
            store.unregisterListener(preferenceListener)
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            drawFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceWidth = 0
            surfaceHeight = 0
            super.onSurfaceDestroyed(holder)
        }

        private fun currentSide(): DisplaySide? =
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                DeviceProfile.classify(surfaceWidth, surfaceHeight)
            } else {
                null
            }

        private fun drawFrame() {
            val side = currentSide() ?: return
            val bitmap = bitmapFor(side)
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    WallpaperRenderer.draw(canvas, bitmap, surfaceWidth, surfaceHeight)
                }
            } catch (e: IllegalStateException) {
                // サーフェスが破棄された直後の lockCanvas。次の onSurfaceChanged で描き直される。
                Log.d(TAG, "描画をスキップしました", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: IllegalStateException) {
                        Log.d(TAG, "サーフェスの解放に失敗しました", e)
                    }
                }
            }
        }

        private fun bitmapFor(side: DisplaySide): Bitmap? {
            val stamp = store.stampFor(side)
            val file = store.imageFileFor(side) ?: return null
            WallpaperImageCache.get(side, surfaceWidth, surfaceHeight, stamp)?.let { return it }

            val decoded = WallpaperRenderer.decodeScaled(file, surfaceWidth, surfaceHeight)
                ?: return null
            WallpaperImageCache.put(side, surfaceWidth, surfaceHeight, stamp, decoded)
            return decoded
        }
    }

    private companion object {
        const val TAG = "FoldWallpaperService"
    }
}
