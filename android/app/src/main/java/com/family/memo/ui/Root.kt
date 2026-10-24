package com.family.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.memo.sync.SyncWorker
import com.family.memo.ui.assets.AssetTab
import com.family.memo.ui.home.HomeViewModel
import com.family.memo.ui.mine.MineTab
import kotlinx.coroutines.launch

/** 页签：线性图标风格（HyperOS 视觉），选中时染主色。 */
enum class HomeTab(val label: String, val icon: ImageVector) {
    MEMOS("备忘录", Icons.Outlined.StickyNote2),
    FAMILY("家庭", Icons.Outlined.FamilyRestroom),
    CHECKLIST("清单", Icons.Outlined.Checklist),
    ASSETS("资产", Icons.Outlined.Key),
    MINE("我的", Icons.Outlined.Person),
}

/**
 * 主框架（参考 Miuix / HyperOS）：
 * 顶部大标题 + 右侧新建，扁平底部导航（无选中胶囊、顶部细分隔线）。
 */
@Composable
fun MemoAppRoot(
    openMemoId: String?,
    onOpenEditor: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    var tabName by rememberSaveable { mutableStateOf(HomeTab.MEMOS.name) }
    val current = HomeTab.valueOf(tabName)
    val context = LocalContext.current

    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    LaunchedEffect(openMemoId) {
        if (!openMemoId.isNullOrBlank()) {
            val m = vm.repo.db.memoDao().byId(openMemoId)
            if (m?.type == "asset") onOpenAsset(openMemoId) else onOpenEditor(openMemoId)
        }
    }
    LaunchedEffect(Unit) { SyncWorker.ensurePeriodic(context) }

    val startCreateAsset = rememberCreationFlow(vm) { id, _ -> onOpenAsset(id) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        when (current) {
            HomeTab.MEMOS -> MemoListTab(vm, HomeTab.MEMOS, filterChecklist = false, familyOnly = false, onOpenEditor, onOpenAsset)
            HomeTab.FAMILY -> MemoListTab(vm, HomeTab.FAMILY, filterChecklist = false, familyOnly = true, onOpenEditor, onOpenAsset)
            HomeTab.CHECKLIST -> MemoListTab(vm, HomeTab.CHECKLIST, filterChecklist = true, familyOnly = false, onOpenEditor, onOpenAsset)
            HomeTab.ASSETS -> AssetTab(vm, onCreate = { startCreateAsset("asset") }, onOpenEditor = { id -> onOpenAsset(id) })
            HomeTab.MINE -> MineTab(vm, onOpenEditor)
        }
        // 悬浮玻璃导航栏（浮在内容之上）
        GlassBottomBar(
            items = HomeTab.entries.map { it.icon to it.label },
            selectedIndex = HomeTab.entries.indexOf(current),
            onSelect = { tabName = HomeTab.entries[it].name },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

/** 顶部标题栏：大标题 + 右侧新建按钮。 */
@Composable
private fun PageHeader(title: String, onAdd: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, "新建", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

/** 搜索条：整宽、大圆角、浅色填充（HyperOS 搜索条样式）。 */
@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "搜索备忘录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/** 筛选胶囊：选中为主色浅底 + 主色文字。 */
@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 备忘录 / 家庭 / 清单 共用的列表页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoListTab(
    vm: HomeViewModel,
    tab: HomeTab,
    filterChecklist: Boolean,
    familyOnly: Boolean,
    onOpenEditor: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
) {
    val memos by vm.memos.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val allTags by vm.allTags.collectAsState()
    val syncAt by vm.syncAt.collectAsState()
    val syncError by vm.syncError.collectAsState()
    val elder by vm.elderMode.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedSpace by rememberSaveable { mutableStateOf("") }
    var selectedTag by rememberSaveable { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }

    val familySpaceId = spaces.firstOrNull { it.type == "family" }?.id
    val filtered = memos.filter { m ->
        m.type != "asset" &&
            (if (filterChecklist) m.type == "checklist" else true) &&
            (if (familyOnly) m.spaceId == familySpaceId else true) &&
            (selectedSpace.isBlank() || m.spaceId == selectedSpace) &&
            (selectedTag.isBlank() || m.tagList.contains(selectedTag)) &&
            (query.isBlank() ||
                m.title.contains(query, true) ||
                com.family.memo.core.ContentCodec.plainText(m.content, 10000).contains(query, true))
    }

    val startCreate = rememberCreationFlow(vm) { id, isAsset ->
        if (isAsset) onOpenAsset(id) else onOpenEditor(id)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(tab.label, onAdd = { startCreate(if (tab == HomeTab.CHECKLIST) "checklist" else "note") })
        SearchBar(query) { query = it }
        Text(
            when {
                syncError.isNotBlank() -> "⚠ $syncError"
                else -> "已同步 · " + com.family.memo.core.RelativeTime.format(syncAt)
            },
            fontSize = 12.sp,
            color = if (syncError.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        if (!elder) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill("全部", selectedSpace.isBlank() && selectedTag.isBlank()) {
                    selectedSpace = ""
                    selectedTag = ""
                }
                spaces.forEach { sp ->
                    FilterPill(sp.name, selectedSpace == sp.id) {
                        selectedSpace = if (selectedSpace == sp.id) "" else sp.id
                    }
                }
                allTags.forEach { tag ->
                    FilterPill("#$tag", selectedTag == tag) {
                        selectedTag = if (selectedTag == tag) "" else tag
                    }
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = {
                syncing = true
                SyncWorker.requestNow(vm.getApplication())
            },
            modifier = Modifier.weight(1f),
        ) {
            if (filtered.isEmpty()) {
                EmptyState("记下第一条家庭备忘吧")
            } else {
                MemoGrid(
                    memos = filtered,
                    onOpen = { onOpenEditor(it.id) },
                    actionsFor = rememberMemoActions(vm.repo),
                    spaceNameOf = { spaceId ->
                        when (spaces.firstOrNull { it.id == spaceId }?.type) {
                            "family" -> "👥 家庭"
                            "group" -> "💼 " + (spaces.firstOrNull { it.id == spaceId }?.name ?: "工作组")
                            else -> "🔒 私人"
                        }
                    },
                )
            }
        }
    }
    LaunchedEffect(syncAt) { syncing = false }
}

/**
 * 创建流程：点击新建 → 选择空间 → 创建并打开编辑器。
 * 返回的函数接收 "note" / "checklist" / "asset"。
 */
@Composable
private fun rememberCreationFlow(vm: HomeViewModel, onCreated: (String, Boolean) -> Unit): (String) -> Unit {
    val scope = rememberCoroutineScope()
    val spaces by vm.spaces.collectAsState()
    var pendingType by remember { mutableStateOf<String?>(null) }

    if (pendingType != null) {
        val title = when (pendingType) {
            "note" -> "新建笔记，保存到"
            "checklist" -> "新建清单，保存到"
            else -> "记一笔资产，保存到"
        }
        AlertDialog(
            onDismissRequest = { pendingType = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        "个人空间只有你自己可见；家庭/工作组内成员都能看到并协作",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    spaces.sortedBy { if (it.type == "family") 0 else if (it.type == "group") 1 else 2 }
                        .forEach { sp ->
                            TextButton(
                                onClick = {
                                    val type = pendingType ?: return@TextButton
                                    pendingType = null
                                    scope.launch {
                                        val m = vm.repo.createMemo(sp.id, type)
                                        onCreated(m.id, type == "asset")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when (sp.type) {
                                        "family" -> "👥 " + sp.name
                                        "group" -> "💼 " + sp.name
                                        else -> "🔒 " + sp.name
                                    },
                                )
                            }
                        }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingType = null }) { Text("取消") } },
        )
    }

    return remember { { type: String -> pendingType = type } }
}
