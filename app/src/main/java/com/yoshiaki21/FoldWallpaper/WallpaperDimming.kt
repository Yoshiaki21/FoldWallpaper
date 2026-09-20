package com.yoshiaki21.FoldWallpaper

/**
 * 壁紙を暗くする度合いの定義。
 *
 * 画像の上に黒を重ねて暗くする。重ねる方式にしているのは、デコード済みのビットマップを
 * そのままキャッシュに残せるため。暗さを変えても再デコードは起きず、描き直すだけで反映される。
 *
 * 上限は実際に使ってみて決める前提の暫定値。変更はこのファイルの [MAX_PERCENT] だけで済む。
 */
object WallpaperDimming {

    /** スライダーで指定できる暗さの上限（%）。実機で試した上で調整する。 */
    const val MAX_PERCENT: Int = 80

    /** 暗くしない状態。 */
    const val MIN_PERCENT: Int = 0

    /** 初期値。既定では暗くせず、ユーザーが好みに合わせて上げる。 */
    const val DEFAULT_PERCENT: Int = 0

    /** 指定できる範囲に収める。 */
    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** 画像の上に重ねる黒のアルファ値（0〜255）。壁紙の描画に使う。 */
    fun alpha(percent: Int): Int = clamp(percent) * 255 / 100

    /** 同じ暗さを 0f〜1f で表したもの。設定画面のプレビューに使う。 */
    fun alphaFraction(percent: Int): Float = clamp(percent) / 100f
}
