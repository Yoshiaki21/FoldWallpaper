package com.yoshiaki21.FoldWallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.yoshiaki21.FoldWallpaper.data.ImageFolderScanner
import com.yoshiaki21.FoldWallpaper.data.WallpaperStore
import com.yoshiaki21.FoldWallpaper.ui.theme.FoldWallpaperTheme
import com.yoshiaki21.FoldWallpaper.wallpaper.FoldWallpaperService
import com.yoshiaki21.FoldWallpaper.wallpaper.WallpaperRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 内側／外側それぞれのフォルダを選ぶ設定画面。ライブ壁紙の settingsActivity も兼ねる。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoldWallpaperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SettingsScreen()
                }
            }
        }
    }
}

/** サムネイル読み込み時の目標解像度。実描画には使わないので小さめで足りる。 */
private const val THUMBNAIL_TARGET_PX = 480

/** 設定画面が表示する、片面ぶんの状態。 */
private data class SideUiState(
    val loading: Boolean = true,
    val folderName: String? = null,
    val hasFolder: Boolean = false,
    val hasAccess: Boolean = true,
    val imageCount: Int = 0,
    val thumbnail: Bitmap? = null,
)

@Composable
private fun SettingsScreen() {
    val store = WallpaperStore(LocalContext.current)

    // フォルダの差し替えや画面復帰のたびに増やし、フォルダの中身を読み直させる。
    var revision by remember { mutableIntStateOf(0) }

    OnResume { revision++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
        )

        DisplaySide.entries.forEach { side ->
            FolderSlotCard(
                side = side,
                store = store,
                revision = revision,
                onFolderChanged = { revision++ },
            )
        }

        Text(
            text = stringResource(R.string.folder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        IntervalCard(store = store)

        SetLiveWallpaperButton(revision = revision, onReturned = { revision++ })

        DiagnosticsCard()
    }
}

@Composable
private fun FolderSlotCard(
    side: DisplaySide,
    store: WallpaperStore,
    revision: Int,
    onFolderChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SideUiState()) }

    LaunchedEffect(side, revision) {
        state = state.copy(loading = true)
        state = withContext(Dispatchers.IO) { loadSideState(context, store, side) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            state = state.copy(loading = true)
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (store.setFolder(side, treeUri)) store.rescanFolder(side)
                }
                onFolderChanged()
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(side.labelRes()),
                style = MaterialTheme.typography.titleMedium,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(side.previewAspectRatio())
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val thumbnail = state.thumbnail
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // Engine 側と同じ中央クロップで見せる。
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (state.loading) R.string.state_scanning else R.string.state_no_folder,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FolderStatus(state)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { folderPicker.launch(store.folderUri(side)) },
                    enabled = !state.loading,
                ) {
                    Text(
                        text = stringResource(
                            if (state.hasFolder) R.string.action_change_folder
                            else R.string.action_pick_folder,
                        ),
                    )
                }
                if (state.hasFolder) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.clearFolder(side) }
                                onFolderChanged()
                            }
                        },
                        enabled = !state.loading,
                    ) {
                        Text(text = stringResource(R.string.action_clear_folder))
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderStatus(state: SideUiState) {
    when {
        state.loading -> Unit

        !state.hasFolder -> Text(
            text = stringResource(R.string.state_no_folder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        !state.hasAccess -> Text(
            text = stringResource(R.string.state_folder_denied),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        state.imageCount == 0 -> Text(
            text = stringResource(R.string.state_folder_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Column {
            state.folderName?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(R.string.state_image_count, state.imageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IntervalCard(store: WallpaperStore) {
    var selected by remember { mutableStateOf(store.switchInterval) }
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.interval_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(text = stringResource(selected.labelRes()))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    SwitchInterval.entries.forEach { interval ->
                        DropdownMenuItem(
                            text = { Text(stringResource(interval.labelRes())) },
                            onClick = {
                                store.switchInterval = interval
                                selected = interval
                                expanded = false
                            },
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.interval_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetLiveWallpaperButton(revision: Int, onReturned: () -> Unit) {
    val context = LocalContext.current
    val component = remember { ComponentName(context, FoldWallpaperService::class.java) }
    val isActive = remember(revision) {
        WallpaperManager.getInstance(context).wallpaperInfo?.component == component
    }

    val chooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onReturned() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                chooser.launch(
                    Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        component,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.action_set_live_wallpaper))
        }
        if (isActive) {
            Text(
                text = stringResource(R.string.state_already_active),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 実機のアスペクト比を確認するための診断表示。
 * [DeviceProfile] の値を実測値に合わせるときに使う。
 */
@Composable
private fun DiagnosticsCard() {
    val containerSize = LocalWindowInfo.current.containerSize
    val width = containerSize.width
    val height = containerSize.height
    val ratio = DeviceProfile.aspectRatioOf(width, height)
    val side = DeviceProfile.classify(width, height)
    val withinKnownRange = DeviceProfile.isWithinKnownRange(width, height)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.diagnostics_device, DeviceProfile.DEVICE_NAME),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.diagnostics_size, width, height),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.diagnostics_ratio,
                    String.format(Locale.US, "%.3f", ratio),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.diagnostics_side, stringResource(side.labelRes())),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!withinKnownRange) {
                Text(
                    text = stringResource(R.string.diagnostics_out_of_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.diagnostics_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 画面が前面に戻るたびに [onResume] を呼ぶ。 */
@Composable
private fun OnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalContext.current.findLifecycleOwner()
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner?.lifecycle
        if (lifecycle == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) onResume()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }
}

/** 設定画面を開いたときだけフォルダを読み直す。描画経路では再スキャンしない。 */
private fun loadSideState(
    context: Context,
    store: WallpaperStore,
    side: DisplaySide,
): SideUiState {
    val folderUri = store.folderUri(side)
        ?: return SideUiState(loading = false, hasFolder = false)

    if (!store.hasFolderAccess(side)) {
        return SideUiState(loading = false, hasFolder = true, hasAccess = false)
    }

    val imageCount = store.rescanFolder(side)
    val thumbnail = store.currentImageFile(side)?.let {
        WallpaperRenderer.decodeScaled(it, THUMBNAIL_TARGET_PX, THUMBNAIL_TARGET_PX)
    }

    return SideUiState(
        loading = false,
        folderName = ImageFolderScanner.folderDisplayName(context, folderUri),
        hasFolder = true,
        hasAccess = true,
        imageCount = imageCount,
        thumbnail = thumbnail,
    )
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private fun DisplaySide.labelRes(): Int = when (this) {
    DisplaySide.INNER -> R.string.side_inner
    DisplaySide.OUTER -> R.string.side_outer
}

private fun SwitchInterval.labelRes(): Int = when (this) {
    SwitchInterval.NONE -> R.string.interval_none
    SwitchInterval.MINUTES_15 -> R.string.interval_15m
    SwitchInterval.HOUR_1 -> R.string.interval_1h
    SwitchInterval.HOURS_6 -> R.string.interval_6h
}

/** プレビュー枠の形。実機の見え方に近づけるための表示上の値。 */
private fun DisplaySide.previewAspectRatio(): Float = when (this) {
    DisplaySide.INNER -> 1f / 1.04f
    DisplaySide.OUTER -> 1f / 2.17f
}
