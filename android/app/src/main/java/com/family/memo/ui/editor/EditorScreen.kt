package com.family.memo.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.family.memo.MemoApp
import com.family.memo.core.CheckItem
import com.family.memo.core.RelativeTime
import com.family.memo.data.local.MemoEntity
import com.family.memo.ui.MemoFiles
import com.family.memo.ui.theme.MemoCardColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(memoId: String, onClose: () -> Unit, vm: EditorViewModel = viewModel()) {
    LaunchedEffect(memoId) { vm.load(memoId) }
    val memo by vm.memo.collectAsState()
    val title by vm.title.collectAsState()
    val text by vm.text.collectAsState()
    val items by vm.items.collectAsState()
    val tags by vm.tags.collectAsState()
    val remindAts by vm.remindAts.collectAsState()
    var showTagInput by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val appContainer = (context.applicationContext as MemoApp).container
    val spaces by appContainer.db.spaceDao().observeAll().collectAsState(initial = emptyList())
    val familySpaceId = spaces.firstOrNull { it.type == "family" }?.id
    val isFamily = familySpaceId != null && memo?.spaceId == familySpaceId

    var menuOpen by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }
    var showRemind by remember { mutableStateOf(false) }
    var showSpacePicker by remember { mutableStateOf(false) }

    // 退出编辑页时立即保存（自动保存有 2 秒防抖，快速返回会丢最后一段输入）
    androidx.compose.runtime.DisposableEffect(memoId) {
        onDispose { vm.persist() }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            vm.addImage(uri, name, mime)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
                title = { Text("") },
                actions = {
                    IconButton(onClick = { vm.setPinned(!(memo?.pinned ?: false)) }) {
                        Icon(
                            Icons.Filled.PushPin,
                            if (memo?.pinned == true) "取消置顶" else "置顶",
                            tint = if (memo?.pinned == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showSpacePicker = true }) {
                        Icon(
                            Icons.Filled.Share,
                            "切换空间",
                            tint = if (isFamily) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (memo?.archived == true) "取消归档" else "归档") },
                                onClick = { menuOpen = false; vm.setArchived(!(memo?.archived ?: false)) },
                            )
                            DropdownMenuItem(
                                text = { Text("移入回收站") },
                                onClick = { menuOpen = false; vm.trash(onClose) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            EditorToolbar(
                onImage = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onChecklist = { vm.toggleChecklist() },
                onColor = { showColor = true },
                onRemind = { showRemind = true },
                memo = memo,
                hasRemind = remindAts.isNotEmpty(),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { vm.title.value = it },
                placeholder = { Text("标题", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                textStyle = MaterialTheme.typography.titleLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
                singleLine = true,
            )
            // 标签行
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tags.forEach { tag ->
                    androidx.compose.material3.SuggestionChip(
                        onClick = { vm.removeTag(tag) },
                        label = { Text("#$tag ✕", style = MaterialTheme.typography.labelMedium) },
                    )
                }
                androidx.compose.material3.SuggestionChip(
                    onClick = { showTagInput = true },
                    label = { Text("＋ 标签", style = MaterialTheme.typography.labelMedium) },
                )
            }
            // 提醒时间列表（点 ✕ 移除该次提醒）
            if (remindAts.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    remindAts.sorted().forEach { at ->
                        androidx.compose.material3.SuggestionChip(
                            onClick = { vm.removeRemindAt(at) },
                            label = {
                                Text(
                                    "⏰ " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at)) + " ✕",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }
            if (memo?.type == "checklist") {
                items.forEachIndexed { index, item: CheckItem ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        IconButton(onClick = { vm.toggleItem(index) }, Modifier.size(30.dp)) {
                            Icon(
                                if (item.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                if (item.done) "已完成" else "未完成",
                                tint = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = item.text,
                            onValueChange = { vm.updateItemText(index, it) },
                            placeholder = { Text("条目 ${index + 1}") },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                            singleLine = true,
                        )
                        IconButton(onClick = { vm.removeItem(index) }, Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Delete, "删除条目", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { vm.addItem() }, modifier = Modifier.padding(start = 12.dp)) {
                    Text("＋ 添加条目")
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { vm.text.value = it },
                    placeholder = { Text("记点什么…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(260.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
            }
            // 附件缩略图
            memo?.attachmentList?.takeIf { it.isNotEmpty() }?.let { atts ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    atts.forEach { att ->
                        Box {
                            AsyncImage(
                                model = File(MemoFiles.cacheDir(), att.sha256),
                                contentDescription = att.filename,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .clickable { vm.removeAttachment(att.sha256) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Delete, "移除图片", Modifier.size(12.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
            Text(
                when {
                    memo == null -> ""
                    isFamily -> "👥 家庭共享 · "
                    else -> "🔒 私人 · "
                } + if (memo?.dirty == true) "自动保存中…" else "✅ 已同步 ${RelativeTime.format(memo?.updatedAt ?: 0)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (showTagInput) {
        AlertDialog(
            onDismissRequest = { showTagInput = false },
            title = { Text("添加标签") },
            text = {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("标签名（最长 24 字）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addTag(tagInput)
                    tagInput = ""
                    showTagInput = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showTagInput = false }) { Text("取消") } },
        )
    }
    if (showColor) {
        AlertDialog(
            onDismissRequest = { showColor = false },
            title = { Text("卡片颜色") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MemoCardColors.forEach { (name, c) ->
                        val selected = memo?.color == name || (memo?.color == "default" && name == "default")
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (selected) c.copy(alpha = 0.55f).compositeOverDark() else c)
                                .clickable { vm.setColor(name); showColor = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(Icons.Filled.CheckCircle, "已选", Modifier.size(18.dp), tint = Color.White)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showColor = false }) { Text("完成") } },
        )
    }
    if (showRemind) {
        AddRemindDialog(onAdd = { at -> vm.addRemindAt(at); showRemind = false }, onDismiss = { showRemind = false })
    }
    if (showSpacePicker) {
        AlertDialog(
            onDismissRequest = { showSpacePicker = false },
            title = { Text("移动到空间") },
            text = {
                Column {
                    spaces.forEach { sp ->
                        TextButton(
                            onClick = { vm.setSpace(sp.id); showSpacePicker = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (sp.type == "family") "👥 " else if (sp.type == "group") "💼 " else "🔒 ") + sp.name +
                                    (if (memo?.spaceId == sp.id) " ✓" else ""),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSpacePicker = false }) { Text("取消") } },
        )
    }
}

private fun Color.compositeOverDark(): Color = Color(
    red = red * 0.6f, green = green * 0.6f, blue = blue * 0.6f, alpha = 1f,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRemindDialog(
    onAdd: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<Calendar?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加提醒时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "可添加多个提醒时间，到点各自推送一次",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val presets: List<Pair<String, () -> Calendar>> = listOf(
                    "1 小时后" to {
                        Calendar.getInstance().apply {
                            timeInMillis = System.currentTimeMillis()
                            add(Calendar.HOUR_OF_DAY, 1)
                        }
                    },
                    "今晚 20:00" to {
                        Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                        }
                    },
                    "明天 09:00" to {
                        Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, 1)
                            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                        }
                    },
                )
                presets.forEach { (label, calc) ->
                    TextButton(onClick = { onAdd(calc().timeInMillis); onDismiss() }) { Text(label) }
                }
                TextButton(onClick = { pickDate = true }) { Text("选日期和时间…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    if (pickDate) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = com.family.memo.core.AssetHelper.localMidnightToDateUtc(
                com.family.memo.core.AssetHelper.todayMidnight(),
            ),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        // 本地当日 0 点 + 暂存，进入时间选择
                        pickedDate = Calendar.getInstance().apply {
                            timeInMillis = com.family.memo.core.AssetHelper.dateToLocalMidnight(it)
                        }
                    }
                    pickDate = false
                }) { Text("下一步") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("取消") } },
        ) {
            androidx.compose.material3.DatePicker(state = state)
        }
    }
    pickedDate?.let { date ->
        val timeState = androidx.compose.material3.rememberTimePickerState(initialHour = 9, initialMinute = 0)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            title = { Text("选择时间") },
            text = { androidx.compose.material3.TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val cal = (date.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, timeState.hour)
                        set(Calendar.MINUTE, timeState.minute)
                        set(Calendar.SECOND, 0)
                    }
                    pickedDate = null
                    onAdd(cal.timeInMillis)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EditorToolbar(
    onImage: () -> Unit,
    onChecklist: () -> Unit,
    onColor: () -> Unit,
    onRemind: () -> Unit,
    memo: MemoEntity?,
    hasRemind: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onImage) { Icon(Icons.Filled.Image, "添加图片") }
        IconButton(onClick = onChecklist) {
            Icon(
                Icons.Filled.Checklist, "清单模式",
                tint = if (memo?.type == "checklist") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onColor) { Icon(Icons.Filled.Palette, "颜色") }
        IconButton(onClick = onRemind) {
            Icon(
                Icons.Filled.Notifications, "提醒",
                tint = if (hasRemind) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else "图片.jpg"
        } ?: "图片.jpg"
    } catch (_: Exception) {
        "图片.jpg"
    }
}
