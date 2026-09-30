package com.family.memo.data.repo

import android.content.Context
import com.family.memo.data.api.ApiClient
import com.family.memo.data.api.MemoApi
import com.family.memo.data.api.PushInDto
import com.family.memo.data.local.AppDatabase
import com.family.memo.data.local.AttachmentEntry
import com.family.memo.data.local.MemoEntity
import com.family.memo.data.local.PendingUploadEntity
import com.family.memo.data.prefs.Prefs
import com.family.memo.sync.RemoteSyncApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 远端网关：Retrofit + DataStore token + 附件缓存目录，实现 RemoteSyncApi。 */
class RemoteGateway(
    private val prefs: Prefs,
    private val filesDir: File,
) : RemoteSyncApi {

    /** 供空间管理等低频操作直接使用 API 客户端。 */
    suspend fun client(): MemoApi {
        val s = prefs.snapshot()
        if (s.baseUrl.isBlank()) throw com.family.memo.data.api.ApiError("尚未配置服务器地址", offline = true)
        return ApiClient.create(s.baseUrl) { s.token }
    }

    override suspend fun push(inputs: List<PushInDto>) = client().push(com.family.memo.data.api.PushReqDto(inputs))

    override suspend fun pull(since: Long, limit: Int) = client().pull(since, limit)

    override suspend fun checkExisting(shas: List<String>) = client().check(com.family.memo.data.api.CheckReqDto(shas))

    override suspend fun upload(sha: String, data: ByteArray, filename: String, mime: String): com.family.memo.data.api.UploadRespDto =
        withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("up-", ".bin", filesDir)
            try {
                tmp.writeBytes(data)
                client().upload(com.family.memo.data.api.buildFilePart(tmp, mime, "file"))
            } finally {
                tmp.delete()
            }
        }

    override suspend fun download(sha: String): ByteArray = withContext(Dispatchers.IO) {
        client().downloadRaw(sha).bytes()
    }
}

/**
 * 备忘录仓库：LocalSyncStore 的真实实现 + UI 侧全部读写操作。
 * Room 是唯一事实源，UI 永远只订阅 Room Flow；同步引擎通过本类读写。
 */
