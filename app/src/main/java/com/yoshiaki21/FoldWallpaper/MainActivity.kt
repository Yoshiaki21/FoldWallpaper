package com.yoshiaki21.FoldWallpaper

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToInt

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
                    SettingsRoot()
                }
            }
        }
    }
}

/**
 * プレビュー枠の高さ。暗さを見比べながら調整できる大きさと、
 * スライダーまでスクロールなしで収まることの兼ね合いで決めた暫定値。
 *
 * 幅はこの高さと面のアスペクト比から決まる（外側は縦長なので細くなる）。
 */
private const val PREVIEW_HEIGHT_DP = 240

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

/** 設定画面と情報画面の切り替え。画面数が2つだけなので Navigation は使わない。 */
@Composable
private fun SettingsRoot() {
    var showInfo by remember { mutableStateOf(false) }

    BackHandler(enabled = showInfo) { showInfo = false }

    if (showInfo) {
        InfoScreen(onBack = { showInfo = false })
    } else {
        SettingsScreen(onOpenInfo = { showInfo = true })
    }
}

@Composable
private fun SettingsScreen(onOpenInfo: () -> Unit) {
    val context = LocalContext.current
    val store = WallpaperStore(context)

    // フォルダの差し替えや画面復帰のたびに増やし、フォルダの中身を読み直させる。
    var revision by remember { mutableIntStateOf(0) }

    // スライダーを動かしている間もプレビューが追従するよう、画面側でも暗さを持つ。
    val dimPercents = remember {
        mutableStateMapOf<DisplaySide, Int>().apply {
            DisplaySide.entries.forEach { put(it, store.dimPercent(it)) }
        }
    }

    // 面とプロファイルの組ごとの状態は画面側で保持する。タブを切り替えるたびに
    // SAF のフォルダ走査が走らないようにするため。
    val slotStates = remember {
        mutableStateMapOf<Pair<DisplaySide, WallpaperProfile>, SideUiState>()
    }

    LaunchedEffect(revision) {
        val slots = DisplaySide.entries.flatMap { side ->
            WallpaperProfile.entries.map { profile -> side to profile }
        }
        slots.forEach { slotStates[it] = SideUiState(loading = true) }
        slots.forEach { slot ->
            slotStates[slot] = withContext(Dispatchers.IO) {
                loadSlotState(context, store, slot.first, slot.second)
            }
        }
    }

    // プレビューがどちらのプロファイルの画像かを示すため、設定画面でも判定を追う。
    val activeProfile = ProfileSelection.fromRingerMode(rememberRingerMode())

    OnResume { revision++ }

    // 幅が足りる面（内側）では2面を横に並べ、狭い面（外側）ではタブで切り替える。
    // 判定は設定画面のウィンドウ実寸に対して行うので、分割画面で狭くなった場合もタブ側に倒れる。
    val containerSize = LocalWindowInfo.current.containerSize
    val sideBySide =
        DeviceProfile.classify(containerSize.width, containerSize.height) == DisplaySide.INNER

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onOpenInfo) {
                Text(text = stringResource(R.string.action_open_info))
            }
        }

        if (sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DisplaySide.entries.forEach { side ->
                    SidePanel(
                        side = side,
                        store = store,
                        states = statesFor(slotStates, side),
                        activeProfile = activeProfile,
                        dimPercent = dimPercents[side] ?: WallpaperDimming.DEFAULT_PERCENT,
                        showTitle = true,
                        onDimChange = { dimPercents[side] = it },
                        onDimCommit = { store.setDimPercent(side, it) },
                        onFolderChanged = { revision++ },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            var selectedIndex by remember { mutableIntStateOf(0) }
            val selectedSide = DisplaySide.entries[selectedIndex]

            PrimaryTabRow(selectedTabIndex = selectedIndex) {
                DisplaySide.entries.forEachIndexed { index, side ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        text = { Text(stringResource(side.shortLabelRes())) },
                    )
                }
            }

            SidePanel(
                side = selectedSide,
                store = store,
                states = statesFor(slotStates, selectedSide),
                activeProfile = activeProfile,
                dimPercent = dimPercents[selectedSide] ?: WallpaperDimming.DEFAULT_PERCENT,
                showTitle = false,
                onDimChange = { dimPercents[selectedSide] = it },
                onDimCommit = { store.setDimPercent(selectedSide, it) },
                onFolderChanged = { revision++ },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        IntervalCard(store = store)

        SetLiveWallpaperButton(revision = revision, onReturned = { revision++ })
    }
}

/** 片面ぶんのプレビュー・暗さ・フォルダ設定。 */
@Composable
private fun SidePanel(
    side: DisplaySide,
    store: WallpaperStore,
    states: Map<WallpaperProfile, SideUiState>,
    activeProfile: WallpaperProfile,
    dimPercent: Int,
    showTitle: Boolean,
    onDimChange: (Int) -> Unit,
    onDimCommit: (Int) -> Unit,
    onFolderChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showTitle) {
            Text(
                text = stringResource(side.shortLabelRes()),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // プレビューは実際に表示中の1枚。どのプロファイルのものかはフォルダ設定側で示す。
        PreviewBox(
            side = side,
            state = states[activeProfile] ?: SideUiState(),
            dimPercent = dimPercent,
        )

        DimSlider(
            dimPercent = dimPercent,
            onDimChange = onDimChange,
            onDimCommit = onDimCommit,
        )

        FolderSection(
            side = side,
            store = store,
            states = states,
            activeProfile = activeProfile,
            onFolderChanged = onFolderChanged,
        )
    }
}

/**
 * 実機の見え方に近づけたプレビュー。
 *
 * 高さを固定し、幅をアスペクト比から決める。幅を基準にすると外側（縦長）の枠が
 * 画面いっぱいの高さになってしまい、スライダーと並べて見られない。
 */
@Composable
private fun PreviewBox(side: DisplaySide, state: SideUiState, dimPercent: Int) {
    Box(
        modifier = Modifier
            .height(PREVIEW_HEIGHT_DP.dp)
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
            // Engine 側と同じ暗さを重ね、実際の見え方に合わせる。
            val dimFraction = WallpaperDimming.alphaFraction(dimPercent)
            if (dimFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dimFraction)),
                )
            }
        } else {
            Text(
                text = stringResource(
                    if (state.loading) R.string.state_scanning else R.string.state_no_folder,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 暗さのスライダー。
 *
 * 動かしている間はプレビューだけを更新し、指を離した時点で保存する。
 * 保存のたびに壁紙が描き直されるため、書き込みは確定時の一度だけにする。
 */
@Composable
private fun DimSlider(
    dimPercent: Int,
    onDimChange: (Int) -> Unit,
    onDimCommit: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dim_value, dimPercent),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = dimPercent.toFloat(),
            onValueChange = { onDimChange(it.roundToInt()) },
            onValueChangeFinished = { onDimCommit(dimPercent) },
            valueRange = WallpaperDimming.MIN_PERCENT.toFloat()..
                WallpaperDimming.MAX_PERCENT.toFloat(),
        )
    }
}

