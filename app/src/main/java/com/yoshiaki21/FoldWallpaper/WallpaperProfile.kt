package com.yoshiaki21.FoldWallpaper

import android.content.Context
import android.media.AudioManager

/** 状況に応じて使い分ける壁紙の組。 */
enum class WallpaperProfile {
    /** 一人でいるとき。どんな画像でも構わない。 */
    PRIVATE,

    /** 他人の目があるとき。既定値でもある。 */
    STANDARD,
}

/**
 * どちらのプロファイルを使うかの判定。
 *
 * マナーモードを「他人の目があるか」の意思表示として使う。公共の場ではマナーをONにする
 * という前提に立ち、**着信音が鳴る状態のときだけプライベート**とみなす。
 *
 * 誤判定の被害は非対称で、プライベートなのに標準が出るのは無害だが、他人の目がある場で
 * プライベートが出るのは要件違反になる。そのため判定できない場合・想定外の値の場合は
 * すべて [FALLBACK]（標準）に倒す。
 *
 * 判定の向きを変えたくなったらこのファイルだけを見ればよい。
 */
object ProfileSelection {

    /** 判定できないときに使うプロファイル。安全側に倒すため標準とする。 */
    val FALLBACK: WallpaperProfile = WallpaperProfile.STANDARD

    /**
     * マナーモードの値からプロファイルを決める。
     *
     * [AudioManager.RINGER_MODE_NORMAL] のときだけプライベート。
     * バイブ・サイレント・想定外の値はすべて標準。
     */
    fun fromRingerMode(ringerMode: Int): WallpaperProfile = when (ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> WallpaperProfile.PRIVATE
        AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT ->
            WallpaperProfile.STANDARD
        else -> FALLBACK
    }

    /** 端末の現在のマナーモード。取得できなければ [UNKNOWN_RINGER_MODE]。 */
    fun currentRingerMode(context: Context): Int =
        context.getSystemService(AudioManager::class.java)?.ringerMode ?: UNKNOWN_RINGER_MODE

    /** 現在のプロファイル。 */
    fun current(context: Context): WallpaperProfile =
        fromRingerMode(currentRingerMode(context))

    /** マナーモードを読めなかったことを表す値。実在のモードと重ならない負値にしてある。 */
    const val UNKNOWN_RINGER_MODE: Int = -1
}
