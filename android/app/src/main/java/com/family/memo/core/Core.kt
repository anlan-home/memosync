package com.family.memo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 备忘录正文的 JSON 编解码（服务端只校验 JSON 合法性，结构由客户端定义）。 */
@Serializable
data class CheckItem(val text: String, val done: Boolean = false)

/** 资产自定义字段（如 网址、会员等级、绑定手机）。 */
@Serializable
data class CustomField(val key: String = "", val value: String = "")

/**
 * 备忘录内容。type=note/checklist/asset 三形态共用：
 *  - note:      text
 *  - checklist: items
 *  - asset:     category/account/password/extra/expiresAt/remindBefore/fields（数字资产）
 */
@Serializable
data class MemoContent(
    val type: String = "note",
    val text: String = "",
    val items: List<CheckItem> = emptyList(),
    // ---- 资产字段 ----
    val category: String = "",
    val account: String = "",
    val password: String = "",
    val extra: String = "",
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("remind_before") val remindBefore: Int = 7,
    val fields: List<CustomField> = emptyList(),
)

object ContentCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(c: MemoContent): String = json.encodeToString(c)

    fun decode(raw: String): MemoContent = try {
        json.decodeFromString<MemoContent>(raw)
    } catch (_: Exception) {
        MemoContent() // 损坏内容兜底为空笔记，绝不崩溃
    }

    fun plainText(raw: String, maxLen: Int = 120): String {
        val c = decode(raw)
        val s = when (c.type) {
            "checklist" -> c.items.joinToString("、") { it.text }
            else -> c.text.replace('\n', ' ')
        }
        return if (s.length > maxLen) s.take(maxLen) + "…" else s
    }
}

/** 数字资产辅助：分类、到期倒计时、提醒时间换算。 */
object AssetHelper {
    const val BEFORE_NONE = -1

    val CATEGORIES: List<Pair<String, String>> = listOf(
        "视频" to "video", "音乐" to "music", "云盘" to "cloud",
        "宽带网络" to "network", "软件服务" to "software", "其他" to "other",
    )

    fun categoryLabel(code: String): String = CATEGORIES.firstOrNull { it.second == code }?.first ?: "其他"

    /** 本地时区当日 0 点。 */
    fun localMidnight(ms: Long, now: Long = System.currentTimeMillis()): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = if (ms <= 0) now else ms
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun todayMidnight(now: Long = System.currentTimeMillis()): Long = localMidnight(now, now)

    /** 距到期还有几天（按日历天差）。 */
    fun daysLeft(expiresAt: Long, now: Long = System.currentTimeMillis()): Long =
        (localMidnight(expiresAt) - todayMidnight(now)) / 86_400_000L

    /** 倒计时文案 + 紧急度（0 正常 / 1 临近 / 2 已过期）。 */
    fun countdown(expiresAt: Long, now: Long = System.currentTimeMillis()): Pair<String, Int> {
        val d = daysLeft(expiresAt, now)
        return when {
            d < 0 -> "已过期 ${-d} 天" to 2
            d == 0L -> "今天到期" to 1
            d <= 7L -> "剩余 $d 天" to 1
            else -> "剩余 $d 天" to 0
        }
    }

    fun formatDate(ms: Long): String =
        java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA).format(java.util.Date(ms))

    /**
     * 提醒时间 = 到期日 09:00 往前推 beforeDays 天。
     * beforeDays == BEFORE_NONE → null（不提醒）。
     */
    fun computeRemindAt(expiresAt: Long, beforeDays: Int): Long? {
        if (beforeDays < 0) return null
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = localMidnight(expiresAt)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -beforeDays)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** DatePicker（UTC 语义的选中毫秒）→ 本地当日 0 点。 */
    fun dateToLocalMidnight(utcSelectedMillis: Long): Long {
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = utcSelectedMillis
        val local = java.util.Calendar.getInstance()
        local.clear()
        local.set(
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH),
            utc.get(java.util.Calendar.DAY_OF_MONTH),
            0, 0, 0,
        )
        return local.timeInMillis
    }

    /** 本地 0 点 → DatePicker 的 UTC 选中毫秒。 */
    fun localMidnightToDateUtc(localMidnightMs: Long): Long {
        val local = java.util.Calendar.getInstance()
        local.timeInMillis = localMidnightMs
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(
            local.get(java.util.Calendar.YEAR),
            local.get(java.util.Calendar.MONTH),
            local.get(java.util.Calendar.DAY_OF_MONTH),
            12, 0, 0,
        )
        return utc.timeInMillis
    }
}

/** uuid v4（与服务端同格式，客户端生成 id 避免分配冲突）。 */
object Ids {
    fun newUuid(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
    }
}

/** 相对时间显示：刚刚 / n 分钟前 / HH:mm / 昨天 / M月d日 */
object RelativeTime {
    fun format(epochMilli: Long, now: Long = System.currentTimeMillis()): String {
        val d = now - epochMilli
        return when {
            d < 60_000 -> "刚刚"
            d < 3600_000 -> "${d / 60_000} 分钟前"
            d < 86400_000 -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = epochMilli
                "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            }
            d < 172800_000 -> "昨天"
            else -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = epochMilli
                "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
            }
        }
    }
}