/** 普段は畳んでおくフォルダ設定。プレビューとスライダーの表示面積を優先する。 */
@Composable
private fun FolderSection(
    side: DisplaySide,
    store: WallpaperStore,
    states: Map<WallpaperProfile, SideUiState>,
    activeProfile: WallpaperProfile,
    onFolderChanged: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.folder_section_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.folder_section_collapse
                            else R.string.folder_section_expand,
                        ),
                    )
                }
            }

            WallpaperProfile.entries.forEach { profile ->
                ProfileFolderRow(
                    side = side,
                    profile = profile,
                    store = store,
                    state = states[profile] ?: SideUiState(),
                    isActive = profile == activeProfile,
                    expanded = expanded,
                    onFolderChanged = onFolderChanged,
                )
            }

            if (expanded) {
                Text(
                    text = stringResource(R.string.folder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * プロファイル1つぶんのフォルダ設定。
 *
 * 畳んでいるときも状態だけは見えるようにし、設定済みかどうかを一目で分かるようにする。
 * いま使われているプロファイルには印を付け、プレビューがどちらの画像かを分かるようにする。
 */
@Composable
private fun ProfileFolderRow(
    side: DisplaySide,
    profile: WallpaperProfile,
    store: WallpaperStore,
    state: SideUiState,
    isActive: Boolean,
    expanded: Boolean,
    onFolderChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            busy = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (store.setFolder(side, profile, treeUri)) store.rescanFolder(side, profile)
                }
                busy = false
                onFolderChanged()
            }
        }
    }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = stringResource(
                if (isActive) R.string.profile_folder_label_active
                else R.string.profile_folder_label,
                stringResource(profile.labelRes()),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        FolderStatus(state)

        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { folderPicker.launch(store.folderUri(side, profile)) },
                    enabled = !busy,
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
                            busy = true
                            scope.launch {
                                withContext(Dispatchers.IO) { store.clearFolder(side, profile) }
                                busy = false
                                onFolderChanged()
                            }
                        },
                        enabled = !busy,
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
                Text(text = it, style = MaterialTheme.typography.bodySmall)
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
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.interval_title),
                style = MaterialTheme.typography.titleSmall,
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

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

/** 設定とは別に開く情報画面。実機の値を確認するために使う。 */
@Composable
private fun InfoScreen(onBack: () -> Unit) {
    val store = WallpaperStore(LocalContext.current)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.info_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.action_back))
            }
        }

        ProfileCard()

        MeasuredSizeCard(store = store)

        DiagnosticsCard()
    }
}

