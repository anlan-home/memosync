package com.family.memo

import com.family.memo.core.ContentCodec
import com.family.memo.core.CustomField
import com.family.memo.core.MemoContent
import com.family.memo.data.local.decodeAts
import com.family.memo.data.local.encodeAts
import com.family.memo.data.local.MemoEntity
import com.family.memo.widget.WidgetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewFeatureTest {

    private fun memo(
        id: String,
        type: String,
        title: String = "t",
        items: List<com.family.memo.core.CheckItem> = emptyList(),
        archived: Boolean = false,
    ) = MemoEntity(
        id = id, spaceId = "f", type = type, title = title,
        content = ContentCodec.encode(MemoContent(type = type, items = items)),
        clientMtime = 1, updatedAt = items.size.toLong() + 1, deletedAt = null, archived = archived,
    )

    @Test
    fun `小组件内容选择逻辑`() {
        val checkA = memo("a", "checklist", items = listOf(com.family.memo.core.CheckItem("x")))
        val checkB = memo("b", "checklist", items = listOf(com.family.memo.core.CheckItem("y"), com.family.memo.core.CheckItem("z")))
        val note = memo("n", "note")
        val memos = listOf(checkA, note, checkB) // b 最新（updatedAt 更大）

        // 未指定 → 最近更新的清单
        assertEquals("b", WidgetData.resolveChecklist("", memos)?.id)
        // 指定 a → a
        assertEquals("a", WidgetData.resolveChecklist("a", memos)?.id)
        // 指定的不存在/类型不符 → 回退最近
        assertEquals("b", WidgetData.resolveChecklist("n", memos)?.id)
        // 笔记小组件
        assertEquals("n", WidgetData.resolveNote("", memos)?.id)
        // 已删除的不选
        val deleted = listOf(checkA.copy(deletedAt = 1L))
        assertNull(WidgetData.resolveChecklist("", deleted))
    }

    @Test
    fun `小组件行最多 8 条`() {
        val items = (1..12).map { com.family.memo.core.CheckItem("项目$it") }
        val rows = WidgetData.rows(memo("a", "checklist", items = items))
        assertEquals(8, rows.size)
    }

    @Test
    fun `资产自定义字段序列化往返`() {
        val c = MemoContent(
            type = "asset",
            fields = listOf(CustomField("网址", "https://example.com"), CustomField("会员等级", "年卡")),
        )
        val back = ContentCodec.decode(ContentCodec.encode(c))
        assertEquals(c, back)
        // 旧内容无字段 → 默认空
        assertEquals(0, ContentCodec.decode("""{"type":"asset","account":"a"}""").fields.size)
    }

    @Test
    fun `多条提醒时间编解码`() {
        val raw = encodeAts(listOf(300L, 100L, 200L))
        assertEquals(listOf(300L, 100L, 200L), decodeAts(raw)) // 保持客户端给定顺序（服务端才排序）
        assertEquals(emptyList<Long>(), decodeAts("[]"))
        assertEquals(emptyList<Long>(), decodeAts("broken"))
        // 实体辅助列
        val m = MemoEntity(id = "x", spaceId = "f", remindAts = raw)
        assertEquals(listOf(300L, 100L, 200L), m.remindAtList)
    }
}
