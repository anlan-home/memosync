package com.family.memo.ui.auth

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.memo.MemoApp
import com.family.memo.data.api.ApiClient
import com.family.memo.data.api.PairReqDto
import com.family.memo.data.api.LoginReqDto
import com.family.memo.data.api.toApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as MemoApp).container

    data class State(
        val loading: Boolean = false,
        val error: String = "",
        val serverUrl: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        // 记住上次服务器地址
        viewModelScope.launch {
            val saved = container.prefs.snapshot().baseUrl
            if (saved.isNotBlank()) _state.value = _state.value.copy(serverUrl = saved)
        }
    }

    /** 登录成功后：缓存空间列表、恢复实时监听并立即同步一次。 */
    private suspend fun afterLogin(baseUrl: String, token: String) {
        val api = ApiClient.create(baseUrl) { token }
        val spaces = api.spaces().spaces
        container.db.spaceDao().clear()
        container.db.spaceDao().upsertAll(spaces.map { com.family.memo.data.local.SpaceEntity(it.id, it.name, it.type) })
        container.startEventMonitor()
        com.family.memo.sync.SyncWorker.requestNow(getApplication())
    }

    fun login(baseUrl: String, username: String, password: String, onDone: () -> Unit) {
        val url = normalizeUrl(baseUrl)
        if (url.isBlank()) {
            _state.value = _state.value.copy(error = "请填写 NAS 地址")
            return
        }
        _state.value = _state.value.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                val api = ApiClient.create(url) { "" }
                val resp = api.login(LoginReqDto(username, password, "Android 手机"))
                container.prefs.saveLogin(url, resp.token, resp.user.id, resp.user.nickname, resp.user.role)
                afterLogin(url, resp.token)
                _state.value = _state.value.copy(loading = false)
                onDone()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.toApiError().message ?: "未知错误")
            }
        }
    }

    fun pair(baseUrl: String, code: String, onDone: () -> Unit) {
        val url = normalizeUrl(baseUrl)
        if (url.isBlank() || code.isBlank()) {
            _state.value = _state.value.copy(error = "请填写服务器地址和配对码")
            return
        }
        _state.value = _state.value.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                val api = ApiClient.create(url) { "" }
                val resp = api.pair(PairReqDto(code.trim().uppercase(), "Android 手机"))
                container.prefs.saveLogin(url, resp.token, resp.user.id, resp.user.nickname, resp.user.role)
                afterLogin(url, resp.token)
                _state.value = _state.value.copy(loading = false)
                onDone()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.toApiError().message ?: "未知错误")
            }
        }
    }

    private fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isNotBlank() && !u.startsWith("http")) u = "http://$u"
        return u
    }
}

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val vm: LoginViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by vm.state.collectAsState()
    var mode by remember { mutableStateOf(0) } // 0 密码 1 配对码
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text("🗒️", style = MaterialTheme.typography.titleLarge, modifier = Modifier.size(56.dp))
        Text("家庭备忘录", style = MaterialTheme.typography.titleLarge)
        Text(
            "记事存家里，全家自动同步",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == 0,
                onClick = { mode = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("密码登录") }
            SegmentedButton(
                selected = mode == 1,
                onClick = { mode = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("配对码接入") }
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("NAS 地址（如 192.168.1.10:5231）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        if (mode == 0) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("账号") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("配对码（管理页生成，8 位）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "在 NAS 管理页「接入配对」中为成员生成配对码，输入即可免密绑定",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        if (state.error.isNotBlank()) {
            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.size(28.dp))
        } else if (mode == 0) {
            Button(
                onClick = { vm.login(url, user.trim(), pass, onDone) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("登 录") }
        } else {
            Button(
                onClick = { vm.pair(url, code, onDone) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("接入家庭 NAS") }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "🔒 数据仅存储于家庭 NAS，不经过任何第三方",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}
