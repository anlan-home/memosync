package com.family.memo.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.family.memo.data.api.ApiError
import com.family.memo.data.api.AddMemberReqDto
import com.family.memo.data.api.CreateSpaceReqDto
import com.family.memo.ui.home.HomeViewModel
import kotlinx.coroutines.launch

/**
 * 空间与工作组管理：创建工作组、按用户名拉人/移人、解散空组。
 * 家庭空间是系统内置（全员共享），个人空间私有——两者不在此管理。
 */
@Composable
fun SpaceManageDialog(vm: HomeViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val spaces by vm.spaces.collectAsState()
    val groups = spaces.filter { it.type == "group" }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var managingId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }

    fun run(block: suspend () -> String) {
        scope.launch {
            message = try {
                block()
            } catch (e: Exception) {
                (e as? ApiError)?.message ?: e.message ?: "操作失败"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("空间与工作组") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (message.isNotBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    "工作组：组内备忘录仅组员可见，适合与部分家人或同事协作。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 8.dp))
                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("组名（≤30 字）") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        TextButton(onClick = {
                            if (newName.isNotBlank()) {
                                run {
                                    vm.container.remote.client().createSpace(CreateSpaceReqDto(newName.trim()))
                                    vm.refreshSpaces()
                                    newName = ""
                                    creating = false
                                    "已创建"
                                }
                            }
                        }) { Text("创建") }
                        TextButton(onClick = { creating = false }) { Text("取消") }
                    }
                } else {
                    TextButton(onClick = { creating = true }) { Text("＋ 新建工作组") }
                }
                Spacer(Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(groups, key = { it.id }) { grp ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("💼 ${grp.name}", Modifier.weight(1f))
                            TextButton(onClick = { managingId = grp.id }) { Text("成员") }
                            TextButton(onClick = {
                                run {
                                    vm.container.remote.client().deleteSpace(grp.id)
                                    vm.refreshSpaces()
                                    "已解散（仅空组可解散）"
                                }
                            }) { Text("解散", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    if (groups.isEmpty()) {
                        item { Text("还没有工作组", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
    managingId?.let { spaceId ->
        MemberManageDialog(
            vm = vm,
            spaceId = spaceId,
            onClose = { managingId = null },
        )
    }
}

@Composable
private fun MemberManageDialog(vm: HomeViewModel, spaceId: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<com.family.memo.data.api.SpaceMemberDto>>(emptyList()) }
    var addUser by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            try {
                members = vm.container.remote.client().spaceMembers(spaceId).members
                err = ""
            } catch (e: Exception) {
                err = (e as? ApiError)?.message ?: "加载失败"
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(spaceId) { reload() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("工作组成员") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (err.isNotBlank()) {
                    Text(err, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(members, key = { it.userId }) { m ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.nickname, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (m.role == "owner") "组长" else "成员",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (m.role != "owner") {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            vm.container.remote.client().removeSpaceMember(spaceId, m.userId)
                                            reload()
                                        } catch (e: Exception) {
                                            err = (e as? ApiError)?.message ?: "移除失败"
                                        }
                                    }
                                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = addUser,
                        onValueChange = { addUser = it },
                        label = { Text("按用户名添加") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                vm.container.remote.client().addSpaceMember(spaceId, AddMemberReqDto(addUser.trim()))
                                addUser = ""
                                reload()
                            } catch (e: Exception) {
                                err = (e as? ApiError)?.message ?: "添加失败"
                            }
                        }
                    }) { Text("添加") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
}
