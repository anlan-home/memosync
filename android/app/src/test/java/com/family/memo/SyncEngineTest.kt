package com.family.memo

import com.family.memo.core.ContentCodec
import com.family.memo.data.api.AttachmentDto
import com.family.memo.data.api.ChangeDto
import com.family.memo.data.api.CheckRespDto
import com.family.memo.data.api.MemoDto
import com.family.memo.data.api.PullRespDto
import com.family.memo.data.api.PushInDto
import com.family.memo.data.api.PushRespDto
import com.family.memo.data.api.PushResultDto
import com.family.memo.data.api.UploadRespDto
import com.family.memo.data.local.AttachmentEntry
import com.family.memo.data.local.MemoEntity
import com.family.memo.data.local.PendingUploadEntity
import com.family.memo.data.local.decodeAttachments
import com.family.memo.data.local.encodeAttachments
import com.family.memo.sync.LocalSyncStore
import com.family.memo.sync.RemoteSyncApi
import com.family.memo.sync.SyncEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存版本地库：完全按接口语义实现。 */
class FakeLocal : LocalSyncStore {
    val rows = LinkedHashMap<String, MemoEntity>()
    val pending = LinkedHashMap<String, PendingUploadEntity>()
    val cachedBlobs = HashMap<String, ByteArray>()
    private var cur = 0L
    var syncFinishedCalls = 0

    fun put(m: MemoEntity) { rows[m.id] = m }

    override suspend fun dirtyMemos(): List<MemoEntity> = rows.values.filter { it.dirty }
    override suspend fun memoById(id: String): MemoEntity? = rows[id]

    override suspend fun applyServerMemo(dto: MemoDto) {
        val existing = rows[dto.id]
        rows[dto.id] = MemoEntity(
            id = dto.id,
            creatorId = dto.creatorId,
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
            attachments = encodeAttachments(dto.attachments.map { AttachmentEntry.fromDto(it) }),
        )
    }

    override suspend fun markClean(id: String, version: Long, updatedAt: Long) {
        rows[id]?.let { rows[id] = it.copy(dirty = false, version = version, updatedAt = updatedAt) }
    }

    override suspend fun hardDelete(id: String) { rows.remove(id) }

    override suspend fun insertConflictCopy(src: MemoEntity) {
        val copy = src.copy(
            id = "copy-of-${src.id}",
            version = 0,
            dirty = true,
            conflict = true,
            deletedAt = null,
            clientMtime = 999L,
        )
        rows[copy.id] = copy
    }

    override suspend fun cursor(): Long = cur
    override suspend fun setCursor(v: Long) { cur = v }

    override suspend fun pendingUploads(): List<PendingUploadEntity> = pending.values.toList()
    override suspend fun readPendingFile(p: PendingUploadEntity): ByteArray? = "data-of-${p.sha256}".toByteArray()
    override suspend fun removePendingUpload(sha: String) { pending.remove(sha) }

    override suspend fun promotePendingToCache(sha: String, data: ByteArray) {
        cachedBlobs[sha] = data
        pending.remove(sha)
    }

    override suspend fun hasCachedAttachment(sha: String): Boolean = cachedBlobs.containsKey(sha)
    override suspend fun saveAttachment(sha: String, data: ByteArray) { cachedBlobs[sha] = data }
    override suspend fun resetLocalCache() {
        // 清空已同步数据，保留待推送的本地编辑
        rows.values.filter { !it.dirty }.map { it.id }.forEach { rows.remove(it) }
    }

    override suspend fun onSyncFinished() { syncFinishedCalls++ }
}

/** 内存版远端：模拟服务端版本链与冲突判定。 */
class FakeRemote : RemoteSyncApi {
    val server = LinkedHashMap<String, MemoDto>() // id → 当前服务端状态
    val blobs = HashMap<String, ByteArray>()
    private var seq = 0L
    var downloadCalls = 0

    fun putServer(m: MemoDto) {
        server[m.id] = m.copy(version = m.version)
        seq++
    }