class MemoRepository(
    private val context: Context,
    val db: AppDatabase,
    private val prefs: Prefs,
) : com.family.memo.sync.LocalSyncStore {

    private val filesDir get() = context.filesDir
    private val cacheDir get() = File(filesDir, "attachments")
    private val pendingDir get() = File(filesDir, "pending")

    fun initDirs() {
        cacheDir.mkdirs()
        pendingDir.mkdirs()
    }

    fun attachmentFile(sha: String): File = File(cacheDir, sha)

    /** 附件缓存根目录（供 UI 图片加载）。 */
    fun attachmentCacheDir(): File = cacheDir

    // ---------- LocalSyncStore ----------

    override suspend fun dirtyMemos(): List<MemoEntity> = db.memoDao().dirty()

    override suspend fun memoById(id: String): MemoEntity? = db.memoDao().byId(id)

    override suspend fun applyServerMemo(dto: com.family.memo.data.api.MemoDto) {
        val existing = db.memoDao().byId(dto.id)
        db.memoDao().upsert(
            MemoEntity(
                id = dto.id,
                creatorId = dto.creatorId.ifBlank { existing?.creatorId ?: "" },
                spaceId = dto.spaceId,
                type = dto.type,
                title = dto.title,
                content = dto.content,
                color = dto.color,
                pinned = dto.pinned,
                archived = dto.archived,
                remindAt = dto.remindAt,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
                clientMtime = dto.clientMtime,
                deletedAt = dto.deletedAt,
                version = dto.version,
                dirty = false,
                conflict = existing?.conflict ?: false,
                attachments = com.family.memo.data.local.encodeAttachments(dto.attachments.map { AttachmentEntry.fromDto(it) }),
                tags = com.family.memo.data.local.encodeTags(dto.tags),
                remindAts = com.family.memo.data.local.encodeAts(
                    dto.remindAts.ifEmpty { dto.remindAt?.let { listOf(it) } ?: emptyList() },
                ),
            ),
        )
        notifyWidgets()
    }

    override suspend fun markClean(id: String, version: Long, updatedAt: Long) =
        db.memoDao().markClean(id, version, updatedAt)

    override suspend fun hardDelete(id: String) = db.memoDao().hardDelete(id)

    override suspend fun insertConflictCopy(src: MemoEntity) {
        val copy = src.copy(
            id = com.family.memo.core.Ids.newUuid(),
            version = 0,
            dirty = true,
            conflict = true,
            title = (if (src.title.isBlank()) "无标题" else src.title),
            deletedAt = null,
            clientMtime = System.currentTimeMillis(),
        )
        db.memoDao().upsert(copy)
    }

    override suspend fun cursor(): Long = prefs.snapshot().cursor

    override suspend fun setCursor(v: Long) = prefs.setCursor(v)

    override suspend fun pendingUploads(): List<PendingUploadEntity> = db.pendingUploadDao().all()

    override suspend fun readPendingFile(p: PendingUploadEntity): ByteArray? =
        withContext(Dispatchers.IO) { File(p.filePath).takeIf { it.exists() }?.readBytes() }

    override suspend fun removePendingUpload(sha: String) = db.pendingUploadDao().delete(sha)

    override suspend fun promotePendingToCache(sha: String, data: ByteArray) = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        File(cacheDir, sha).writeBytes(data)
        Unit
    }

    override suspend fun hasCachedAttachment(sha: String): Boolean =
        withContext(Dispatchers.IO) { attachmentFile(sha).exists() }

    override suspend fun saveAttachment(sha: String, data: ByteArray) = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        File(cacheDir, sha).writeBytes(data)
        Unit
    }

    override suspend fun onSyncFinished() { /* 提醒重排由 SyncWorker 调 ReminderScheduler */ }

    // ---------- UI 操作 ----------

    /** 新建备忘录（本地立即生效，dirty 待同步）。 */
    suspend fun createMemo(spaceId: String, type: String): MemoEntity {
        val now = System.currentTimeMillis()
        val m = MemoEntity(
            id = com.family.memo.core.Ids.newUuid(),
            spaceId = spaceId,
            type = type,
            content = if (type == "checklist") {
                com.family.memo.core.ContentCodec.encode(com.family.memo.core.MemoContent(type = "checklist", items = mutableListOf()))
            } else {
                com.family.memo.core.ContentCodec.encode(com.family.memo.core.MemoContent(type = "note", text = ""))
            },
            createdAt = now,
            updatedAt = now,
            clientMtime = now,
            version = 0,
            dirty = true,
        )
        db.memoDao().upsert(m)
        requestSync()
        notifyWidgets()
        return m
    }

    suspend fun updateMemo(id: String, patch: (MemoEntity) -> MemoEntity) {
        val cur = db.memoDao().byId(id) ?: return
        val now = System.currentTimeMillis()
        db.memoDao().upsert(patch(cur).copy(dirty = true, clientMtime = now, updatedAt = now, version = cur.version))
        requestSync()
        notifyWidgets()
    }

    suspend fun setTitleAndContent(id: String, title: String, content: String) = updateMemo(id) {
        it.copy(title = title, content = content)
    }

    suspend fun setColor(id: String, color: String) = updateMemo(id) { it.copy(color = color) }

    suspend fun setPinned(id: String, pinned: Boolean) = updateMemo(id) { it.copy(pinned = pinned) }

    suspend fun setArchived(id: String, archived: Boolean) = updateMemo(id) { it.copy(archived = archived) }

    suspend fun setSpace(id: String, spaceId: String) = updateMemo(id) { it.copy(spaceId = spaceId) }

    suspend fun setReminder(id: String, remindAt: Long?) = updateMemo(id) { it.copy(remindAt = remindAt) }

    /** 软删除：进回收站，同步后家庭成员都会看到。 */
    suspend fun trash(id: String) = updateMemo(id) { it.copy(deletedAt = System.currentTimeMillis()) }

    suspend fun restore(id: String) = updateMemo(id) { it.copy(deletedAt = null) }

    /** 彻底删除（回收站内）：只清本地。
     *  家庭范围的删除由软删墓碑 + 服务端 30 天物理清理完成，
     *  这里清除的是「我设备上的回收站条目」。 */
    suspend fun deleteForever(id: String) {
        db.memoDao().hardDelete(id)
    }

    /** 清单操作。 */
    suspend fun toggleCheckItem(id: String, index: Int) {
        val cur = db.memoDao().byId(id) ?: return
        val c = com.family.memo.core.ContentCodec.decode(cur.content).copy(type = "checklist")
        if (index !in c.items.indices) return
        val items = c.items.toMutableList()
        items[index] = items[index].copy(done = !items[index].done)
        setTitleAndContent(id, cur.title, com.family.memo.core.ContentCodec.encode(c.copy(items = items)))
    }

    suspend fun addCheckItem(id: String, text: String) {
        val cur = db.memoDao().byId(id) ?: return
        val c = com.family.memo.core.ContentCodec.decode(cur.content).copy(type = "checklist")
        setTitleAndContent(id, cur.title, com.family.memo.core.ContentCodec.encode(c.copy(items = c.items + com.family.memo.core.CheckItem(text))))
    }

    suspend fun removeCheckItem(id: String, index: Int) {
        val cur = db.memoDao().byId(id) ?: return
        val c = com.family.memo.core.ContentCodec.decode(cur.content).copy(type = "checklist")
        if (index !in c.items.indices) return
        val items = c.items.toMutableList()
        items.removeAt(index)
        setTitleAndContent(id, cur.title, com.family.memo.core.ContentCodec.encode(c.copy(items = items)))
    }

    /** 添加图片：复制进 pending 目录，登记待上传，并把引用写进备忘录。 */
    suspend fun addImage(id: String, source: File, displayName: String, mime: String): Boolean =
        withContext(Dispatchers.IO) {
            val cur = db.memoDao().byId(id) ?: return@withContext false
            val sha = sha256Of(source) ?: return@withContext false
            pendingDir.mkdirs()
            val dest = File(pendingDir, sha)
            if (!dest.exists()) source.copyTo(dest, overwrite = true)
            db.pendingUploadDao().upsert(
                PendingUploadEntity(
                    sha256 = sha, filePath = dest.absolutePath,
                    filename = displayName, mime = mime, size = dest.length(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            val list = cur.attachmentList.toMutableList()
            if (list.none { it.sha256 == sha }) {
                list += AttachmentEntry(sha256 = sha, filename = displayName, mime = mime, size = dest.length())
            }
            val now = System.currentTimeMillis()
            db.memoDao().upsert(
                cur.copy(
                    attachments = com.family.memo.data.local.encodeAttachments(list),
                    dirty = true, clientMtime = now, updatedAt = now,
                ),
            )
            requestSync()
            true
        }

    /** 数据变化后刷新桌面小组件。 */
    private fun notifyWidgets() {
        com.family.memo.sync.WorkersScope.launch { com.family.memo.widget.WidgetRefresher.refreshAll(context) }
    }

    /** 同步请求入口：交给 SyncScheduler（WorkManager）。 */
    var syncRequester: (() -> Unit) = {}

    fun requestSync() {
        syncRequester()
    }

    companion object {
        fun sha256Of(file: File): String? = try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
