package com.family.memo.widget

import com.family.memo.core.ContentCodec
import com.family.memo.data.local.MemoEntity

/** 小组件的数据选择逻辑（纯函数，便于 JVM 测试）。 */
object WidgetData {

    /**
     * 小组件展示的一行。
     * index 是该条目在备忘录 content.items 中的真实下标——勾选动作必须用它，
     * 不能用列表位置（列表会按完成状态重排）。
     */
    data class Row(val index: Int, val text: String, val done: Boolean)

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

    /**
     * 清单 → 小组件行：未完成排前面（保持各自相对顺序），最多 8 条。
     * 每行保留原始索引，保证勾选命中正确条目。
     */
    fun rows(memo: MemoEntity): List<Row> {
        val items = ContentCodec.decode(memo.content).items
        return items.mapIndexed { i, it -> Row(i, it.text, it.done) }
            .sortedBy { if (it.done) 1 else 0 } // 稳定排序：未完成在前
            .take(8)
    }

    fun headerText(memo: MemoEntity): String =
        memo.title.ifBlank { ContentCodec.plainText(memo.content, 12).ifBlank { "清单" } }
}
