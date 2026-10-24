package com.family.memo.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "memosync")

/** 应用配置与登录态。cursor 为服务端 changes 游标。 */
class Prefs(private val context: Context) {
    private object K {
        val baseUrl = stringPreferencesKey("base_url")
        val baseUrlAlt = stringPreferencesKey("base_url_alt")
        val activeUrl = stringPreferencesKey("active_url")
        val activeUrlAt = longPreferencesKey("active_url_at")
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val nickname = stringPreferencesKey("nickname")
        val role = stringPreferencesKey("role")
        val cursor = longPreferencesKey("cursor")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncError = stringPreferencesKey("last_sync_error")
        val fontSize = stringPreferencesKey("font_size") // standard | large | xlarge
        val elderMode = stringPreferencesKey("elder_mode") // "1" 开
        val theme = stringPreferencesKey("theme") // system | light | dark
    }

    val loggedIn: Flow<Boolean> = context.dataStore.data.map { !it[K.token].isNullOrBlank() }
    val baseUrl: Flow<String> = context.dataStore.data.map { it[K.baseUrl] ?: "" }
    val baseUrlAlt: Flow<String> = context.dataStore.data.map { it[K.baseUrlAlt] ?: "" }
    val activeUrl: Flow<String> = context.dataStore.data.map { it[K.activeUrl] ?: "" }
    val nickname: Flow<String> = context.dataStore.data.map { it[K.nickname] ?: "" }
    val fontSize: Flow<String> = context.dataStore.data.map { it[K.fontSize] ?: "standard" }
    val elderMode: Flow<Boolean> = context.dataStore.data.map { (it[K.elderMode] ?: "") == "1" }
    val theme: Flow<String> = context.dataStore.data.map { it[K.theme] ?: "system" }
    val lastSyncAt: Flow<Long> = context.dataStore.data.map { it[K.lastSyncAt] ?: 0 }
    val lastSyncError: Flow<String> = context.dataStore.data.map { it[K.lastSyncError] ?: "" }

    data class Snapshot(
        val baseUrl: String,
        val baseUrlAlt: String = "",
        val activeUrl: String = "",
        val activeUrlAt: Long = 0,
        val token: String,
        val userId: String,
        val nickname: String,
        val role: String,
        val cursor: Long,
        val fontSize: String,
        val elderMode: Boolean,
        val theme: String,
    )

    suspend fun snapshot(): Snapshot {
        val p = context.dataStore.data.first()
        return Snapshot(
            baseUrl = p[K.baseUrl] ?: "",
            baseUrlAlt = p[K.baseUrlAlt] ?: "",
            activeUrl = p[K.activeUrl] ?: "",
            activeUrlAt = p[K.activeUrlAt] ?: 0L,
            token = p[K.token] ?: "",
            userId = p[K.userId] ?: "",
            nickname = p[K.nickname] ?: "",
            role = p[K.role] ?: "",
            cursor = p[K.cursor] ?: 0L,
            fontSize = p[K.fontSize] ?: "standard",
            elderMode = (p[K.elderMode] ?: "") == "1",
            theme = p[K.theme] ?: "system",
        )
    }

    suspend fun saveLogin(baseUrl: String, token: String, userId: String, nickname: String, role: String, altUrl: String = "") {
        context.dataStore.edit { p ->
            p[K.baseUrl] = baseUrl.trimEnd('/')
            val alt = altUrl.trim().trimEnd('/')
            if (alt.isNotEmpty() && alt != baseUrl.trimEnd('/')) p[K.baseUrlAlt] = alt else p.remove(K.baseUrlAlt)
            p[K.activeUrl] = baseUrl.trimEnd('/')
            p[K.activeUrlAt] = System.currentTimeMillis()
            p[K.token] = token
            p[K.userId] = userId
            p[K.nickname] = nickname
            p[K.role] = role
            p[K.cursor] = 0L
        }
    }

    /** 记录本次实际使用的地址（双地址自动切换后回写）。 */
    suspend fun setActiveUrl(url: String) {
        context.dataStore.edit { p ->
            p[K.activeUrl] = url.trim().trimEnd('/')
            p[K.activeUrlAt] = System.currentTimeMillis()
        }
    }

    /** 修改地址（设置页）。 */
    suspend fun setEndpoints(primary: String, alt: String) {
        context.dataStore.edit { p ->
            p[K.baseUrl] = primary.trim().trimEnd('/')
            val a = alt.trim().trimEnd('/')
            if (a.isNotEmpty() && a != primary.trim().trimEnd('/')) p[K.baseUrlAlt] = a else p.remove(K.baseUrlAlt)
            // 地址变动后让缓存失效，下次重新探测
            p[K.activeUrlAt] = 0L
        }
    }

    suspend fun setNickname(n: String) {
        context.dataStore.edit { it[K.nickname] = n }
    }

    suspend fun setCursor(v: Long) {
        context.dataStore.edit { it[K.cursor] = v }
    }

    suspend fun setSyncResult(at: Long, error: String?) {
        context.dataStore.edit { p ->
            p[K.lastSyncAt] = at
            if (error == null) p.remove(K.lastSyncError) else p[K.lastSyncError] = error
        }
    }

    suspend fun setFontSize(v: String) {
        context.dataStore.edit { it[K.fontSize] = v }
    }

    suspend fun setElderMode(on: Boolean) {
        context.dataStore.edit { it[K.elderMode] = if (on) "1" else "0" }
    }

    suspend fun setTheme(v: String) {
        context.dataStore.edit { it[K.theme] = v }
    }

    suspend fun logout() = context.dataStore.edit { p ->
        p.remove(K.token)
        p.remove(K.userId)
        p.remove(K.nickname)
        p.remove(K.role)
        p.remove(K.cursor)
        p[K.lastSyncError] = ""
    }
}
