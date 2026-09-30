package com.family.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.family.memo.data.local.MemoEntity
import com.family.memo.core.ContentCodec
import com.family.memo.data.repo.MemoRepository
import com.family.memo.ui.theme.MemoCardColors
import kotlinx.coroutines.launch
import java.io.File

/** 卡片颜色。 */
fun cardColor(name: String) = MemoCardColors[name] ?: MemoCardColors["default"]!!

/** 长按操作菜单数据。 */
data class MemoActions(val onPin: () -> Unit, val onArchive: () -> Unit, val onDelete: () -> Unit)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MemoCard(
    memo: MemoEntity,
    onClick: () -> Unit,
    actions: MemoActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val content = ContentCodec.decode(memo.content)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardColor(memo.color)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (memo.pinned) {
                    Icon(
                        Icons.Filled.PushPin, contentDescription = "置顶",
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                }
                if (memo.title.isNotBlank()) {
                    Text(
                        memo.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.MoreVert, "更多",
                            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (memo.pinned) "取消置顶" else "置顶") },
                            onClick = { menuOpen = false; actions.onPin() },
                        )
                        if (memo.type == "checklist") {
                            DropdownMenuItem(
                                text = { Text("设为清单小组件内容") },
                                onClick = {
                                    menuOpen = false
                                    com.family.memo.widget.WidgetPrefs.setChecklistId(context, memo.id)
                                    com.family.memo.widget.WidgetRefresher.refreshAll(context)
                                },
                            )
                        }
                        if (memo.type == "note") {
                            DropdownMenuItem(
                                text = { Text("设为笔记小组件内容") },
                                onClick = {
                                    menuOpen = false
                                    com.family.memo.widget.WidgetPrefs.setNoteId(context, memo.id)
                                    com.family.memo.widget.WidgetRefresher.refreshAll(context)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("归档") },
                            onClick = { menuOpen = false; actions.onArchive() },
                        )
                        DropdownMenuItem(
                            text = { Text("移入回收站") },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }
            if (memo.title.isBlank() && content.type == "note" && content.text.isBlank() && content.items.isEmpty()) {
                Text("(空)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            if (content.type == "checklist") {
                val shown = content.items.take(6)
                shown.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(
                            if (item.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (item.done) "已完成" else "未完成",
                            Modifier.size(16.dp),
                            tint = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                            maxLines = 2,
                        )
                    }
                }
                if (content.items.size > 6) {
                    Text("… 共 ${content.items.size} 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (content.text.isNotBlank()) {
                Text(
                    content.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // 附件缩略图（本地缓存）
            if (memo.attachmentList.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    memo.attachmentList.take(3).forEach { att ->
                        AsyncImage(
                            model = File(MemoFiles.cacheDir(), att.sha256),
                            contentDescription = att.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            }
            if (memo.remindAt != null) {
                Text(
                    "⏰ " + com.family.memo.core.RelativeTime.format(memo.remindAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (memo.tagList.isNotEmpty()) {
                Text(
                    memo.tagList.take(3).joinToString(" ") { "#$it" } + if (memo.tagList.size > 3) " …" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (memo.conflict) {
                    Text("冲突副本 · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                if (memo.dirty) {
                    Icon(
                        Icons.Outlined.Sync, "待同步", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(3.dp))
                    Text("待同步", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(
                        Icons.Outlined.CloudDone, "已同步", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(3.dp))
                    Text(
                        com.family.memo.core.RelativeTime.format(memo.updatedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移入回收站？") },
            text = { Text("所有家庭成员的列表中都会移除，30 天内可在回收站找回。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.onDelete() }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** 附件缓存目录的静态桥（coil 模型用）。 */
object MemoFiles {
    @Volatile
    private var cache: File? = null
    fun init(dir: File) { cache = dir }
    fun cacheDir(): File = cache ?: File("/tmp")
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🗒️", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MemoGrid(
    memos: List<MemoEntity>,
    onOpen: (MemoEntity) -> Unit,
    actionsFor: (MemoEntity) -> MemoActions,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 158.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        if (header != null) {
            item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) { header() }
        }
        items(memos, key = { it.id }) { memo ->
            MemoCard(
                memo = memo,
                onClick = { onOpen(memo) },
                actions = actionsFor(memo),
            )
        }
    }
}

/** 常用的仓库操作包装（置顶/归档/删除）。 */
@Composable
fun rememberMemoActions(repo: MemoRepository): (MemoEntity) -> MemoActions {
    val scope = rememberCoroutineScope()
    return remember(repo) {
        { memo ->
            MemoActions(
                onPin = { scope.launch { repo.setPinned(memo.id, !memo.pinned) } },
                onArchive = { scope.launch { repo.setArchived(memo.id, true) } },
                onDelete = { scope.launch { repo.trash(memo.id) } },
            )
        }
    }
}
