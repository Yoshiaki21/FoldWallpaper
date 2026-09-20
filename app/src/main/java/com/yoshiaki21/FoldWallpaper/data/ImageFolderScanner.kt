package com.yoshiaki21.FoldWallpaper.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * SAF で選んだフォルダ直下の画像を列挙する。
 *
 * `DocumentFile.listFiles()` は1ファイルごとに ContentResolver へクエリを投げるため、
 * 数百枚のフォルダでは数秒かかる。ここでは子ドキュメント一覧に対する単発クエリで済ませる。
 */
object ImageFolderScanner {

    private const val TAG = "ImageFolderScanner"

    /**
     * [treeUri] 直下の画像ファイルの documentId を返す。サブフォルダには入らない。
     * 権限切れなどで読めなければ空リスト。
     */
    fun listImageDocumentIds(context: Context, treeUri: Uri): List<String> {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "フォルダURIが不正です: $treeUri", e)
            return emptyList()
        }

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                )
                val mimeColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                buildList {
                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeColumn) ?: continue
                        if (!mimeType.startsWith("image/")) continue
                        cursor.getString(idColumn)?.let(::add)
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "フォルダを読めませんでした: $treeUri", e)
            emptyList()
        }
    }

    /** [treeUri] 配下の [documentId] を指す、実際に開けるURI。 */
    fun documentUri(treeUri: Uri, documentId: String): Uri? = try {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "ドキュメントURIを作れませんでした: $documentId", e)
        null
    }

    /** フォルダの表示名。取得できなければ null。 */
    fun folderDisplayName(context: Context, treeUri: Uri): String? {
        val documentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "フォルダURIが不正です: $treeUri", e)
            return null
        }

        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return try {
            context.contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "フォルダ名を取得できませんでした: $treeUri", e)
            null
        }
    }
}
