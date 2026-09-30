package com.family.memo.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.memo.MemoApp
import com.family.memo.data.local.MemoEntity
import com.family.memo.data.local.SpaceEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 首页/家庭/清单共享的列表状态。 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as MemoApp).container
    val repo get() = container.repository

    val memos: StateFlow<List<MemoEntity>> = container.db.memoDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val spaces: StateFlow<List<SpaceEntity>> = container.db.spaceDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncAt: StateFlow<Long> = container.prefs.lastSyncAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val syncError: StateFlow<String> = container.prefs.lastSyncError
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val elderMode: StateFlow<Boolean> = container.prefs.elderMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 全部标签（去重排序，供首页筛选 chips）。 */
    val allTags: StateFlow<List<String>> = container.db.memoDao().observeActive()
        .map { list -> list.flatMap { it.tagList }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 新建默认空间：家庭页签默认家庭空间，否则个人空间。 */
    fun defaultSpaceId(familyOnly: Boolean): String {
        val list = spaces.value
        return if (familyOnly) {
            list.firstOrNull { it.type == "family" }?.id
                ?: list.firstOrNull()?.id ?: ""
        } else {
            list.firstOrNull { it.type == "personal" }?.id
                ?: list.firstOrNull()?.id ?: ""
        }
    }

    /** 建组/拉人后刷新空间缓存（服务端为准）。 */
    fun refreshSpaces() = viewModelScope.launch {
        try {
            val api = container.remote.client()
            val list = api.spaces().spaces
            container.db.spaceDao().upsertAll(list.map { com.family.memo.data.local.SpaceEntity(it.id, it.name, it.type) })
        } catch (_: Exception) {
        }
    }

    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        container.stopEventMonitor()
        container.prefs.logout()
        container.db.clearAllTables()
        onDone()
    }
}
