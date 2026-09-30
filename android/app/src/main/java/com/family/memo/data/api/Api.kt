package com.family.memo.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 与服务端 API 的 JSON 一一对应（snake_case 用 @SerialName 映射）

@Serializable
data class AttachmentDto(
    val id: String = "",
    @SerialName("sha256") val sha256: String,
    val filename: String = "",
    val mime: String = "application/octet-stream",
    val size: Long = 0,
)

@Serializable
data class MemoDto(
    val id: String,
    @SerialName("creator_id") val creatorId: String = "",
    @SerialName("space_id") val spaceId: String,
    val type: String = "note",
    val title: String = "",
    val content: String = "{}",
    val color: String = "default",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    @SerialName("remind_at") val remindAt: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("client_mtime") val clientMtime: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    val version: Long = 0,
    val attachments: List<AttachmentDto> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("remind_ats") val remindAts: List<Long> = emptyList(),
)

@Serializable
data class PushInDto(
    val memo: MemoDto,
    @SerialName("base_version") val baseVersion: Long,
)

@Serializable
data class PushResultDto(
    val id: String,
    val status: String,
    val message: String? = null,
    val version: Long = 0,
    val memo: MemoDto? = null,
)

@Serializable
data class PushReqDto(val memos: List<PushInDto>)

@Serializable
data class PushRespDto(
    val results: List<PushResultDto> = emptyList(),
    @SerialName("latest_seq") val latestSeq: Long = 0,
)

@Serializable
data class ChangeDto(
    val seq: Long,
    @SerialName("memo_id") val memoId: String = "",
    val deleted: Boolean = false,
    val memo: MemoDto? = null,
)

@Serializable
data class PullRespDto(
    val changes: List<ChangeDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long = 0,
    @SerialName("latest_seq") val latestSeq: Long = 0,
)

@Serializable
data class SpaceDto(val id: String, val name: String, val type: String)

@Serializable
data class SpacesRespDto(val spaces: List<SpaceDto> = emptyList())

@Serializable
data class UserDto(
    val id: String = "",
    val username: String = "",
    val nickname: String = "",
    val role: String = "",
)

@Serializable
data class LoginReqDto(val username: String, val password: String, @SerialName("device_name") val deviceName: String)

@Serializable
data class PairReqDto(val code: String, @SerialName("device_name") val deviceName: String)

@Serializable
data class LoginRespDto(val token: String, val user: UserDto)

@Serializable
data class UploadRespDto(val id: String, @SerialName("sha256") val sha256: String, val size: Long)

@Serializable
data class CheckReqDto(val sha256s: List<String>)

@Serializable
data class CheckRespDto(val existing: Map<String, Long> = emptyMap())

@Serializable
data class PasswordReqDto(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class OkRespDto(val ok: String? = null)
