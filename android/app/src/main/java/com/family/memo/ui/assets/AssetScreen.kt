package com.family.memo.ui.assets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.family.memo.core.AssetHelper
import com.family.memo.core.ContentCodec
import com.family.memo.core.RelativeTime
import com.family.memo.data.local.MemoEntity
import com.family.memo.ui.EmptyState
import com.family.memo.ui.cardColor
import com.family.memo.ui.home.HomeViewModel
import kotlinx.coroutines.launch

/** 「资产」页签：数字资产列表（按到期时间升序）。 */
@Composable
fun AssetTab(vm: HomeViewModel, onOpenEditor: (String) -> Unit) {
    val memos by vm.memos.collectAsState()
    val spaces by vm.spaces.collectAsState()
    var selectedSpace by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val assets = memos
        .filter { it.type == "asset" && (selectedSpace.isBlank() || it.spaceId == selectedSpace) }
        .sortedBy { ContentCodec.decode(it.content).expiresAt ?: Long.MAX_VALUE }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = selectedSpace.isBlank(), onClick = { selectedSpace = "" }, label = { Text("全部") })
            spaces.forEach { sp ->
                FilterChip(selected = selectedSpace == sp.id, onClick = { selectedSpace = sp.id }, label = { Text(sp.name) })
            }
        }
        Text(
            "🔐 账号密码同步给空间内所有成员；建议配合 Tailscale/HTTPS 使用",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (assets.isEmpty()) {
            EmptyState("把家里的视频会员、宽带、云盘账号都记在这里")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(assets, key = { it.id }) { memo ->
                    AssetCard(
                        memo = memo,
                        onClick = { onOpenEditor(memo.id) },
                        onDelete = { scope.launch { vm.repo.trash(memo.id) } },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AssetCard(memo: MemoEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    val content = ContentCodec.decode(memo.content)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var reveal by remember(memo.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun copy(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "${label}已复制", Toast.LENGTH_SHORT).show()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor(memo.color.ifBlank { "apricot" })),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    memo.title.ifBlank { "(未命名资产)" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    AssetHelper.categoryLabel(content.category),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, Modifier.size(28.dp)) {
                        Icon(Icons.Filled.MoreVert, "更多", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("移入回收站") },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }

            if (content.account.isNotBlank()) {
                InfoRow("账号", content.account, onCopy = { copy("账号", content.account) })
            }
            if (content.password.isNotBlank()) {
                InfoRow(
                    "密码",
                    if (reveal) content.password else "••••••••",
                    onCopy = { copy("密码", content.password) },
                    trailing = {
                        IconButton(onClick = { reveal = !reveal }, Modifier.size(26.dp)) {
                            Icon(
                                if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                if (reveal) "隐藏密码" else "显示密码",
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
            // 自定义字段（键值对，值可一键复制）
            content.fields.forEach { f ->
                if (f.key.isNotBlank() || f.value.isNotBlank()) {
                    InfoRow(f.key.ifBlank { "字段" }, f.value, onCopy = { copy(f.key.ifBlank { "字段" }, f.value) })
                }
            }

            if (content.expiresAt != null) {
                val (label, urgency) = AssetHelper.countdown(content.expiresAt)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.CalendarMonth, "到期", Modifier.size(14.dp), tint = urgencyColor(urgency))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${AssetHelper.formatDate(content.expiresAt)}到期 · $label",
                        style = MaterialTheme.typography.bodyMedium,
                        color = urgencyColor(urgency),
                    )
                    if (content.remindBefore != AssetHelper.BEFORE_NONE) {
                        Text(
                            " · 提前${content.remindBefore}天提醒",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (content.extra.isNotBlank()) {
                Text(
                    content.extra,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                if (memo.dirty) "⟳ 待同步" else "✅ ${RelativeTime.format(memo.updatedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移入回收站？") },
            text = { Text("家庭成员列表中都会移除，30 天内可在回收站找回。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("移入回收站") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

private fun urgencyColor(urgency: Int) = when (urgency) {
    2 -> Color_Expired
    1 -> Color_Soon
    else -> Color_Normal
}

private val Color_Expired = androidx.compose.ui.graphics.Color(0xFFC0395D)
private val Color_Soon = androidx.compose.ui.graphics.Color(0xFFB45309)
private val Color_Normal = androidx.compose.ui.graphics.Color(0xFF2E9E6B)

@Composable
private fun InfoRow(label: String, value: String, onCopy: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            "$label  ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
        IconButton(onClick = onCopy, Modifier.size(26.dp)) {
            Icon(Icons.Filled.ContentCopy, "复制$label", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
