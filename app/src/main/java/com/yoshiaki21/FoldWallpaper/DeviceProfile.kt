package com.yoshiaki21.FoldWallpaper

/** 壁紙を描画している面。 */
enum class DisplaySide {
    /** 内側（開いた状態）のディスプレイ。ほぼ正方形。 */
    INNER,

    /** 外側（閉じた状態）のディスプレイ。縦長。 */
    OUTER,
}

/**
 * 機種依存の数値を集約した唯一の場所。
 *
 * Fold系の後継機に買い替えたときは、このファイルの数値だけを書き換えれば対応できる。
 * 呼び出し側（Engine・設定画面）にはしきい値やアスペクト比を一切書かないこと。
 *
 * アスペクト比は向きに依存しないよう「長辺 / 短辺」で表す（常に 1.0 以上）。
 */
object DeviceProfile {

    /** このプロファイルが想定している端末。表示用。 */
    const val DEVICE_NAME: String = "Google Pixel 11 Pro Fold"

    /**
     * 内側ディスプレイの想定アスペクト比。
     *
     * 実機実測が済むまでの暫定値。参考: Pixel Fold 2208x1840 = 1.20 /
     * Pixel 9 Pro Fold 2152x2076 = 1.04。
     */
    val INNER_ASPECT_RATIO_RANGE: ClosedFloatingPointRange<Float> = 1.00f..1.45f

    /**
     * 外側ディスプレイの想定アスペクト比。
     *
     * 実機実測が済むまでの暫定値。参考: Pixel Fold 2092x1080 = 1.94 /
     * Pixel 9 Pro Fold 2424x1080 = 2.24。
     */
    val OUTER_ASPECT_RATIO_RANGE: ClosedFloatingPointRange<Float> = 1.70f..2.60f

    /**
     * 上のどちらのレンジにも入らなかったときに使う分界点。
     * これ未満なら内側、以上なら外側と見なす。
     */
    const val CLASSIFICATION_THRESHOLD: Float = 1.55f

    /** 面の判定ができないとき（サイズが未確定など）に使う既定値。端末は閉じた状態で始まる。 */
    val FALLBACK_SIDE: DisplaySide = DisplaySide.OUTER

    /** 長辺 / 短辺 のアスペクト比。サイズが不正なら 0。 */
    fun aspectRatioOf(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        return maxOf(width, height).toFloat() / minOf(width, height).toFloat()
    }

    /** 描画領域のサイズから内側／外側を判定する。 */
    fun classify(width: Int, height: Int): DisplaySide {
        val ratio = aspectRatioOf(width, height)
        return when {
            ratio <= 0f -> FALLBACK_SIDE
            ratio in INNER_ASPECT_RATIO_RANGE -> DisplaySide.INNER
            ratio in OUTER_ASPECT_RATIO_RANGE -> DisplaySide.OUTER
            ratio < CLASSIFICATION_THRESHOLD -> DisplaySide.INNER
            else -> DisplaySide.OUTER
        }
    }

    /**
     * 実測値が想定レンジに収まっているか。
     * false のときは [INNER_ASPECT_RATIO_RANGE] / [OUTER_ASPECT_RATIO_RANGE] の更新が必要。
     * 設定画面の診断表示から実機の値を確認するために使う。
     */
    fun isWithinKnownRange(width: Int, height: Int): Boolean {
        val ratio = aspectRatioOf(width, height)
        return ratio in INNER_ASPECT_RATIO_RANGE || ratio in OUTER_ASPECT_RATIO_RANGE
    }
}
