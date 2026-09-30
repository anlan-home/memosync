package com.family.memo.sync

import com.family.memo.core.Ids
import com.family.memo.data.api.ChangeDto
import com.family.memo.data.api.CheckRespDto
import com.family.memo.data.api.MemoDto
import com.family.memo.data.api.PullRespDto
import com.family.memo.data.api.PushInDto
import com.family.memo.data.api.PushRespDto
import com.family.memo.data.api.UploadRespDto

/**
 * 同步引擎：纯 Kotlin 逻辑，不依赖 Android 类，便于 JVM 单元测试。
 *
 * 协议（与 docs/方案设计.md §4 一致）：
 *  1. 上传待传附件（sha256 秒传检查 → 缺失才上传）
 *  2. push 本地 dirty 备忘录（带 base_version 乐观锁）
 *     - ok     → 记录服务端版本，清除 dirty
 *     - conflict → 本地内容另存冲突副本（作为新备忘录再推一次），本地行接受服务端状态
 *  3. pull 增量（changes 游标）：跳过本地仍 dirty 的条目（其将走 push 冲突流程），其余覆盖入库
 */
class SyncEngine(
    private val local: LocalSyncStore,
    private val remote: RemoteSyncApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun syncNow(): SyncReport {
        val report = SyncReport()
        uploadPendingAttachments(report)
        pushDirty(report)
        pullIncremental(report)
        local.onSyncFinished()
        return report
    }

    private suspend fun uploadPendingAttachments(report: SyncReport) {
        for (p in local.pendingUploads()) {
            try {
                val data = local.readPendingFile(p)
                if (data == null) {
                    report.warnings += "附件文件缺失：${p.filename}"
                    local.removePendingUpload(p.sha256)
                } else {
                    val existing = remote.checkExisting(listOf(p.sha256))
                    if (!existing.existing.containsKey(p.sha256)) {
                        remote.upload(p.sha256, data, p.filename, p.mime)
                    }
                    local.promotePendingToCache(p.sha256, data)
                    report.uploads += p.filename
                }
            } catch (e: Exception) {
                report.warnings += "附件上传失败：${p.filename}（${e.message}）"
                throw e // 离线或服务器故障：本次同步终止，待下次重试
            }
        }
    }

    private suspend fun pushDirty(report: SyncReport) {
        val dirty = local.dirtyMemos()
        if (dirty.isEmpty()) return
        val resp: PushRespDto = remote.push(dirty.map { m -> PushInDto(m.toDto(), m.version) })
        var conflictsCreated = false
        for (r in resp.results) {
            val localRow = local.memoById(r.id) ?: continue
            when (r.status) {
                "ok" -> {
                    local.markClean(r.id, r.version, r.memo?.updatedAt ?: clock())
                    report.pushed++
                }
                "conflict" -> {
                    // 本地内容与服务端不同时，保全本地编辑为冲突副本（新 id、继续 dirty，下一轮推送）
                    val differs = r.memo == null ||
                        localRow.title != r.memo.title || localRow.content != r.memo.content
                    if (differs && !localRow.conflict) {
                        local.insertConflictCopy(localRow)
                        conflictsCreated = true
                        report.conflicts++
                    }
                    if (r.memo != null) {
                        local.applyServerMemo(r.memo)
                    } else {
                        // 服务端已无此条目（被彻底清理）：保留副本，删除原行
                        local.hardDelete(r.id)
                    }
                }
                else -> {
                    report.warnings += r.message ?: "推送被拒绝"
                    report.errors++
                }
            }
        }
        if (conflictsCreated) pushDirty(report) // 冲突副本再推一次（新 id base=0）
    }

    private suspend fun pullIncremental(report: SyncReport) {
        var since = local.cursor()
        // 单次最多拉 5 页，避免异常情况下的死循环
        repeat(5) {
            val resp: PullRespDto = remote.pull(since, 200)
            for (ch in resp.changes) {
                applyChange(ch, report)
            }
            local.setCursor(resp.nextCursor)
            since = resp.nextCursor
            report.pulled += resp.changes.size
            if (resp.nextCursor >= resp.latestSeq || resp.changes.isEmpty()) return
        }
    }

    private suspend fun applyChange(ch: ChangeDto, report: SyncReport) {
        val m = ch.memo
        if (m == null) {
            // 物理清理墓碑：本地一并删除（含回收站）
            local.hardDelete(ch.memoId)
            return
        }
        val localRow = local.memoById(m.id)
        if (localRow?.dirty == true) {
            // 本地有未推送修改：不覆盖，等 push 走冲突流程
            return
        }
        local.applyServerMemo(m)
        // 下载缺失的附件缓存
        for (att in m.attachments) {
            if (!local.hasCachedAttachment(att.sha256)) {
                try {
                    val data = remote.download(att.sha256)
                    local.saveAttachment(att.sha256, data)
                } catch (e: Exception) {
                    report.warnings += "附件下载失败：${att.filename}（${e.message}）"
                }
            }
        }
    }

    data class SyncReport(
        var pushed: Int = 0,
        var pulled: Int = 0,
        var conflicts: Int = 0,
        var errors: Int = 0,
        val uploads: MutableList<String> = mutableListOf(),
        val warnings: MutableList<String> = mutableListOf(),
    )
}

/** 本地存储抽象（实现见 MemoRepository）。 */
interface LocalSyncStore {
    suspend fun dirtyMemos(): List<com.family.memo.data.local.MemoEntity>
    suspend fun memoById(id: String): com.family.memo.data.local.MemoEntity?
    suspend fun applyServerMemo(dto: MemoDto)
    suspend fun markClean(id: String, version: Long, updatedAt: Long)
    suspend fun hardDelete(id: String)
    suspend fun insertConflictCopy(src: com.family.memo.data.local.MemoEntity)
    suspend fun cursor(): Long
    suspend fun setCursor(v: Long)
    suspend fun pendingUploads(): List<com.family.memo.data.local.PendingUploadEntity>
    suspend fun readPendingFile(p: com.family.memo.data.local.PendingUploadEntity): ByteArray?
    suspend fun removePendingUpload(sha: String)
    suspend fun promotePendingToCache(sha: String, data: ByteArray)
    suspend fun hasCachedAttachment(sha: String): Boolean
    suspend fun saveAttachment(sha: String, data: ByteArray)
    suspend fun onSyncFinished()
}

/** 远端 API 抽象（实现见 MemoRepository / 远端网关）。 */
interface RemoteSyncApi {
    suspend fun push(inputs: List<PushInDto>): PushRespDto
    suspend fun pull(since: Long, limit: Int): PullRespDto
    suspend fun upload(sha: String, data: ByteArray, filename: String, mime: String): UploadRespDto
    suspend fun checkExisting(shas: List<String>): CheckRespDto
    suspend fun download(sha: String): ByteArray
}

/** MemoEntity → 传输 DTO。 */
fun com.family.memo.data.local.MemoEntity.toDto() = MemoDto(
    id = id,
    creatorId = creatorId,
    spaceId = spaceId,
    type = type,
    title = title,
    content = content,
    color = color,
    pinned = pinned,
    archived = archived,
    remindAt = remindAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    clientMtime = if (clientMtime > 0) clientMtime else clock0(),
    deletedAt = deletedAt,
    version = version,
    attachments = attachmentList.map { it.toDto() },
    tags = tagList,
    remindAts = remindAtList,
)

private fun clock0() = System.currentTimeMillis()
