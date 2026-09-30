package com.family.memo.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.memo.MemoApp
import com.family.memo.core.CheckItem
import com.family.memo.core.ContentCodec
import com.family.memo.core.MemoContent
import com.family.memo.data.local.MemoEntity
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as MemoApp).container
    private val repo get() = container.repository

    private val _memo = MutableStateFlow<MemoEntity?>(null)
    val memo: StateFlow<MemoEntity?> = _memo

    val title = MutableStateFlow("")
    val text = MutableStateFlow("")
    val items = MutableStateFlow<List<CheckItem>>(emptyList())
    val tags = MutableStateFlow<List<String>>(emptyList())
    val remindAts = MutableStateFlow<List<Long>>(emptyList())

    private var ready = false
    private var memoId: String? = null

    /** 载入备忘录；同步回写且本地无待推修改时刷新输入框。 */
    fun load(id: String) {
        if (memoId == id) return
        memoId = id
        viewModelScope.launch {
            val m = repo.db.memoDao().byId(id) ?: return@launch
            applyFromDb(m)
            ready = true
            repo.db.memoDao().observeById(id).collect { row ->
                if (row == null) return@collect
                if (!row.dirty) {
                    val c = ContentCodec.decode(row.content)
                    val dbText = c.text
                    val dbItems = c.items
                    if (row.title != _memo.value?.title || dbText != text.value ||
                        dbItems != items.value || row.tagList != tags.value || row.remindAtList != remindAts.value
                    ) {
                        applyFromDb(row)
                    }
                }
                _memo.value = row
            }
        }
    }

    private fun applyFromDb(m: MemoEntity) {
        val c = ContentCodec.decode(m.content)
        _memo.value = m
        title.value = m.title
        text.value = c.text
        items.value = c.items
        tags.value = m.tagList
        remindAts.value = m.remindAtList
    }

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(listOf(title, text, items, tags, remindAts)) { _ -> Unit }
                .drop(1)
                .debounce(2000)
                .collect { persist() }
        }
    }

    /** 将当前输入状态持久化（自动保存，防抖触发；也供显式调用）。 */
    fun persist() {
        val id = memoId ?: return
        if (!ready) return
        viewModelScope.launch {
            val row = repo.db.memoDao().byId(id) ?: return@launch
            val c = if (row.type == "checklist") {
                MemoContent(type = "checklist", text = text.value, items = items.value)
            } else {
                MemoContent(type = "note", text = text.value, items = items.value)
            }
            repo.updateMemo(id) {
                it.copy(
                    title = title.value,
                    content = ContentCodec.encode(c),
                    tags = com.family.memo.data.local.encodeTags(tags.value),
                    remindAts = com.family.memo.data.local.encodeAts(remindAts.value),
                    remindAt = remindAts.value.minOrNull(),
                )
            }
            container.reminderScheduler.rescheduleAllAsync()
        }
    }

    /** 新增一条提醒时间（去重排序）。 */
    fun addRemindAt(at: Long) {
        if (at <= 0) return
        if (remindAts.value.none { it == at }) {
            remindAts.value = (remindAts.value + at).sorted()
        }
    }

    /** 移动到其他空间（家庭/工作组/个人）。 */
    fun setSpace(spaceId: String) = viewModelScope.launch {
        memoId?.let { repo.setSpace(it, spaceId) }
    }

    /** 移除一条提醒时间。 */
    fun removeRemindAt(at: Long) {
        remindAts.value = remindAts.value - at
    }

    fun addTag(raw: String) {
        val t = raw.trim()
        if (t.isEmpty() || tags.value.size >= 10) return
        if (tags.value.none { it.equals(t, ignoreCase = false) }) {
            tags.value = tags.value + t
        }
    }

    fun removeTag(tag: String) {
        tags.value = tags.value - tag
    }

    fun toggleChecklist(onDone: (Boolean) -> Unit = {}) {
        val row = _memo.value ?: return
        val toChecklist = row.type != "checklist"
        viewModelScope.launch {
            repo.updateMemo(row.id) {
                if (toChecklist) it.copy(type = "checklist")
                else it.copy(type = "note")
            }
            if (toChecklist && items.value.isEmpty()) {
                items.value = listOf(CheckItem(""))
                persist()
            }
            onDone(toChecklist)
        }
    }

    fun toggleItem(index: Int) = viewModelScope.launch {
        memoId?.let { repo.toggleCheckItem(it, index) }
    }

    fun addItem() = viewModelScope.launch {
        memoId?.let { repo.addCheckItem(it, "") }
    }

    fun updateItemText(index: Int, newText: String) {
        val cur = items.value.toMutableList()
        if (index !in cur.indices) return
        cur[index] = cur[index].copy(text = newText)
        items.value = cur
    }

    fun removeItem(index: Int) = viewModelScope.launch {
        memoId?.let { repo.removeCheckItem(it, index) }
    }

    fun setColor(color: String) = viewModelScope.launch {
        memoId?.let { repo.setColor(it, color) }
    }

    fun setPinned(pinned: Boolean) = viewModelScope.launch {
        memoId?.let { repo.setPinned(it, pinned) }
    }

    fun setArchived(archived: Boolean) = viewModelScope.launch {
        memoId?.let { repo.setArchived(it, archived) }
    }

    fun trash(onClose: () -> Unit) = viewModelScope.launch {
        memoId?.let { repo.trash(it) }
        onClose()
    }

    /** 共享开关：家庭空间 ↔ 个人空间。 */
    fun setShared(family: Boolean) = viewModelScope.launch {
        val id = memoId ?: return@launch
        val spaces = repo.db.spaceDao().all()
        val target = if (family) {
            spaces.firstOrNull { it.type == "family" }?.id
        } else {
            spaces.firstOrNull { it.type == "personal" }?.id
        } ?: return@launch
        repo.setSpace(id, target)
    }

    fun setReminder(at: Long?) = viewModelScope.launch {
        memoId?.let {
            repo.setReminder(it, at)
            container.reminderScheduler.rescheduleAll()
        }
    }

    fun addImage(uri: Uri, displayName: String, mime: String) = viewModelScope.launch {
        val id = memoId ?: return@launch
        val context = getApplication<Application>()
        try {
            val src = File(context.cacheDir, "pick-${System.currentTimeMillis()}.bin")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                src.outputStream().use { ins.copyTo(it) }
            } ?: return@launch
            repo.addImage(id, src, displayName, mime.ifBlank { "image/jpeg" })
            src.delete()
        } catch (_: Exception) {
            // 选取的图片无法读取时静默失败（保留编辑内容）
        }
    }

    fun removeAttachment(sha: String) = viewModelScope.launch {
        val id = memoId ?: return@launch
        val row = repo.db.memoDao().byId(id) ?: return@launch
        val list = row.attachmentList.filterNot { it.sha256 == sha }
        repo.updateMemo(id) { it.copy(attachments = com.family.memo.data.local.encodeAttachments(list)) }
    }
}
