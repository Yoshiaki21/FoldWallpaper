package com.yoshiaki21.FoldWallpaper.wallpaper

import android.util.Log
import com.yoshiaki21.FoldWallpaper.DisplaySide
import com.yoshiaki21.FoldWallpaper.WallpaperProfile
import com.yoshiaki21.FoldWallpaper.data.WallpaperStore
import java.io.File

/**
 * フォルダ内の画像を、面とプロファイルの組ごとに進めていく。
 *
 * SAF からのコピーを伴うためバックグラウンドで動かす必要があり、かつ状態を持つので、
 * すべての呼び出しを単一のスレッドから行うこと。
 */
class ImageRotator(private val store: WallpaperStore) {

    /**
     * 描画経路からフォルダを読み直した組。
     *
     * 通常のスキャン契機は設定画面を開いたときで、ここでの読み直しは一覧が失われた場合の
     * 保険にすぎない。フォルダが空だったり権限が切れていたりすると画像を用意できないまま
     * 毎回この経路に来るため、組ごとに1度だけに制限する。
     */
    private val rescanned = mutableSetOf<Pair<DisplaySide, WallpaperProfile>>()

    /** 設定が変わったら、読み直しの制限を解除する。 */
    fun onSettingsChanged() {
        rescanned.clear()
    }

    /**
     * 次の1枚に進める。
     *
     * 先読み済みがあればそれを昇格させるだけで済む。無ければその場で1枚選んでコピーする。
     * 進められなかった場合は false を返し、表示中の画像はそのまま残す
     * （フォルダが空・権限失効・コピー失敗のいずれでも壁紙を黒くしないため）。
     */
    fun advance(side: DisplaySide, profile: WallpaperProfile): Boolean {
        if (store.promoteNextToCurrent(side)) return true

        val pick = pick(side, profile) ?: return false
        val cached = store.cacheAsCurrent(side, profile, pick)
        if (!cached) Log.w(TAG, "画像を用意できませんでした (side=$side, profile=$profile)")
        return cached
    }

    /** [side] の表示中の画像。無ければ null。 */
    fun currentImage(side: DisplaySide): File? = store.currentImageFile(side)

    /** 次回用の1枚を先読みしておく。すでに先読み済みなら何もしない。 */
    fun prefetchNext(side: DisplaySide, profile: WallpaperProfile) {
        if (store.hasPrefetched(side)) return
        val pick = pick(side, profile) ?: return
        store.cacheAsNext(side, profile, pick)
    }

    /**
     * 先読み済みの画像を捨てる。
     *
     * プロファイルが切り替わったとき、先読みは切り替え前のフォルダから選ばれている。
     * そのまま昇格させると切り替え後も前のプロファイルの画像が出てしまうため、破棄する。
     */
    fun discardPrefetched(side: DisplaySide) {
        store.clearPrefetched(side)
    }

    /** 表示中と違う1枚を選ぶ。一覧が空のときだけ、組ごとに1度フォルダを読み直す。 */
    private fun pick(side: DisplaySide, profile: WallpaperProfile): String? {
        var candidates = store.imageDocumentIds(side, profile)
        if (candidates.isEmpty() &&
            rescanned.add(side to profile) &&
            store.folderUri(side, profile) != null
        ) {
            store.rescanFolder(side, profile)
            candidates = store.imageDocumentIds(side, profile)
        }
        return ImageSelection.pickNext(candidates, store.currentDocumentId(side))
    }

    private companion object {
        const val TAG = "ImageRotator"
    }
}
