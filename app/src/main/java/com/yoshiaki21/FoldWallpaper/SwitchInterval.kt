package com.yoshiaki21.FoldWallpaper

/**
 * 時間経過で画像を切り替える間隔。
 *
 * 開閉したときは間隔によらず必ず切り替わる。この設定は「開閉しないまま
 * 壁紙が見える状態になったとき、どれだけ経っていれば別の1枚にするか」を決める。
 */
enum class SwitchInterval(val minutes: Int) {
    /** 時間経過では切り替えない（開閉時のみ）。 */
    NONE(0),
    MINUTES_15(15),
    HOUR_1(60),
    HOURS_6(360),
    ;

    val millis: Long get() = minutes * 60_000L

    /** 時間経過による切り替えを行うか。 */
    val isTimeBased: Boolean get() = minutes > 0

    companion object {
        val DEFAULT: SwitchInterval = HOUR_1

        /** 保存済みの分数から復元する。未知の値なら [DEFAULT]。 */
        fun fromMinutes(minutes: Int): SwitchInterval =
            entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}
