package com.family.memo.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.family.memo.data.api.AttachmentDto
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AttachmentEntry(
    val sha256: String,
    val filename: String,
    val mime: String,
    val size: Long,
) {
    fun toDto() = AttachmentDto(id = sha256, sha256 = sha256, filename = filename, mime = mime, size = size)

    companion object {
        fun fromDto(d: AttachmentDto) = AttachmentEntry(d.sha256, d.filename, d.mime, d.size)
    }
}

private val attJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeAttachments(list: List<AttachmentEntry>): String = attJson.encodeToString(list)

fun decodeAttachments(raw: String): List<AttachmentEntry> = try {
    attJson.decodeFromString<List<AttachmentEntry>>(raw)
} catch (_: Exception) {
    emptyList()
}

fun encodeTags(list: List<String>): String = attJson.encodeToString(list)

fun decodeTags(raw: String): List<String> = try {
    attJson.decodeFromString<List<String>>(raw)
} catch (_: Exception) {
    emptyList()
}

fun encodeAts(list: List<Long>): String = attJson.encodeToString(list)

fun decodeAts(raw: String): List<Long> = try {
    attJson.decodeFromString<List<Long>>(raw)
} catch (_: Exception) {
    emptyList()
}

/**
 * 备忘录本地行。同步相关字段：
 *  version  服务端已知版本（0=从未同步成功）
 *  dirty    有待推送修改
 *  conflict 是冲突副本（被覆盖一方的保全内容）
 */
@Entity(tableName = "memos")
data class MemoEntity(
    @PrimaryKey val id: String,
    val creatorId: String = "",
    val spaceId: String,
    val type: String = "note", // note | checklist
    val title: String = "",
    val content: String = "{}",
    val color: String = "default",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val remindAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val clientMtime: Long = 0,
    val deletedAt: Long? = null,
    val version: Long = 0,
    val dirty: Boolean = false,
    val conflict: Boolean = false,
    val attachments: String = "[]",
    val tags: String = "[]",
    val remindAts: String = "[]",
) {
    val attachmentList: List<AttachmentEntry> get() = decodeAttachments(attachments)
    val tagList: List<String> get() = decodeTags(tags)
    val remindAtList: List<Long> get() = decodeAts(remindAts)
}

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // family | personal
)

/** 待上传附件（选图后先落 pending 目录，同步 Worker 依次上传）。 */
@Entity(tableName = "pending_uploads")
data class PendingUploadEntity(
    @PrimaryKey val sha256: String,
    val filePath: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val createdAt: Long,
)

@Dao
interface MemoDao {
    @Query("SELECT * FROM memos WHERE deletedAt IS NULL AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<MemoEntity>>

    @Query("SELECT * FROM memos WHERE deletedAt IS NULL AND archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<MemoEntity>>

    @Query("SELECT * FROM memos WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<MemoEntity>>

    @Query("SELECT * FROM memos WHERE deletedAt IS NULL AND remindAt IS NOT NULL AND remindAt > :now ORDER BY remindAt ASC")
    fun observeUpcomingReminders(now: Long): Flow<List<MemoEntity>>

    @Query("SELECT * FROM memos WHERE deletedAt IS NULL AND remindAt IS NOT NULL AND remindAt > :now ORDER BY remindAt ASC")
    suspend fun upcomingReminders(now: Long): List<MemoEntity>

    @Query("SELECT * FROM memos WHERE id = :id")
    suspend fun byId(id: String): MemoEntity?

    @Query("SELECT * FROM memos WHERE id = :id")
    fun observeById(id: String): Flow<MemoEntity?>

    @Query("SELECT * FROM memos WHERE dirty = 1")
    suspend fun dirty(): List<MemoEntity>

    @Query("UPDATE memos SET dirty = 1 WHERE id = :id")
    suspend fun markDirty(id: String)

    @Query("UPDATE memos SET dirty = 0, version = :version, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markClean(id: String, version: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memo: MemoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memos: List<MemoEntity>)

    @Query("DELETE FROM memos WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT COUNT(*) FROM memos WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT * FROM memos")
    suspend fun allOnce(): List<MemoEntity>

    @Query("SELECT * FROM memos WHERE deletedAt IS NULL")
    suspend fun activeOnce(): List<MemoEntity>
}

@Dao
interface SpaceDao {
    @Query("SELECT * FROM spaces")
    fun observeAll(): Flow<List<SpaceEntity>>

    @Query("SELECT * FROM spaces")
    suspend fun all(): List<SpaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(spaces: List<SpaceEntity>)

    @Query("DELETE FROM spaces")
    suspend fun clear()
}

@Dao
interface PendingUploadDao {
    @Query("SELECT * FROM pending_uploads ORDER BY createdAt")
    suspend fun all(): List<PendingUploadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PendingUploadEntity)

    @Query("DELETE FROM pending_uploads WHERE sha256 = :sha")
    suspend fun delete(sha: String)

    @Query("SELECT * FROM pending_uploads WHERE sha256 = :sha")
    suspend fun bySha(sha: String): PendingUploadEntity?
}

@Database(entities = [MemoEntity::class, SpaceEntity::class, PendingUploadEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao
    abstract fun spaceDao(): SpaceDao
    abstract fun pendingUploadDao(): PendingUploadDao

    companion object {
        /** v1→v2：备忘录表新增标签列。 */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** v2→v3：备忘录表新增多条提醒时间列。 */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN remindAts TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val ALL = arrayOf<androidx.room.migration.Migration>(MIGRATION_1_2, MIGRATION_2_3)
    }
}