    override suspend fun push(inputs: List<PushInDto>): PushRespDto {
        val results = inputs.map { input ->
            val m = input.memo
            val cur = server[m.id]
            when {
                cur == null && input.baseVersion == 0L -> {
                    val stored = m.copy(version = 1, updatedAt = 100L)
                    server[m.id] = stored
                    seq++
                    PushResultDto(id = m.id, status = "ok", version = 1, memo = stored)
                }
                cur == null -> PushResultDto(id = m.id, status = "conflict", memo = null)
                input.baseVersion == cur.version -> {
                    val stored = m.copy(version = cur.version + 1, updatedAt = 200L)
                    server[m.id] = stored
                    seq++
                    PushResultDto(id = m.id, status = "ok", version = stored.version, memo = stored)
                }
                else -> PushResultDto(id = m.id, status = "conflict", memo = cur)
            }
        }
        return PushRespDto(results, latestSeq = seq)
    }

    override suspend fun pull(since: Long, limit: Int): PullRespDto {
        // 简化：服务端把全部状态作为一页变更返回（seq 按 id 序），支持游标断点
        val ids = server.keys.sorted()
        val changes = ids.mapIndexed { i, id ->
            ChangeDto(seq = (i + 1).toLong(), memoId = id, deleted = server[id]!!.deletedAt != null, memo = server[id])
        }.filter { it.seq > since }
        val page = changes.take(limit)
        val next = page.lastOrNull()?.seq ?: since
        val latest = (ids.size).toLong()
        return PullRespDto(changes = page, nextCursor = next, latestSeq = latest)
    }

    override suspend fun upload(sha: String, data: ByteArray, filename: String, mime: String): UploadRespDto {
        blobs.putIfAbsent(sha, data)
        return UploadRespDto(sha, sha, data.size.toLong())
    }

    override suspend fun checkExisting(shas: List<String>): CheckRespDto =
        CheckRespDto(shas.filter { blobs.containsKey(it) }.associateWith { 1L })

    override suspend fun download(sha: String): ByteArray {
        downloadCalls++
        return blobs[sha] ?: throw IllegalStateException("no blob")
    }
}

private fun memo(
    id: String,
    title: String = "t",
    version: Long = 0,
    dirty: Boolean = false,
    content: String = ContentCodec.encode(com.family.memo.core.MemoContent(text = "正文")),
    deletedAt: Long? = null,
    spaceId: String = "fam",
    attachments: List<AttachmentEntry> = emptyList(),
) = MemoEntity(
    id = id, spaceId = spaceId, type = "note", title = title, content = content,
    clientMtime = 1L, updatedAt = 1L, createdAt = 1L, version = version, dirty = dirty,
    deletedAt = deletedAt, attachments = encodeAttachments(attachments),
)

private fun dto(m: MemoEntity) = m.let {
    MemoDto(
        id = it.id, spaceId = it.spaceId, type = it.type, title = it.title,
        content = it.content, deletedAt = it.deletedAt, version = it.version,
        clientMtime = it.clientMtime, attachments = it.attachmentList.map { a -> a.toDto() },
    )
}

class SyncEngineTest {

