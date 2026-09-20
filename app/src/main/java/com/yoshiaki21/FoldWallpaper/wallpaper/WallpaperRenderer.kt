package com.yoshiaki21.FoldWallpaper.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.yoshiaki21.FoldWallpaper.WallpaperDimming
import java.io.File
import kotlin.math.max

/** 画像の読み込みと、描画領域への中央クロップ描画。 */
object WallpaperRenderer {

    private const val TAG = "WallpaperRenderer"

    /** 画像が未設定のときに塗る色。 */
    const val EMPTY_BACKGROUND_COLOR: Int = Color.BLACK

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /**
     * [file] を、[targetWidth] x [targetHeight] を埋めるのに必要十分な解像度まで
     * 間引いて読み込む。失敗したら null。
     */
    fun decodeScaled(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(
                    sourceWidth = bounds.outWidth,
                    sourceHeight = bounds.outHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.path, options)
        } catch (e: Exception) {
            Log.w(TAG, "画像の読み込みに失敗しました: ${file.path}", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "画像が大きすぎて読み込めませんでした: ${file.path}", e)
            null
        }
    }

    /**
     * 中央クロップで全面に描き、[dimPercent] のぶんだけ暗くする。
     * [bitmap] が null なら単色で塗りつぶす。
     *
     * 内側（ほぼ正方形）と外側（縦長）で同じ写真を使っても破綻しないよう、
     * 短い方の辺に合わせて拡大し、はみ出した分を左右上下均等に切り落とす。
     *
     * 暗さは描画時に黒を重ねて表現する。ビットマップ自体は元のままキャッシュに残るので、
     * 暗さを変えても再デコードは発生しない。
     */
    fun draw(
        canvas: Canvas,
        bitmap: Bitmap?,
        width: Int,
        height: Int,
        dimPercent: Int = WallpaperDimming.MIN_PERCENT,
    ) {
        if (bitmap == null || bitmap.isRecycled || width <= 0 || height <= 0) {
            canvas.drawColor(EMPTY_BACKGROUND_COLOR)
            return
        }
        canvas.drawColor(EMPTY_BACKGROUND_COLOR)
        canvas.drawBitmap(bitmap, centerCropMatrix(bitmap.width, bitmap.height, width, height), paint)

        val dimAlpha = WallpaperDimming.alpha(dimPercent)
        if (dimAlpha > 0) canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
    }

    /** 中央クロップ用の変換行列。ロジックを単体テストできるよう切り出してある。 */
    fun centerCropMatrix(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Matrix {
        val matrix = Matrix()
        if (sourceWidth <= 0 || sourceHeight <= 0) return matrix
        val scale = max(
            targetWidth.toFloat() / sourceWidth.toFloat(),
            targetHeight.toFloat() / sourceHeight.toFloat(),
        )
        val dx = (targetWidth - sourceWidth * scale) / 2f
        val dy = (targetHeight - sourceHeight * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        return matrix
    }

    /**
     * 描画領域を下回らない範囲で最大の間引き率。
     * 中央クロップは長い方の辺を余らせるので、判定は短い方の辺基準で行う。
     */
    fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sampleSize = 1
        while (
            sourceWidth / (sampleSize * 2) >= targetWidth &&
            sourceHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
