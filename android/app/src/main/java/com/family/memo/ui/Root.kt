package com.family.memo.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.memo.sync.SyncWorker
import com.family.memo.ui.assets.AssetTab
import com.family.memo.ui.home.HomeViewModel
import com.family.memo.ui.mine.MineTab
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    MEMOS("备忘录"), FAMILY("家庭"), CHECKLIST("清单"), ASSETS("资产"), MINE("我的"),
}

/** 主框架：底部 5 Tab。 */
@Composable
fun MemoAppRoot(
    openMemoId: String?,
    onOpenEditor: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.MEMOS.name) }
    val current = HomeTab.valueOf(tab)
    val context = LocalContext.current

    // 通知权限（提醒可靠性）首次进入时请求
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
            // 通知深链：按备忘录类型路由到对应编辑器
            val m = vm.repo.db.memoDao().byId(openMemoId)
            if (m?.type == "asset") onOpenAsset(openMemoId) else onOpenEditor(openMemoId)
        }
    }
    LaunchedEffect(Unit) { SyncWorker.ensurePeriodic(context) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = current == t,
                        onClick = { tab = t.name },
                        icon = {
                            when (t) {
                                HomeTab.MEMOS -> Icon(Icons.Filled.StickyNote2, null)
                                HomeTab.FAMILY -> Icon(Icons.Filled.FamilyRestroom, null)
                                HomeTab.CHECKLIST -> Icon(Icons.Filled.Checklist, null)
                                HomeTab.ASSETS -> Icon(Icons.Filled.Key, null)
                                HomeTab.MINE -> Icon(Icons.Outlined.Settings, null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (current != HomeTab.MINE) NewMemoFab(vm, current, onOpenEditor, onOpenAsset)
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (current) {
                HomeTab.MEMOS -> MemoListTab(vm, filterChecklist = false, familyOnly = false, onOpenEditor)
                HomeTab.FAMILY -> MemoListTab(vm, filterChecklist = false, familyOnly = true, onOpenEditor)
                HomeTab.CHECKLIST -> MemoListTab(vm, filterChecklist = true, familyOnly = false, onOpenEditor)
                HomeTab.ASSETS -> AssetTab(vm) { id -> onOpenAsset(id) }
                HomeTab.MINE -> MineTab(vm, onOpenEditor)
            }
        }
    }
}

@Composable
private fun NewMemoFab(
    vm: HomeViewModel,
    tab: HomeTab,
    onOpenEditor: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (tab == HomeTab.ASSETS) {
        // 资产页签：一键新建资产
        ExtendedFloatingActionButton(
            onClick = {
                scope.launch {
                    val space = vm.defaultSpaceId(familyOnly = true)
                    val m = vm.repo.createMemo(space, "asset")
                    onOpenAsset(m.id)
                }
            },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("记一笔资产") },
        )
        return
    }
    androidx.compose.foundation.layout.Box {
        ExtendedFloatingActionButton(
            onClick = { expanded = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("新建") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("新建笔记") },
                onClick = {
                    expanded = false
                    scope.launch {
                        val space = vm.defaultSpaceId(familyOnly = tab == HomeTab.FAMILY)
                        val m = vm.repo.createMemo(space, "note")
                        onOpenEditor(m.id)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("新建清单") },
                onClick = {
                    expanded = false
                    scope.launch {
                        val space = vm.defaultSpaceId(familyOnly = tab == HomeTab.CHECKLIST)
                        val m = vm.repo.createMemo(space, "checklist")
                        onOpenEditor(m.id)
                    }
                },
            )
        }
    }
}

/** 备忘录/家庭/清单三个页签共用的列表页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoListTab(
    vm: HomeViewModel,
    filterChecklist: Boolean,
    familyOnly: Boolean,
    onOpenEditor: (String) -> Unit,
) {
    val memos by vm.memos.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val allTags by vm.allTags.collectAsState()
    val syncAt by vm.syncAt.collectAsState()
    val syncError by vm.syncError.collectAsState()
    val elder by vm.elderMode.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedSpace by rememberSaveable { mutableStateOf("") } // ""=全部
    var selectedTag by rememberSaveable { mutableStateOf("") }   // ""=不筛选
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("🔍 搜索备忘录") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        Text(
            when {
                syncError.isNotBlank() -> "⚠ $syncError"
                else -> "✅ 已同步 " + com.family.memo.core.RelativeTime.format(syncAt)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (syncError.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (!elder) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = selectedSpace.isBlank(), onClick = { selectedSpace = "" }, label = { Text("全部") })
                spaces.forEach { sp ->
                    FilterChip(
                        selected = selectedSpace == sp.id,
                        onClick = { selectedSpace = sp.id },
                        label = { Text(sp.name) },
                    )
                }
                allTags.forEach { tag ->
                    FilterChip(
                        selected = selectedTag == tag,
                        onClick = { selectedTag = if (selectedTag == tag) "" else tag },
                        label = { Text("#$tag") },
                    )
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
                )
            }
        }
    }
    LaunchedEffect(syncAt) { syncing = false }
}
