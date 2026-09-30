package com.family.memo.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.family.memo.sync.WorkersScope
import kotlinx.coroutines.launch

/** 小组件数据刷新：数据变化后由仓库层调用。 */
object WidgetRefresher {
    fun refreshAll(context: Context) {
        WorkersScope.launch {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                ChecklistWidgetProvider.refreshAll(context, mgr)
                NoteWidgetProvider.refreshAll(context, mgr)
            } catch (_: Exception) {
            }
        }
    }
}

/** 小组件上的清单条目勾选/取消动作。 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ChecklistWidgetProvider.ACTION_TOGGLE) return
        val memoId = intent.getStringExtra("memo_id") ?: return
        val index = intent.getIntExtra("index", -1)
        if (index < 0) return
        val pending = goAsync()
        WorkersScope.launch {
            try {
                val repo = (context.applicationContext as com.family.memo.MemoApp).container.repository
                repo.toggleCheckItem(memoId, index)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
/** 小组件内容选择偏好（长按备忘录「设为小组件内容」写入）。 */
object WidgetPrefs {
    private fun sp(context: Context) = context.getSharedPreferences("widget_pref", Context.MODE_PRIVATE)
    fun setChecklistId(context: Context, id: String) { sp(context).edit().putString("checklist_id", id).apply() }
    fun setNoteId(context: Context, id: String) { sp(context).edit().putString("note_id", id).apply() }
    fun checklistId(context: Context): String = sp(context).getString("checklist_id", "") ?: ""
    fun noteId(context: Context): String = sp(context).getString("note_id", "") ?: ""
}
