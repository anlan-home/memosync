package com.family.memo.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateSpaceReqDto(val name: String)

@Serializable
data class SpaceMemberDto(
    @SerialName("user_id") val userId: String,
    val username: String = "",
    val nickname: String = "",
    val role: String = "member",
)

@Serializable
data class SpaceMembersRespDto(val members: List<SpaceMemberDto> = emptyList())

@Serializable
data class AddMemberReqDto(val username: String)

@Serializable
data class NotifyConfigDto(
    @SerialName("feishu_webhook") val feishuWebhook: String = "",
    @SerialName("dingtalk_webhook") val dingtalkWebhook: String = "",
    @SerialName("dingtalk_secret") val dingtalkSecret: String = "",
)
