package com.family.memo.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.family.memo.ui.home.HomeViewModel
import kotlinx.coroutines.launch

/** 设置弹窗：字体大小、长辈模式、主题、退出登录。 */
@Composable
fun SettingsDialog(vm: HomeViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fontSize by vm.container.prefs.fontSize.collectAsState(initial = "standard")
    val elder by vm.container.prefs.elderMode.collectAsState(initial = false)
    val theme by vm.container.prefs.theme.collectAsState(initial = "system")
    var confirmLogout by remember { mutableStateOf(false) }
    val primary by vm.container.prefs.baseUrl.collectAsState(initial = "")
    val alt by vm.container.prefs.baseUrlAlt.collectAsState(initial = "")
    val active by vm.container.prefs.activeUrl.collectAsState(initial = "")
    var editPrimary by remember(primary) { mutableStateOf(primary) }
    var editAlt by remember(alt) { mutableStateOf(alt) }
    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("设置") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    Text(
                        "服务器地址（双地址）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "当前生效：" + (active.ifBlank { "未连接" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = editPrimary,
                        onValueChange = { editPrimary = it },
                        label = { Text("主地址（内网）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = editAlt,
                        onValueChange = { editAlt = it },
                        label = { Text("备用地址（外网 / Tailscale，可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch { vm.container.prefs.setEndpoints(editPrimary, editAlt); vm.refreshSpaces(); testResult = "地址已保存" }
                        }) { Text("保存地址") }
                        TextButton(enabled = !testing, onClick = {
                            testing = true
                            testResult = "测试中…"
                            scope.launch {
                                val r = try {
                                    vm.container.remote.resolveBaseUrl(forceProbe = true)
                                } catch (e: Exception) {
                                    null
                                }
                                testResult = when (r) {
                                    is com.family.memo.data.api.EndpointResolver.Result.Ready ->
                                        (if (r.switched) "已切换到" else "可用：") + r.label + "（" + r.url + "）"
                                    is com.family.memo.data.api.EndpointResolver.Result.Failed ->
                                        "都连不上：" + r.tried.joinToString()
                                    is com.family.memo.data.api.EndpointResolver.Result.NotConfigured ->
                                        "尚未填写地址"
                                    null -> "测试失败"
                                }
                                testing = false
                            }
                        }) { Text(if (testing) "测试中…" else "测试连接") }
                    }
                    if (testResult.isNotBlank()) {
                        Text(
                            testResult,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                SettingGroup("字体大小") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("standard" to "标准", "large" to "大", "xlarge" to "特大").forEach { (v, label) ->
                            FilterChip(
                                selected = fontSize == v,
                                onClick = { scope.launch { vm.container.prefs.setFontSize(v) } },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                SettingGroup("长辈简化模式（更大字号、隐藏筛选）") {
                    Switch(checked = elder, onCheckedChange = { on -> scope.launch { vm.container.prefs.setElderMode(on) } })
                }
                SettingGroup("主题") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (v, label) ->
                            FilterChip(
                                selected = theme == v,
                                onClick = { scope.launch { vm.container.prefs.setTheme(v) } },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                TextButton(onClick = { confirmLogout = true }) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("本机缓存的备忘录会被清空，重新登录后自动同步回来。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    vm.logout { onClose() }
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
