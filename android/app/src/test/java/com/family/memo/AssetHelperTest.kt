package com.family.memo

import com.family.memo.core.AssetHelper
import com.family.memo.core.ContentCodec
import com.family.memo.core.MemoContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class AssetHelperTest {

    /** 固定"今天"：2026-09-28 15:30 本地时间。 */
    private fun fixedNow(): Long = Calendar.getInstance().apply {
        set(2026, 8, 28, 15, 30, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun date(y: Int, m: Int, d: Int): Long = Calendar.getInstance().apply {
        set(y, m - 1, d, 10, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `daysLeft 按日历天计算与时钟时刻无关`() {
        val expires = date(2026, 10, 1)
        // 到期日当天（哪怕深夜 23 点）→ 0（今天到期）
        val expiryDayLateNight = Calendar.getInstance().apply {
            set(2026, 9, 1, 23, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(0L, AssetHelper.daysLeft(expires, expiryDayLateNight))
        // 9-28 → 10-1 = 3 天
        assertEquals(3L, AssetHelper.daysLeft(expires, fixedNow()))
        // 过去 → 负数
        assertEquals(-2L, AssetHelper.daysLeft(date(2026, 9, 26), fixedNow()))
    }

    @Test
    fun `countdown 文案与级别`() {
        val now = fixedNow()
        assertEquals(0, AssetHelper.countdown(date(2026, 12, 1), now).second) // 远期
        assertEquals(1, AssetHelper.countdown(date(2026, 10, 1), now).second) // 7 天内
        assertEquals(1, AssetHelper.countdown(date(2026, 9, 28), now).second) // 今天
        assertEquals(2, AssetHelper.countdown(date(2026, 9, 20), now).second) // 已过期
        assertEquals("今天到期", AssetHelper.countdown(date(2026, 9, 28), now).first)
    }

    @Test
    fun `computeRemindAt 提前N天的早上9点`() {
        val expires = date(2026, 10, 1)
        val remind = AssetHelper.computeRemindAt(expires, 7)!!
        val cal = Calendar.getInstance().apply { timeInMillis = remind }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(8, cal.get(Calendar.MONTH)) // 9月
        assertEquals(24, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        // 到期当天提醒 = 当天 9:00
        val sameDay = AssetHelper.computeRemindAt(expires, 0)!!
        val cal2 = Calendar.getInstance().apply { timeInMillis = sameDay }
        assertEquals(1, cal2.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal2.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `computeRemindAt 不提醒返回 null`() {
        val expires = date(2026, 10, 1)
        assertNull(AssetHelper.computeRemindAt(expires, AssetHelper.BEFORE_NONE))
    }

    @Test
    fun `资产内容 JSON 往返且与旧内容兼容`() {
        val c = MemoContent(
            type = "asset", category = "video", account = "13800138000",
            password = "vip-pass", extra = "妈妈开的", expiresAt = 1798761600000L, remindBefore = 3,
        )
        val back = ContentCodec.decode(ContentCodec.encode(c))
        assertEquals(c, back)
        // 旧的 note 内容（无资产字段）解析不崩溃，字段取默认值
        val oldNote = ContentCodec.decode("""{"type":"note","text":"hello"}""")
        assertEquals("hello", oldNote.text)
        assertEquals("", oldNote.account)
        assertEquals(7, oldNote.remindBefore)
        // 未知字段忽略
        val unknown = ContentCodec.decode("""{"type":"note","text":"x","future_field":123}""")
        assertEquals("x", unknown.text)
    }

    @Test
    fun `DatePicker UTC 毫秒与本地零点互转稳定`() {
        val local = AssetHelper.localMidnight(date(2026, 10, 1))
        val utc = AssetHelper.localMidnightToDateUtc(local)
        val back = AssetHelper.dateToLocalMidnight(utc)
        assertEquals(local, back)
    }
}
