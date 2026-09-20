package com.yoshiaki21.FoldWallpaper.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.yoshiaki21.FoldWallpaper.DisplaySide
import com.yoshiaki21.FoldWallpaper.SwitchInterval
import com.yoshiaki21.FoldWallpaper.WallpaperDimming
import com.yoshiaki21.FoldWallpaper.WallpaperProfile
import java.io.File
import java.io.IOException

/** 壁紙が実際に描画された領域のサイズ。壁紙素材を作るときの基準になる。 */
data class MeasuredSize(val width: Int, val height: Int, val recordedAt: Long)

/**
 * 設定と、画像のローカルキャッシュの保存先。
 *
 * SAF のフォルダURIは [android.content.ContentResolver.takePersistableUriPermission] で
 * 永続化できるが、フォルダから画像を読む処理は開閉のたびに走ると遅い。そのため
 * 「いま表示している1枚」と「次に表示する1枚」だけを内部ストレージへコピーして持ち、
 * 描画経路では SAF に触れない。
 *
 * ファイル一覧は SharedPreferences ではなく内部ファイルに置く。SharedPreferences は
 * 初回アクセス時に全キーを読み込むため、数千件の一覧を混ぜると描画経路が遅くなる。
 */
class WallpaperStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cacheDir: File
        get() = File(appContext.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    init {
        migrateLegacyFolderKeys()
    }

    // --- フォルダ設定 -------------------------------------------------------

    /** [side] と [profile] の組に選ばれたフォルダ。未設定なら null。 */
    fun folderUri(side: DisplaySide, profile: WallpaperProfile): Uri? =
        prefs.getString(key(KEY_FOLDER, side, profile), null)?.parseUriOrNull()

    /**
     * フォルダを [treeUri] に差し替える。永続パーミッションを取得し、
     * 以前のフォルダの権限は解放する。一覧とキャッシュ画像は作り直しになるので消す。
     */
    fun setFolder(side: DisplaySide, profile: WallpaperProfile, treeUri: Uri): Boolean {
        val previous = folderUri(side, profile)
        return try {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            prefs.edit { putString(key(KEY_FOLDER, side, profile), treeUri.toString()) }
            if (previous != null && previous != treeUri) releasePermission(previous, side, profile)
            resetSourceState(side, profile)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "フォルダの権限を取得できませんでした: $treeUri", e)
            false
        }
    }

    /** フォルダ設定を消し、権限も解放する。 */
    fun clearFolder(side: DisplaySide, profile: WallpaperProfile) {
        folderUri(side, profile)?.let { releasePermission(it, side, profile) }
        prefs.edit { remove(key(KEY_FOLDER, side, profile)) }
        resetSourceState(side, profile)
    }

    /** フォルダを読む権限がまだ有効か。アンインストール後の再インストール等で失効する。 */
    fun hasFolderAccess(side: DisplaySide, profile: WallpaperProfile): Boolean {
        val uri = folderUri(side, profile) ?: return false
        return appContext.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
    }

    /**
     * 他の組み合わせがまだ使っているフォルダの権限は解放しない。
     * 面（内側/外側）とプロファイル（プライベート/標準）で同じフォルダを指定できるため。
     */
    private fun releasePermission(
        uri: Uri,
        releasingSide: DisplaySide,
        releasingProfile: WallpaperProfile,
    ) {
        val stillInUse = sourceSlots().any { (side, profile) ->
            (side != releasingSide || profile != releasingProfile) && folderUri(side, profile) == uri
        }
        if (stillInUse) return
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.d(TAG, "解放する権限がありませんでした: $uri", e)
        }
    }

    /** 面とプロファイルの全組み合わせ。 */
    private fun sourceSlots(): List<Pair<DisplaySide, WallpaperProfile>> =
        DisplaySide.entries.flatMap { side ->
            WallpaperProfile.entries.map { profile -> side to profile }
        }

    // --- 切替間隔 -----------------------------------------------------------

    var switchInterval: SwitchInterval
        get() = SwitchInterval.fromMinutes(
            prefs.getInt(KEY_INTERVAL, SwitchInterval.DEFAULT.minutes),
        )
        set(value) = prefs.edit { putInt(KEY_INTERVAL, value.minutes) }

    // --- 暗さ ---------------------------------------------------------------

    /** [side] の壁紙を暗くする度合い（%）。内側と外側でパネルが違うため別々に持つ。 */
    fun dimPercent(side: DisplaySide): Int = WallpaperDimming.clamp(
        prefs.getInt(key(KEY_DIM, side), WallpaperDimming.DEFAULT_PERCENT),
    )

    fun setDimPercent(side: DisplaySide, percent: Int) {
        prefs.edit { putInt(key(KEY_DIM, side), WallpaperDimming.clamp(percent)) }
    }

    // --- 壁紙の描画領域の実測値 ---------------------------------------------

    /**
     * [side] で壁紙が実際に描かれた領域のサイズ。一度も描かれていなければ null。
     *
     * 両面の解像度を同時に取得するAPIは存在せず（`DisplayManager.getDisplays()` は
     * 今有効な論理ディスプレイしか返さない）、設定画面のウィンドウサイズは壁紙サーフェスと
     * 一致しない。壁紙素材を作るための正確な値は Engine が実測するしかないため、
     * 描画時に記録して情報画面で参照する。
     */
    fun measuredSize(side: DisplaySide): MeasuredSize? {
        val width = prefs.getInt(key(KEY_MEASURED_WIDTH, side), 0)
        val height = prefs.getInt(key(KEY_MEASURED_HEIGHT, side), 0)
        if (width <= 0 || height <= 0) return null
        return MeasuredSize(
            width = width,
            height = height,
            recordedAt = prefs.getLong(key(KEY_MEASURED_AT, side), 0L),
        )
    }

    /** 描画のたびに呼ばれるので、サイズが変わったときだけ書き込む。 */
    fun recordMeasuredSize(side: DisplaySide, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val known = measuredSize(side)
        if (known != null && known.width == width && known.height == height) return
        prefs.edit {
            putInt(key(KEY_MEASURED_WIDTH, side), width)
            putInt(key(KEY_MEASURED_HEIGHT, side), height)
            putLong(key(KEY_MEASURED_AT, side), System.currentTimeMillis())
        }
    }

    // --- ファイル一覧 -------------------------------------------------------

    /** 保存済みの画像一覧（documentId）。未スキャンなら空。 */
    fun imageDocumentIds(side: DisplaySide, profile: WallpaperProfile): List<String> {
        val file = listFile(side, profile)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines().filter { it.isNotBlank() }
        } catch (e: IOException) {
            Log.w(TAG, "一覧を読めませんでした (side=$side, profile=$profile)", e)
            emptyList()
        }
    }

    /**
     * フォルダを読み直して一覧を保存する。返り値は画像の枚数。
     *
     * 設定画面とEngineの両方から呼ばれうるので、書き込みは一時ファイル経由で行い、
     * 書きかけの一覧が読まれないようにする。
     */
    fun rescanFolder(side: DisplaySide, profile: WallpaperProfile): Int {
        val treeUri = folderUri(side, profile) ?: return 0
        val ids = ImageFolderScanner.listImageDocumentIds(appContext, treeUri)
        val target = listFile(side, profile)
        val temp = File(cacheDir, "${target.name}.tmp")
        try {
            temp.writeText(ids.joinToString(separator = "\n"))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "一覧を保存できませんでした (side=$side, profile=$profile)", e)
            temp.delete()
        }
        return ids.size
    }

    // --- 表示中／先読みの画像 -----------------------------------------------

    /** いま表示している画像。無ければ null。 */
    fun currentImageFile(side: DisplaySide): File? = currentFile(side).takeIf { it.hasContent() }

    /** 表示中の画像が変わるたびに増える値。ビットマップキャッシュのキーに使う。 */
    fun currentStamp(side: DisplaySide): Long = prefs.getLong(key(KEY_STAMP, side), 0L)

    /** いま表示している画像の documentId。 */
    fun currentDocumentId(side: DisplaySide): String? =
        prefs.getString(key(KEY_CURRENT_DOC, side), null)

    /** 先読み済みの次の画像があるか。 */
    fun hasPrefetched(side: DisplaySide): Boolean =
        nextFile(side).hasContent() && prefs.getString(key(KEY_NEXT_DOC, side), null) != null

    /**
     * 先読み済みの画像を捨てる。
     *
     * 先読みは選んだ時点のフォルダから取られている。プロファイルが切り替わった後に
     * これを昇格させると、切り替え前のフォルダの画像が出てしまう。
     * プライベート用の画像が標準プロファイルで表示される事態を避けるため、必ず捨てる。
     */
    fun clearPrefetched(side: DisplaySide) {
        nextFile(side).delete()
        prefs.edit { remove(key(KEY_NEXT_DOC, side)) }
    }

    /** [documentId] の画像を「次の1枚」としてコピーしておく。 */
    fun cacheAsNext(side: DisplaySide, profile: WallpaperProfile, documentId: String): Boolean {
        val treeUri = folderUri(side, profile) ?: return false
        val source = ImageFolderScanner.documentUri(treeUri, documentId) ?: return false
        if (!copyToLocal(source, nextFile(side))) return false
        prefs.edit { putString(key(KEY_NEXT_DOC, side), documentId) }
        return true
    }

    /** 先読み済みの1枚を表示中へ昇格させる。先読みが無ければ false。 */
    fun promoteNextToCurrent(side: DisplaySide): Boolean {
        val next = nextFile(side)
        val documentId = prefs.getString(key(KEY_NEXT_DOC, side), null)
        if (!next.hasContent() || documentId == null) return false

        val current = currentFile(side)
        current.delete()
        val moved = next.renameTo(current) || try {
            next.copyTo(current, overwrite = true)
            next.delete()
            true
        } catch (e: IOException) {
            Log.w(TAG, "画像を昇格できませんでした (side=$side)", e)
            false
        }

        if (moved) markCurrent(side, documentId)
        return moved
    }

    /** [documentId] の画像を、先読みを介さずその場で表示中にする。 */
    fun cacheAsCurrent(side: DisplaySide, profile: WallpaperProfile, documentId: String): Boolean {
        val treeUri = folderUri(side, profile) ?: return false
        val source = ImageFolderScanner.documentUri(treeUri, documentId) ?: return false
        if (!copyToLocal(source, currentFile(side))) return false
        markCurrent(side, documentId)
        return true
    }

    private fun markCurrent(side: DisplaySide, documentId: String) {
        val now = System.currentTimeMillis()
        prefs.edit {
            putString(key(KEY_CURRENT_DOC, side), documentId)
            remove(key(KEY_NEXT_DOC, side))
            putLong(key(KEY_STAMP, side), now)
            putLong(key(KEY_LAST_SWITCH, side), now)
        }
    }

    // --- 切替タイミングの記録 -----------------------------------------------

    /** [side] の画像を最後に切り替えた時刻。 */
    fun lastSwitchAt(side: DisplaySide): Long = prefs.getLong(key(KEY_LAST_SWITCH, side), 0L)

    /**
     * 最後に描画した面。Engine が作り直されてもプロセスが死んでも開閉を取りこぼさないよう、
     * Engine のフィールドではなくここに持つ。
     */
    var lastRenderedSide: DisplaySide?
        get() = prefs.getString(KEY_LAST_SIDE, null)?.let { name ->
            DisplaySide.entries.firstOrNull { it.name == name }
        }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_SIDE) else putString(KEY_LAST_SIDE, value.name)
        }

    /**
     * 最後に描画したプロファイル。[lastRenderedSide] と同じ理由で永続化する。
     * これが現在のプロファイルと違えば、マナーモードが切り替わったということ。
     */
    var lastRenderedProfile: WallpaperProfile?
        get() = prefs.getString(KEY_LAST_PROFILE, null)?.let { name ->
            WallpaperProfile.entries.firstOrNull { it.name == name }
        }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_PROFILE) else putString(KEY_LAST_PROFILE, value.name)
        }

    // --- 変更通知 -----------------------------------------------------------

    /**
     * 画像を選び直す必要がある設定（フォルダ・切替間隔）の変更か。
     *
     * Engine は自分の記帳（表示中の画像・最終切替時刻など）も同じ SharedPreferences に書く。
     * それを変更通知として拾うと描画がループするので、設定由来の変更だけを見分ける。
     */
    fun isImageSourceKey(key: String?): Boolean =
        key != null && (key.startsWith(KEY_FOLDER) || key == KEY_INTERVAL)

    /**
     * 見た目だけの設定（暗さ）の変更か。
     * 画像は据え置きで描き直すだけでよいので、[isImageSourceKey] とは扱いを分ける。
     */
    fun isAppearanceKey(key: String?): Boolean = key != null && key.startsWith(KEY_DIM)

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // --- 内部 ---------------------------------------------------------------

    /**
     * フォルダが変わったら、その組の一覧は作り直しになる。
     *
     * 表示中／先読みの画像は面ごとに1枚ずつしか持たないので、どのプロファイル由来か
     * までは区別せずに消す。次の描画で選び直される。
     */
    private fun resetSourceState(side: DisplaySide, profile: WallpaperProfile) {
        listFile(side, profile).delete()
        currentFile(side).delete()
        nextFile(side).delete()
        prefs.edit {
            remove(key(KEY_CURRENT_DOC, side))
            remove(key(KEY_NEXT_DOC, side))
            remove(key(KEY_STAMP, side))
            remove(key(KEY_LAST_SWITCH, side))
        }
    }

    /**
     * プロファイル導入前のキー（`folder_<面>`）を標準プロファイルへ移す。
     *
     * 移行先を標準にするのは、プライベート用は明示的に設定してもらうべきだから。
     * 何度呼んでも安全なように書いてある。
     */
    private fun migrateLegacyFolderKeys() {
        DisplaySide.entries.forEach { side ->
            val legacyKey = "${KEY_FOLDER}_${side.suffix()}"
            val legacyValue = prefs.getString(legacyKey, null) ?: return@forEach
            val targetKey = key(KEY_FOLDER, side, WallpaperProfile.STANDARD)

            prefs.edit {
                if (prefs.getString(targetKey, null) == null) putString(targetKey, legacyValue)
                remove(legacyKey)
            }
            // 旧形式の一覧は新しい名前で作り直されるので消しておく。
            File(cacheDir, "list_${side.suffix()}.txt").delete()
        }
    }

    /** コピー中の失敗で既存の画像を壊さないよう、一時ファイル経由で差し替える。 */
    private fun copyToLocal(source: Uri, target: File): Boolean {
        val temp = File(cacheDir, "${target.name}.tmp")
        return try {
            appContext.contentResolver.openInputStream(source)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("画像を開けませんでした: $source")

            if (temp.length() <= 0L) throw IOException("コピーした画像が空でした: $source")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "画像をコピーできませんでした: $source", e)
            temp.delete()
            false
        }
    }

    private fun currentFile(side: DisplaySide) = File(cacheDir, "current_${side.suffix()}.img")

    private fun nextFile(side: DisplaySide) = File(cacheDir, "next_${side.suffix()}.img")

    private fun listFile(side: DisplaySide, profile: WallpaperProfile) =
        File(cacheDir, "list_${side.suffix()}_${profile.suffix()}.txt")

    private fun File.hasContent(): Boolean = isFile && length() > 0L

    private fun DisplaySide.suffix(): String = name.lowercase()

    private fun WallpaperProfile.suffix(): String = name.lowercase()

    /** 面ごとの記帳（表示中の画像など）に使うキー。 */
    private fun key(prefix: String, side: DisplaySide): String = "${prefix}_${side.suffix()}"

    /** 面とプロファイルの組ごとの設定（フォルダ）に使うキー。 */
    private fun key(prefix: String, side: DisplaySide, profile: WallpaperProfile): String =
        "${prefix}_${side.suffix()}_${profile.suffix()}"

    private fun String.parseUriOrNull(): Uri? = try {
        toUri()
    } catch (e: Exception) {
        Log.w(TAG, "URIを復元できませんでした: $this", e)
        null
    }

    private companion object {
        const val TAG = "WallpaperStore"
        const val PREFS_NAME = "fold_wallpaper"
        const val CACHE_DIR_NAME = "wallpapers"

        const val KEY_FOLDER = "folder"
        const val KEY_CURRENT_DOC = "current_doc"
        const val KEY_NEXT_DOC = "next_doc"
        const val KEY_STAMP = "stamp"
        const val KEY_LAST_SWITCH = "last_switch"
        const val KEY_INTERVAL = "switch_interval_minutes"
        const val KEY_DIM = "dim"
        const val KEY_MEASURED_WIDTH = "measured_width"
        const val KEY_MEASURED_HEIGHT = "measured_height"
        const val KEY_MEASURED_AT = "measured_at"
        const val KEY_LAST_SIDE = "last_rendered_side"
        const val KEY_LAST_PROFILE = "last_rendered_profile"
    }
}
