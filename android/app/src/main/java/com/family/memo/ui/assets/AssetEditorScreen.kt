package com.family.memo.ui.assets

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.memo.MemoApp
import com.family.memo.core.AssetHelper
import com.family.memo.core.ContentCodec
import com.family.memo.core.MemoContent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class AssetEditorViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as MemoApp).container
    private val repo get() = container.repository

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    private var memoId: String? = null

    val platform = MutableStateFlow("")
    val category = MutableStateFlow("video")
    val account = MutableStateFlow("")
    val password = MutableStateFlow("")
    val extra = MutableStateFlow("")
    val fields = MutableStateFlow<List<com.family.memo.core.CustomField>>(emptyList())
    val expiresAt = MutableStateFlow<Long?>(null)
    val remindBefore = MutableStateFlow(7)

    private fun applyFromDb(m: com.family.memo.data.local.MemoEntity) {
        val c = ContentCodec.decode(m.content)
        platform.value = m.title
        category.value = c.category.ifBlank { "video" }
        account.value = c.account
        password.value = c.password
        extra.value = c.extra
        fields.value = c.fields
        expiresAt.value = c.expiresAt
        remindBefore.value = c.remindBefore
    }

    fun load(id: String) {
        if (memoId == id) return
        memoId = id
        viewModelScope.launch {
            val m = repo.db.memoDao().byId(id) ?: return@launch
            applyFromDb(m)
            _loaded.value = true
        }
    }

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(platform, category, account, password, extra) { _, _, _, _, _ -> Unit }
                .drop(1)
                .debounce(2000)
                .collect { persist() }
        }
    }

    /** 显式保存（日期/提醒选择后立即调用）与防抖保存共用。 */
    fun persist() {
        val id = memoId ?: return
        if (!_loaded.value) return
        viewModelScope.launch {
            val row = repo.db.memoDao().byId(id) ?: return@launch
            val exp = expiresAt.value
            val c = MemoContent(
                type = "asset",
                category = category.value,
                account = account.value,
                password = password.value,
                extra = extra.value,
                fields = fields.value.filter { it.key.isNotBlank() || it.value.isNotBlank() },
                expiresAt = exp,
                remindBefore = remindBefore.value,
            )
            val remindAt = exp?.let { AssetHelper.computeRemindAt(it, remindBefore.value) }
            repo.updateMemo(id) { cur ->
                cur.copy(
                    title = platform.value,
                    content = ContentCodec.encode(c),
                    remindAt = remindAt,
                )
            }
            if (remindAt != null) container.reminderScheduler.rescheduleAll()
        }
    }

    /** 选择到期日（本地当日 0 点），同时按当前 remindBefore 重算提醒。 */
    fun setExpiry(localMidnight: Long?) {
        expiresAt.value = localMidnight
        persist()
    }

    fun setRemindBefore(days: Int) {
        remindBefore.value = days
        persist()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetEditorScreen(memoId: String, onClose: () -> Unit, vm: AssetEditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    LaunchedEffect(memoId) { vm.load(memoId) }
    androidx.compose.runtime.DisposableEffect(memoId) { onDispose { vm.persist() } }
    val loaded by vm.loaded.collectAsState()
    val platform by vm.platform.collectAsState()
    val category by vm.category.collectAsState()
    val account by vm.account.collectAsState()
    val password by vm.password.collectAsState()
    val extra by vm.extra.collectAsState()
    val fields by vm.fields.collectAsState()
    val expiresAt by vm.expiresAt.collectAsState()
    val remindBefore by vm.remindBefore.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var revealPassword by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.outline,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
                title = { Text("数字资产", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    TextButton(onClick = { vm.persist(); onClose() }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!loaded) return@Column

            OutlinedTextField(
                value = platform,
                onValueChange = { vm.platform.value = it },
                label = { Text("名称（如：爱奇艺黄金VIP / 家里宽带）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
            )
            Column {
                Text("分类", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssetHelper.CATEGORIES.forEach { (label, code) ->
                        FilterChip(
                            selected = category == code,
                            onClick = { vm.category.value = code },
                            label = { Text(label) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = account,
                onValueChange = { vm.account.value = it },
                label = { Text("账号（手机号 / 邮箱 / 用户名）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { vm.password.value = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { revealPassword = !revealPassword }) {
                        Text(if (revealPassword) "隐藏" else "显示")
                    }
                },
                colors = fieldColors,
            )

            // 自定义字段（网址、会员等级等，值可一键复制）
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "自定义字段",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (fields.size < 6) vm.fields.value = fields + com.family.memo.core.CustomField()
                    }) { Text("＋ 添加") }
                }
                fields.forEachIndexed { index, f ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = f.key,
                            onValueChange = { nv -> vm.fields.value = fields.toMutableList().apply { set(index, f.copy(key = nv)) } },
                            label = { Text("名称") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = fieldColors,
                        )
                        OutlinedTextField(
                            value = f.value,
                            onValueChange = { nv -> vm.fields.value = fields.toMutableList().apply { set(index, f.copy(value = nv)) } },
                            label = { Text("内容") },
                            modifier = Modifier.weight(1.4f),
                            singleLine = true,
                            colors = fieldColors,
                        )
                        IconButton(onClick = { vm.fields.value = fields.filterIndexed { i, _ -> i != index } }, Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Delete, "删除字段", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 到期日
            Column {
                Text("到期时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp))
                        Text(
                            " " + (expiresAt?.let { AssetHelper.formatDate(it) } ?: "选择日期"),
                        )
                    }
                    if (expiresAt != null) {
                        val (label, urgency) = AssetHelper.countdown(expiresAt!!)
                        Text(
                            label,
                            color = when (urgency) {
                                2 -> Color(0xFFC0395D)
                                1 -> Color(0xFFB45309)
                                else -> Color(0xFF2E9E6B)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (expiresAt != null) {
                        TextButton(onClick = { vm.setExpiry(null) }) { Text("清除") }
                    }
                }
            }

            // 提醒设置
            if (expiresAt != null) {
                Column {
                    Text("过期提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            AssetHelper.BEFORE_NONE to "不提醒",
                            0 to "到期当天",
                            3 to "提前3天",
                            7 to "提前7天",
                            30 to "提前30天",
                        ).forEach { (days, label) ->
                            FilterChip(
                                selected = remindBefore == days,
                                onClick = { vm.setRemindBefore(days) },
                                label = { Text(label) },
                            )
                        }
                    }
                    val remindAt = AssetHelper.computeRemindAt(expiresAt!!, remindBefore)
                    if (remindAt != null) {
                        Text(
                            "将在 " + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
                                .format(java.util.Date(remindAt)) + " 推送提醒",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = extra,
                onValueChange = { vm.extra.value = it },
                label = { Text("备注（谁开的、自动续费、绑定手机…）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                colors = fieldColors,
            )
            Text(
                "🔐 密码明文同步到家庭 NAS（空间内成员均可见）。外网访问建议走 Tailscale 或 HTTPS。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }

    if (showDatePicker) {
        val initial = expiresAt?.let { AssetHelper.localMidnightToDateUtc(it) }
            ?: AssetHelper.localMidnightToDateUtc(AssetHelper.todayMidnight() + 365L * 86400000)
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.setExpiry(AssetHelper.dateToLocalMidnight(it)) }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = state)
        }
    }
}
