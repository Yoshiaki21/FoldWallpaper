package com.yoshiaki21.FoldWallpaper.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.yoshiaki21.FoldWallpaper.DisplaySide
import java.io.File
import java.io.IOException

/**
 * 内側／外側それぞれの壁紙画像の保存先。
 *
 * Photo Picker が返す URI は [android.content.ContentResolver.takePersistableUriPermission] の
 * 対象外で、権限がプロセス終了や再起動で失効する。ライブ壁紙は常駐して再起動後も描画するため、
 * URI を持ち回るのではなく選択時にアプリ内部ストレージへ実体をコピーし、Engine 側は
 * そのローカルファイルだけを読む。
 */
class WallpaperStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val imageDir: File
        get() = File(appContext.filesDir, IMAGE_DIR_NAME).apply { mkdirs() }

    /** [side] 用に保存済みの画像。未設定なら null。 */
    fun imageFileFor(side: DisplaySide): File? {
        val file = fileFor(side)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /**
     * [side] の画像が最後に更新された時刻。0 なら未設定。
     * 画像キャッシュのキーに使い、差し替え時に古いビットマップを確実に捨てる。
     */
    fun stampFor(side: DisplaySide): Long = prefs.getLong(stampKey(side), 0L)

    /** 画像が1枚でも設定されているか。 */
    fun hasAnyImage(): Boolean = DisplaySide.entries.any { imageFileFor(it) != null }

    /**
     * [source] の画像を内部ストレージへコピーして [side] 用に登録する。
     * 成功したら true。コピー中の失敗で既存の画像を壊さないよう、一時ファイル経由で差し替える。
     */
    fun saveImage(side: DisplaySide, source: Uri): Boolean {
        val target = fileFor(side)
        val temp = File(imageDir, "${target.name}.tmp")
        return try {
            appContext.contentResolver.openInputStream(source)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("画像を開けませんでした: $source")

            if (temp.length() <= 0L) throw IOException("コピーした画像が空でした: $source")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            prefs.edit { putLong(stampKey(side), System.currentTimeMillis()) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "画像の保存に失敗しました (side=$side)", e)
            temp.delete()
            false
        }
    }

    /** [side] 用の画像設定を消す。 */
    fun clearImage(side: DisplaySide) {
        fileFor(side).delete()
        prefs.edit { remove(stampKey(side)) }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun fileFor(side: DisplaySide): File = File(imageDir, "${side.name.lowercase()}.img")

    private fun stampKey(side: DisplaySide): String = "stamp_${side.name.lowercase()}"

    private companion object {
        const val TAG = "WallpaperStore"
        const val PREFS_NAME = "fold_wallpaper"
        const val IMAGE_DIR_NAME = "wallpapers"
    }
}
