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
    val nickname: Flow<String> = context.dataStore.data.map { it[K.nickname] ?: "" }
    val fontSize: Flow<String> = context.dataStore.data.map { it[K.fontSize] ?: "standard" }
    val elderMode: Flow<Boolean> = context.dataStore.data.map { (it[K.elderMode] ?: "") == "1" }
    val theme: Flow<String> = context.dataStore.data.map { it[K.theme] ?: "system" }
    val lastSyncAt: Flow<Long> = context.dataStore.data.map { it[K.lastSyncAt] ?: 0 }
    val lastSyncError: Flow<String> = context.dataStore.data.map { it[K.lastSyncError] ?: "" }

    data class Snapshot(
        val baseUrl: String,
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

    suspend fun saveLogin(baseUrl: String, token: String, userId: String, nickname: String, role: String) {
        context.dataStore.edit { p ->
            p[K.baseUrl] = baseUrl.trimEnd('/')
            p[K.token] = token
            p[K.userId] = userId
            p[K.nickname] = nickname
            p[K.role] = role
            p[K.cursor] = 0L
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
