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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 開閉に応じて内側／外側それぞれのフォルダから画像を選んで描くライブ壁紙。
 *
 * 面の判定は [WallpaperService.Engine.onSurfaceChanged] に渡される実寸で行う。
 * Engine が毎回作り直される機種でも、Engine が生き残ったままサーフェスだけ
 * 差し替わる機種でも、同じ経路を通るようにするため。
 */
class FoldWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FoldEngine()

    private inner class FoldEngine : Engine() {

        private val store = WallpaperStore(this@FoldWallpaperService)
        private val rotator = ImageRotator(store)

        /**
         * 描画とSAFアクセスを行うスレッド。
         *
         * 画像のデコードは100ms単位でかかるためメインスレッドでは行えない。一方で
         * `lockCanvas` は直列化されている必要があるので、単一スレッドに固定する。
         */
        private val executor = Executors.newSingleThreadExecutor()
        private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

        private var surfaceWidth = 0
        private var surfaceHeight = 0

        /**
         * 設定画面でフォルダや間隔が変わったら描き直す。
         * SharedPreferences はリスナーを弱参照で持つので、フィールドとして保持する必要がある。
         */
        private val preferenceListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when {
                    store.isImageSourceKey(key) -> {
                        // rotator には単一スレッドからだけ触れる。executor が順序を保証する。
                        scope.launch { rotator.onSettingsChanged() }
                        update()
                    }
                    // 暗さの変更で画像まで替わってしまわないよう、描き直すだけにする。
                    store.isAppearanceKey(key) -> redraw()
                }
            }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            store.registerListener(preferenceListener)
        }

        override fun onDestroy() {
            store.unregisterListener(preferenceListener)
            scope.cancel()
            executor.shutdown()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            update()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            redraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) update()
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

        /** 必要なら次の画像へ進めてから描く。 */
        private fun update() {
            val side = currentSide() ?: return
            scope.launch {
                if (shouldAdvance(side)) rotator.advance(side)
                store.lastRenderedSide = side
                drawFrame(side)
                rotator.prefetchNext(side)
            }
        }

        /** 画像はそのままで描き直すだけ。 */
        private fun redraw() {
            val side = currentSide() ?: return
            scope.launch { drawFrame(side) }
        }

        /**
         * 切り替えるべきかの判定。次のいずれかで切り替える。
         * 1. 前回描画した面と違う（＝開閉した）
         * 2. 最終切替から設定間隔が経過している
         * 3. 表示中の画像が無い（初回・フォルダ変更直後）
         */
        private fun shouldAdvance(side: DisplaySide): Boolean {
            if (store.lastRenderedSide != side) return true
            if (store.currentImageFile(side) == null) return true

            val interval = store.switchInterval
            if (!interval.isTimeBased) return false
            val elapsed = System.currentTimeMillis() - store.lastSwitchAt(side)
            return elapsed >= interval.millis
        }

        private fun drawFrame(side: DisplaySide) {
            val width = surfaceWidth
            val height = surfaceHeight
            if (width <= 0 || height <= 0) return

            val bitmap = bitmapFor(side, width, height)
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    WallpaperRenderer.draw(canvas, bitmap, width, height, store.dimPercent(side))
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

        private fun bitmapFor(side: DisplaySide, width: Int, height: Int): Bitmap? {
            val stamp = store.currentStamp(side)
            val file = rotator.currentImage(side) ?: return null
            WallpaperImageCache.get(side, width, height, stamp)?.let { return it }

            val decoded = WallpaperRenderer.decodeScaled(file, width, height) ?: return null
            WallpaperImageCache.put(side, width, height, stamp, decoded)
            return decoded
        }
    }

    private companion object {
        const val TAG = "FoldWallpaperService"
    }
}
