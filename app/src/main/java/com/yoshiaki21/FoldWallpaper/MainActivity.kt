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
import androidx.activity.result.PickVisualMediaRequest
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
import com.yoshiaki21.FoldWallpaper.data.WallpaperStore
import com.yoshiaki21.FoldWallpaper.ui.theme.FoldWallpaperTheme
import com.yoshiaki21.FoldWallpaper.wallpaper.FoldWallpaperService
import com.yoshiaki21.FoldWallpaper.wallpaper.WallpaperRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 内側／外側それぞれの画像を選ぶ設定画面。ライブ壁紙の settingsActivity も兼ねる。 */
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

@Composable
private fun SettingsScreen() {
    val store = WallpaperStore(LocalContext.current)

    // 画像の差し替えや画面復帰のたびに増やし、ファイル由来の表示を読み直させる。
    var revision by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
            ImageSlotCard(
                side = side,
                store = store,
                revision = revision,
                onImageChanged = { revision++ },
                onError = { errorMessage = it },
            )
        }

        SetLiveWallpaperButton(revision = revision, onReturned = { revision++ })

        DiagnosticsCard()

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ImageSlotCard(
    side: DisplaySide,
    store: WallpaperStore,
    revision: Int,
    onImageChanged: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val saveFailedMessage = stringResource(R.string.state_save_failed)

    LaunchedEffect(side, revision) {
        thumbnail = withContext(Dispatchers.IO) {
            store.imageFileFor(side)?.let {
                WallpaperRenderer.decodeScaled(it, THUMBNAIL_TARGET_PX, THUMBNAIL_TARGET_PX)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            isSaving = true
            scope.launch {
                val saved = withContext(Dispatchers.IO) { store.saveImage(side, uri) }
                isSaving = false
                if (saved) onImageChanged() else onError(saveFailedMessage)
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
                val bitmap = thumbnail
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // Engine 側と同じ中央クロップで見せる。
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (isSaving) R.string.state_saving else R.string.state_no_image,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    enabled = !isSaving,
                ) {
                    Text(
                        text = stringResource(
                            if (thumbnail == null) R.string.action_pick_image
                            else R.string.action_change_image,
                        ),
                    )
                }
                if (thumbnail != null) {
                    TextButton(
                        onClick = {
                            store.clearImage(side)
                            onImageChanged()
                        },
                        enabled = !isSaving,
                    ) {
                        Text(text = stringResource(R.string.action_clear_image))
                    }
                }
            }
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
 * [DeviceProfile] の暫定値を実測値に置き換えるときに使う。
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

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private fun DisplaySide.labelRes(): Int = when (this) {
    DisplaySide.INNER -> R.string.side_inner
    DisplaySide.OUTER -> R.string.side_outer
}

/** プレビュー枠の形。実機の見え方に近づけるための表示上の値。 */
private fun DisplaySide.previewAspectRatio(): Float = when (this) {
    DisplaySide.INNER -> 1f / 1.1f
    DisplaySide.OUTER -> 1f / 2.2f
}