/**
 * プライベート判定の状態。マナーモードを切り替えながら動作を確認するためのもの。
 * 画面を開いたままでも追従するよう、マナーモードの変更を購読する。
 */
@Composable
private fun ProfileCard() {
    val ringerMode = rememberRingerMode()
    val profile = ProfileSelection.fromRingerMode(ringerMode)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.profile_ringer_mode,
                    stringResource(ringerModeLabelRes(ringerMode)),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.profile_selected,
                    stringResource(profile.labelRes()),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.profile_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 壁紙が実際に描かれた領域のサイズ。壁紙素材を作るときの基準になる。
 *
 * 両面の値を同時に取得するAPIが無いため、Engine が描画時に記録した実測値を表示する。
 * 端末を一度開いて一度閉じれば両方揃う。
 */
@Composable
private fun MeasuredSizeCard(store: WallpaperStore) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.measured_title),
                style = MaterialTheme.typography.titleSmall,
            )

            DisplaySide.entries.forEach { side ->
                val measured = store.measuredSize(side)
                Column {
                    Text(
                        text = stringResource(side.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (measured == null) {
                        Text(
                            text = stringResource(side.measuredMissingRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = stringResource(
                                R.string.measured_size,
                                measured.width,
                                measured.height,
                                String.format(
                                    Locale.US,
                                    "%.3f",
                                    DeviceProfile.aspectRatioOf(measured.width, measured.height),
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.measured_at,
                                DateUtils.formatDateTime(
                                    context,
                                    measured.recordedAt,
                                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 現在のマナーモードを返し、変更されたら更新する。
 *
 * `RINGER_MODE_CHANGED_ACTION` はシステムからのみ送られる protected intent なので、
 * 受信側に権限は要らない。他アプリからの送信を受ける必要はないため NOT_EXPORTED で登録する。
 */
@Composable
private fun rememberRingerMode(): Int {
    val context = LocalContext.current
    var ringerMode by remember { mutableIntStateOf(ProfileSelection.currentRingerMode(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                ringerMode = ProfileSelection.currentRingerMode(context)
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    return ringerMode
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

/** 面とプロファイルの組の状態を、その面のプロファイル別マップとして取り出す。 */
private fun statesFor(
    slotStates: Map<Pair<DisplaySide, WallpaperProfile>, SideUiState>,
    side: DisplaySide,
): Map<WallpaperProfile, SideUiState> =
    WallpaperProfile.entries.associateWith { profile ->
        slotStates[side to profile] ?: SideUiState()
    }

/** 設定画面を開いたときだけフォルダを読み直す。描画経路では再スキャンしない。 */
private fun loadSlotState(
    context: Context,
    store: WallpaperStore,
    side: DisplaySide,
    profile: WallpaperProfile,
): SideUiState {
    val folderUri = store.folderUri(side, profile)
        ?: return SideUiState(loading = false, hasFolder = false)

    if (!store.hasFolderAccess(side, profile)) {
        return SideUiState(loading = false, hasFolder = true, hasAccess = false)
    }

    val imageCount = store.rescanFolder(side, profile)
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

/** タブやスライダーの見出しに使う短い名前。 */
private fun DisplaySide.shortLabelRes(): Int = when (this) {
    DisplaySide.INNER -> R.string.side_inner_short
    DisplaySide.OUTER -> R.string.side_outer_short
}

/** まだ実測できていないときに、どう操作すれば測れるかを伝える文言。 */
private fun DisplaySide.measuredMissingRes(): Int = when (this) {
    DisplaySide.INNER -> R.string.measured_missing_inner
    DisplaySide.OUTER -> R.string.measured_missing_outer
}

private fun WallpaperProfile.labelRes(): Int = when (this) {
    WallpaperProfile.PRIVATE -> R.string.profile_private
    WallpaperProfile.STANDARD -> R.string.profile_standard
}

private fun ringerModeLabelRes(ringerMode: Int): Int = when (ringerMode) {
    AudioManager.RINGER_MODE_NORMAL -> R.string.ringer_normal
    AudioManager.RINGER_MODE_VIBRATE -> R.string.ringer_vibrate
    AudioManager.RINGER_MODE_SILENT -> R.string.ringer_silent
    else -> R.string.ringer_unknown
}

private fun SwitchInterval.labelRes(): Int = when (this) {
    SwitchInterval.NONE -> R.string.interval_none
    SwitchInterval.MINUTES_15 -> R.string.interval_15m
    SwitchInterval.HOUR_1 -> R.string.interval_1h
    SwitchInterval.HOURS_6 -> R.string.interval_6h
}

/** プレビュー枠の形。実機の実測アスペクト比に合わせてある。 */
private fun DisplaySide.previewAspectRatio(): Float = when (this) {
    DisplaySide.INNER -> 1f / 1.04f
    DisplaySide.OUTER -> 1f / 2.17f
}
