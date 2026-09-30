package com.family.memo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.family.memo.MainActivity
import com.family.memo.MemoApp
import com.family.memo.R
import com.family.memo.core.ContentCodec
import kotlinx.coroutines.runBlocking

/** 笔记小组件：常驻桌面显示最近笔记（或指定的笔记），作为速览便签。 */
class NoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAll(context, mgr)
    }

    companion object {
        fun refreshAll(context: Context, mgr: AppWidgetManager) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, NoteWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as MemoApp
            val memos = runBlocking { app.container.repository.db.memoDao().activeOnce() }
            val pinned = context.getSharedPreferences("widget_pref", Context.MODE_PRIVATE)
                .getString("note_id", "") ?: ""
            val memo = WidgetData.resolveNote(pinned, memos)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_note).apply {
                    if (memo == null) {
                        setTextViewText(R.id.widget_note_title, "笔记")
                        setTextViewText(R.id.widget_note_text, "在应用里记一条笔记吧")
                    } else {
                        val c = ContentCodec.decode(memo.content)
                        setTextViewText(R.id.widget_note_title, memo.title.ifBlank { "笔记" })
                        setTextViewText(
                            R.id.widget_note_text,
                            c.text.ifBlank { "（清单内容请在清单小组件查看）" },
                        )
                    }
                    val open = PendingIntent.getActivity(
                        context, 100000 + id,
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            memo?.let { putExtra("open_memo_id", it.id) }
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    setOnClickPendingIntent(R.id.widget_note_root, open)
                }
                mgr.updateAppWidget(id, views)
            }
        }
    }
}
