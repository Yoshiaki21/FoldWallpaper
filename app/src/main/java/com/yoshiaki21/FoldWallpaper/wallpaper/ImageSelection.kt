package com.yoshiaki21.FoldWallpaper.wallpaper

import kotlin.random.Random

/** フォルダ内のどの画像を次に出すかを決める。 */
object ImageSelection {

    /**
     * [candidates] からランダムに1枚選ぶ。[current] と同じものは避ける。
     *
     * 候補が空なら null。候補が [current] の1枚だけなら、避けようがないのでそれを返す。
     */
    fun pickNext(candidates: List<String>, current: String?, random: Random = Random): String? {
        if (candidates.isEmpty()) return null
        val choices = candidates.filterNot { it == current }
        if (choices.isEmpty()) return candidates.first()
        return choices[random.nextInt(choices.size)]
    }
}
