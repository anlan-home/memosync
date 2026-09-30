package com.family.memo.widget

import com.family.memo.core.ContentCodec
import com.family.memo.data.local.MemoEntity

/** 小组件的数据选择逻辑（纯函数，便于 JVM 测试）。 */
object WidgetData {

    data class Row(val key: String, val text: String, val done: Boolean)

    /**
     * 选定清单小组件展示的备忘录：
     * 用户通过长按菜单「设为小组件内容」指定的优先；否则取最近更新的清单。
     */
    fun resolveChecklist(pinnedId: String, memos: List<MemoEntity>): MemoEntity? =
        memos.firstOrNull { it.id == pinnedId && it.type == "checklist" && it.deletedAt == null }
            ?: memos.filter { it.type == "checklist" && it.deletedAt == null && !it.archived }
                .maxByOrNull { it.updatedAt }

    fun resolveNote(pinnedId: String, memos: List<MemoEntity>): MemoEntity? =
        memos.firstOrNull { it.id == pinnedId && it.type == "note" && it.deletedAt == null }
            ?: memos.filter { it.type == "note" && it.deletedAt == null && !it.archived }
                .maxByOrNull { it.updatedAt }

    /** 清单 → 小组件行（最多 8 条，未完成的排前面）。 */
    fun rows(memo: MemoEntity): List<Row> {
        val c = ContentCodec.decode(memo.content)
        return c.items.take(8).mapIndexed { i, it -> Row("$i", it.text, it.done) }
    }

    fun headerText(memo: MemoEntity): String =
        memo.title.ifBlank { ContentCodec.plainText(memo.content, 12).ifBlank { "清单" } }
}
