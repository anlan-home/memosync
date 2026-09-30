package com.family.memo.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Application
import com.family.memo.MemoApp
import com.family.memo.core.ContentCodec
import com.family.memo.core.RelativeTime
import com.family.memo.data.local.MemoEntity
import com.family.memo.sync.SyncWorker
import com.family.memo.ui.home.HomeViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MineViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as MemoApp).container
    val repo get() = container.repository

    val reminders: StateFlow<List<MemoEntity>> = container.db.memoDao()
        .observeUpcomingReminders(System.currentTimeMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trash: StateFlow<List<MemoEntity>> = container.db.memoDao().observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archived: StateFlow<List<MemoEntity>> = container.db.memoDao().observeArchived()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncAt: StateFlow<Long> = container.prefs.lastSyncAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val nickname: StateFlow<String> = container.prefs.nickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun syncNow() = SyncWorker.requestNow(getApplication())
}

/** 「我的」页签：提醒、回收站、归档、设置。 */
@Composable
fun MineTab(vm: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(), onOpenEditor: (String) -> Unit) {
    val mine: MineViewModel = viewModel()
    val reminders by mine.reminders.collectAsState()
    val trash by mine.trash.collectAsState()
    val archived by mine.archived.collectAsState()
    val syncAt by mine.syncAt.collectAsState()
    val nickname by mine.nickname.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showSpaces by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(nickname.ifBlank { "我" }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "上次同步：" + RelativeTime.format(syncAt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { mine.syncNow() }) { Text("立即同步") }
                            TextButton(onClick = { showSettings = true }) { Text("设置") }
                            TextButton(onClick = { showSpaces = true }) { Text("工作组") }
                        }
                    }
                }
            }
            item { SectionTitle("提醒 ⏰", "${reminders.size}") }
            if (reminders.isEmpty()) {
                item { Text("  暂无即将到来的提醒", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(reminders, key = { "r-" + it.id }) { m ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = { onOpenEditor(m.id) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.title.ifBlank { ContentCodec.plainText(m.content) }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            RelativeTime.format(m.remindAt ?: 0) + " · " + (m.remindAt?.let {
                                java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(it))
                            } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item { SectionTitle("归档", "${archived.size}") }
            items(archived, key = { "a-" + it.id }) { m ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = { onOpenEditor(m.id) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.title.ifBlank { "(无标题)" }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            ContentCodec.plainText(m.content),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { scope.launch { vm.repo.setArchived(m.id, false) } }) { Text("取消归档") }
                        }
                    }
                }
            }
            item { SectionTitle("回收站", "${trash.size}") }
            if (trash.isEmpty()) {
                item { Text("  回收站是空的", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(trash, key = { "t-" + it.id }) { m ->
                var confirm by remember(m.id) { mutableStateOf(false) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.title.ifBlank { "(无标题)" }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "删除于 " + (m.deletedAt?.let { RelativeTime.format(it) } ?: "-"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { scope.launch { vm.repo.restore(m.id) } }) { Text("恢复") }
                            TextButton(onClick = { confirm = true }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("彻底删除？") },
                        text = { Text("将从你的设备回收站中清除（家庭空间中的同条目会在服务端 30 天清理时自动消失）。") },
                        confirmButton = {
                            TextButton(onClick = { confirm = false; scope.launch { vm.repo.deleteForever(m.id) } }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
                    )
                }
            }
        }
    }
    if (showSettings) {
        SettingsDialog(vm = vm, onClose = { showSettings = false })
    }
    if (showSpaces) {
        SpaceManageDialog(vm = vm, onClose = { showSpaces = false })
    }
}

@Composable
private fun SectionTitle(title: String, count: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(6.dp))
        Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