    @Test
    fun `新备忘录推送成功并清除 dirty`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        local.put(memo("m1", title = "WiFi", dirty = true))
        val report = SyncEngine(local, remote).syncNow()
        assertEquals(1, report.pushed)
        assertFalse(local.rows["m1"]!!.dirty)
        assertEquals(1L, local.rows["m1"]!!.version)
        assertEquals("WiFi", remote.server["m1"]!!.title)
    }

    @Test
    fun `冲突时保留本地副本并接受服务端版本`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        // 服务端已有 v2（家人改过）
        remote.putServer(dto(memo("m1", title = "服务端标题", version = 2)).copy(version = 2))
        // 本地仍是基于 v1 的未推送修改
        local.put(memo("m1", title = "我的本地编辑", version = 1, dirty = true))

        val report = SyncEngine(local, remote).syncNow()

        assertEquals(1, report.conflicts)
        // 原行接受服务端状态
        assertEquals("服务端标题", local.rows["m1"]!!.title)
        assertEquals(2L, local.rows["m1"]!!.version)
        assertFalse(local.rows["m1"]!!.dirty)
        // 本地编辑保全为冲突副本，且副本已推送成功
        val copy = local.rows["copy-of-m1"]!!
        assertTrue(copy.conflict)
        assertFalse(copy.dirty) // 第二轮推送已成功
        assertEquals("我的本地编辑", remote.server["copy-of-m1"]!!.title)
    }

    @Test
    fun `内容相同时冲突不生成副本`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        val same = memo("m1", title = "一致", version = 1)
        remote.putServer(dto(same).copy(version = 2))
        local.put(memo("m1", title = "一致", version = 1, dirty = true))
        // 注意：dirty 行 base=1 与服务端 v2 冲突，但内容与 v2 相同（重复编辑）
        val report = SyncEngine(local, remote).syncNow()
        assertEquals(0, report.conflicts)
        assertNull(local.rows["copy-of-m1"])
    }

    @Test
    fun `pull 下发他人变更与删除墓碑`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        remote.putServer(dto(memo("m2", title = "家人新建")))
        remote.putServer(dto(memo("m3", title = "被删除的", deletedAt = 123L)))

        SyncEngine(local, remote).syncNow()

        assertEquals("家人新建", local.rows["m2"]!!.title)
        assertNotNull(local.rows["m3"]!!.deletedAt) // 进回收站而非消失
        assertTrue(local.cursor() > 0)
    }

    @Test
    fun `物理清理墓碑导致本地硬删`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        local.put(memo("gone", title = "曾被清理"))
        // 服务端 pull 返回 memo=null 的墓碑
        val engine = SyncEngine(local, object : RemoteSyncApi by remote {
            override suspend fun pull(since: Long, limit: Int) = PullRespDto(
                changes = listOf(ChangeDto(seq = 1, memoId = "gone", deleted = true, memo = null)),
                nextCursor = 1,
                latestSeq = 1,
            )
        })
        engine.syncNow()
        assertNull(local.rows["gone"])
    }

    @Test
    fun `本地 dirty 的行不被 pull 覆盖`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        remote.putServer(dto(memo("m1", title = "服务端新标题")))
        local.put(memo("m1", title = "我还没推的编辑", version = 1, dirty = true))

        SyncEngine(local, remote).syncNow()

        // 先 push（冲突→内容不同→副本），原行接受服务端
        assertEquals("服务端新标题", local.rows["m1"]!!.title)
        assertNotNull(local.rows["copy-of-m1"])
    }

    @Test
    fun `待传附件先上传再推送备忘录`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        val sha = "a".repeat(64)
        local.pending[sha] = PendingUploadEntity(sha, "/tmp/x", "photo.png", "image/png", 4, 0)
        local.put(
            memo("m1", dirty = true, attachments = listOf(AttachmentEntry(sha, "photo.png", "image/png", 4))),
        )

        val report = SyncEngine(local, remote).syncNow()

        assertTrue(remote.blobs.containsKey(sha))
        assertTrue(local.cachedBlobs.containsKey(sha)) // 已提升为本地缓存
        assertTrue(local.pending.isEmpty())
        assertEquals("photo.png", remote.server["m1"]!!.attachments.first().filename)
        assertEquals(1, report.pushed)
    }

    @Test
    fun `拉取时下载缺失的附件缓存`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        val sha = "b".repeat(64)
        remote.blobs[sha] = "IMG".toByteArray()
        remote.putServer(
            dto(memo("m1")).copy(
                attachments = listOf(AttachmentDto(sha, sha, "img.png", "image/png", 3)),
            ),
        )

        SyncEngine(local, remote).syncNow()

        assertEquals("IMG".toByteArray().decodeToString(), local.cachedBlobs[sha]!!.decodeToString())
        assertEquals(1, remote.downloadCalls)
    }

    @Test
    fun `游标推进且不重复拉取`() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        remote.putServer(dto(memo("m1", title = "一次")))
        SyncEngine(local, remote).syncNow()
        val afterFirst = local.cursor()
        assertEquals(1L, afterFirst)
        val report = SyncEngine(local, remote).syncNow() // 第二次无增量
        assertEquals(0, report.pulled)
    }

    @Test
    fun `附件上传失败时中止本轮同步且保留待传`() = runTest {
        val local = FakeLocal()
        val remote = object : RemoteSyncApi by FakeRemote() {
            override suspend fun checkExisting(shas: List<String>) = CheckRespDto(emptyMap())
            override suspend fun upload(sha: String, data: ByteArray, filename: String, mime: String): UploadRespDto =
                throw java.io.IOException("网络中断")
        }
        val sha = "c".repeat(64)
        local.pending[sha] = PendingUploadEntity(sha, "/tmp/x", "p.png", "image/png", 4, 0)
        local.put(memo("m1", dirty = true, attachments = listOf(AttachmentEntry(sha, "p.png", "image/png", 4))))
        try {
            SyncEngine(local, remote).syncNow()
        } catch (_: java.io.IOException) {
        }
        // 待传附件必须保留（下次重试），备忘录也不能被误标为已同步
        assertTrue(local.pending.containsKey(sha))
        assertTrue(local.rows["m1"]!!.dirty)
    }

    @Test
    fun `服务端持续返回冲突时不失控且最终收敛`() = runTest {
        val local = FakeLocal()
        // 异常服务端：任何推送都返回 conflict 且带 memo（最坏情况）
        val remote = object : RemoteSyncApi by FakeRemote() {
            var calls = 0
            override suspend fun push(inputs: List<PushInDto>): PushRespDto {
                calls++
                if (calls > 20) throw IllegalStateException("push 调用失去控制: $calls")
                return PushRespDto(
                    inputs.map { i ->
                        PushResultDto(
                            id = i.memo.id,
                            status = "conflict",
                            memo = dto(memo(i.memo.id, title = "服务端标题", version = 9)),
                        )
                    },
                    latestSeq = 0,
                )
            }
        }
        local.put(memo("m1", title = "我的编辑", version = 1, dirty = true))
        val report = SyncEngine(local, remote).syncNow()

        // 冲突副本只生成一层：conflict 标记阻止"副本的副本"
        assertTrue("应至少发生一次冲突", report.conflicts >= 1)
        // 推送次数有限（同步能收敛，不会挂死）
        assertTrue("推送调用次数应有限", remote.calls <= 3)
        // 所有本地行最终都不再 dirty（不会无限重试）
        assertTrue("同步后不应残留 dirty 行: ${local.rows}", local.rows.values.none { it.dirty })
    }

    @Test
    fun `游标失效时重建本地缓存但保留待推送编辑`() = runTest {
        val local = FakeLocal()
        val remote = object : RemoteSyncApi by FakeRemote() {
            // 推送被服务端拒绝：本地编辑仍然是 dirty（模拟"推不上去"的最坏情况）
            override suspend fun push(inputs: List<PushInDto>) = PushRespDto(
                inputs.map { PushResultDto(id = it.memo.id, status = "error", message = "暂时无法写入") },
                latestSeq = 0,
            )

            override suspend fun pull(since: Long, limit: Int) = PullRespDto(
                changes = listOf(
                    ChangeDto(seq = 1, memoId = "server-a", deleted = false, memo = dto(memo("server-a", title = "服务端A"))),
                    ChangeDto(seq = 2, memoId = "server-b", deleted = false, memo = dto(memo("server-b", title = "服务端B"))),
                ),
                nextCursor = 2,
                latestSeq = 2,
                resetRequired = true,
            )
        }
        // 本地有一条旧的已同步数据和一条待推送编辑
        local.put(memo("stale", title = "过期缓存"))
        local.put(memo("mine", title = "我没推的编辑", dirty = true))

        val report = SyncEngine(local, remote).syncNow()

        assertTrue("应提示重建: ${report.warnings}", report.warnings.any { it.contains("重建") })
        assertNull("过期缓存应被清除", local.rows["stale"])
        assertNotNull("待推送编辑必须保留", local.rows["mine"])
        assertTrue("保留的行仍 dirty", local.rows["mine"]!!.dirty)
        assertEquals("服务端A", local.rows["server-a"]?.title)
        assertEquals("服务端B", local.rows["server-b"]?.title)
        assertEquals(2L, local.cursor())
    }

    @Test
    fun `内容编解码往返`() {
        val c = com.family.memo.core.MemoContent(
            type = "checklist",
            items = listOf(com.family.memo.core.CheckItem("牛奶", true), com.family.memo.core.CheckItem("鸡蛋")),
        )
        val raw = ContentCodec.encode(c)
        val back = ContentCodec.decode(raw)
        assertEquals(c, back)
        assertEquals("牛奶、鸡蛋", ContentCodec.plainText(raw))
        // 坏内容不崩溃
        assertEquals(com.family.memo.core.MemoContent(), ContentCodec.decode("not-json"))
    }
}
