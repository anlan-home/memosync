package com.family.memo

import com.family.memo.core.CheckItem
import com.family.memo.core.ContentCodec
import com.family.memo.core.MemoContent
import com.family.memo.core.RelativeTime
import com.family.memo.data.local.MemoEntity
import com.family.memo.widget.WidgetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class EdgeCaseTest {

    private fun calendar(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance().apply {
            set(y, m - 1, d, h, min, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun checklistMemo(vararg items: Pair<String, Boolean>): MemoEntity = MemoEntity(
        id = "cl", spaceId = "f", type = "checklist", title = "清单",
        content = ContentCodec.encode(
            MemoContent(
                type = "checklist",
                items = items.map { CheckItem(it.first, it.second) },
            ),
        ),
        clientMtime = 1, updatedAt = 1,
    )

    // ---------- 小组件：勾选必须命中真实条目 ----------

    @Test
    fun `小组件行携带真实条目索引（未完成排前也不会错位）`() {
        // 第 0 条已完成，第 1、2 条未完成
        val memo = checklistMemo("已买牛奶" to true, "买鸡蛋" to false, "买纸巾" to false)
        val rows = WidgetData.rows(memo)
        assertEquals(3, rows.size)
        // 未完成应排在前面，但仍指向原始 items 索引
        assertEquals("买鸡蛋", rows[0].text)
        assertEquals(1, rows[0].index)
        assertEquals("买纸巾", rows[1].text)
        assertEquals(2, rows[1].index)
        assertEquals("已买牛奶", rows[2].text)
        assertEquals(0, rows[2].index)
        // 索引必须能正确回查原始条目
        rows.forEach { row ->
            val items = ContentCodec.decode(memo.content).items
            assertEquals(row.text, items[row.index].text)
        }
    }

    @Test
    fun `小组件行只取前 8 条且索引正确`() {
        val items = (0 until 12).map { CheckItem("项目$it", it % 3 == 0) }
        val memo = MemoEntity(
            id = "cl", spaceId = "f", type = "checklist",
            content = ContentCodec.encode(MemoContent(type = "checklist", items = items)),
            clientMtime = 1, updatedAt = 1,
        )
        val rows = WidgetData.rows(memo)
        assertEquals(8, rows.size)
        val all = ContentCodec.decode(memo.content).items
        rows.forEach { assertEquals(it.text, all[it.index].text) }
    }

    // ---------- 时间显示：未来时间不能显示成"刚刚" ----------

    @Test
    fun `未来的提醒时间显示为剩余时间而不是刚刚`() {
        val now = calendar(2026, 9, 28, 15, 0)
        // 1 小时后
        val in1h = now + 3600_000
        val s1 = RelativeTime.format(in1h, now)
        assertTrue("未来 1 小时应显示还剩多久，实际: $s1", s1.contains("后") || s1.contains("明天") || s1.contains(":"))
        // 3 天后
        val in3d = now + 3 * 86_400_000L
        assertTrue("未来 3 天应显示日期或天数，实际: ${RelativeTime.format(in3d, now)}",
            RelativeTime.format(in3d, now).contains("天") || RelativeTime.format(in3d, now).contains("月"))
        // 过去时间仍按原逻辑
        assertEquals("刚刚", RelativeTime.format(now - 5_000, now))
    }

    // ---------- 同步引擎：分页与冲突边界 ----------

    @Test
    fun `内容损坏时列表预览不崩溃`() {
        assertEquals("", ContentCodec.plainText("not json"))
        assertEquals("", ContentCodec.plainText("{}"))
        // 超长文本截断
        val long = ContentCodec.encode(MemoContent(type = "note", text = "字".repeat(500)))
        assertTrue(ContentCodec.plainText(long, 20).length <= 21)
    }

    @Test
    fun `清单内容里混入未知字段仍可解析`() {
        val raw = """{"type":"checklist","items":[{"text":"牛奶","done":true,"extra_unknown":1}],"future":true}"""
        val c = ContentCodec.decode(raw)
        assertEquals(1, c.items.size)
        assertEquals("牛奶", c.items[0].text)
        assertTrue(c.items[0].done)
    }
}
