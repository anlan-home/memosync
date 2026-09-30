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
import kotlinx.coroutines.runBlocking

/** 清单小组件：常驻桌面，条目可直接勾选，作为工作清单使用。 */
class ChecklistWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAll(context, mgr)
    }

    companion object {
        const val ACTION_TOGGLE = "com.family.memo.widget.TOGGLE"

        fun refreshAll(context: Context, mgr: AppWidgetManager) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChecklistWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as MemoApp
            val memos = runBlocking { app.container.repository.db.memoDao().activeOnce() }
            val pinned = context.getSharedPreferences("widget_pref", Context.MODE_PRIVATE)
                .getString("checklist_id", "") ?: ""
            val memo = WidgetData.resolveChecklist(pinned, memos)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_checklist).apply {
                    if (memo == null) {
                        setTextViewText(R.id.widget_title, "清单")
                        setTextViewText(R.id.widget_empty, "在应用里新建一份清单吧")
                        setViewVisibility(R.id.widget_list, android.view.View.GONE)
                        setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
                    } else {
                        setTextViewText(R.id.widget_title, WidgetData.headerText(memo))
                        setViewVisibility(R.id.widget_list, android.view.View.VISIBLE)
                        setViewVisibility(R.id.widget_empty, android.view.View.GONE)
                        setRemoteAdapter(
                            R.id.widget_list,
                            Intent(context, ChecklistWidgetService::class.java).apply {
                                putExtra("memo_id", memo.id)
                            },
                        )
                        // 条目点击模板：index 由每个条目的 fill-in intent 提供
                        val template = PendingIntent.getBroadcast(
                            context, 0,
                            Intent(context, WidgetActionReceiver::class.java).apply {
                                action = ACTION_TOGGLE
                                putExtra("memo_id", memo.id)
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        setPendingIntentTemplate(R.id.widget_list, template)
                    }
                    // 头部点击 → 打开对应备忘录（或应用）
                    val open = PendingIntent.getActivity(
                        context, id,
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            memo?.let { putExtra("open_memo_id", it.id) }
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    setOnClickPendingIntent(R.id.widget_header, open)
                }
                mgr.updateAppWidget(id, views)
                if (memo != null) {
                    mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
                }
            }
        }
    }
}

/** 清单条目列表服务。 */
class ChecklistWidgetService() : android.widget.RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ChecklistWidgetFactory(applicationContext, intent)
}

/** RemoteViewsFactory：读取本地库生成清单条目。 */
class ChecklistWidgetFactory(
    private val context: Context,
    private val intent: Intent,
) : android.widget.RemoteViewsService.RemoteViewsFactory {

    private val memoId: String = intent.getStringExtra("memo_id") ?: ""
    private var rows: List<WidgetData.Row> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val app = context.applicationContext as MemoApp
        val memo = runBlocking { app.container.repository.db.memoDao().byId(memoId) } ?: return
        rows = WidgetData.rows(memo)
    }

    override fun onDestroy() {}
    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_checklist_item)
        val views = RemoteViews(context.packageName, R.layout.widget_checklist_item)
        views.setTextViewText(R.id.widget_item_text, row.text)
        views.setImageViewResource(
            R.id.widget_item_check,
            if (row.done) R.drawable.ic_widget_checked else R.drawable.ic_widget_unchecked,
        )
        // fill-in 提供 index，配合 provider 里的 PendingIntent 模板完成勾选
        views.setOnClickFillInIntent(
            R.id.widget_item_root,
            Intent().putExtra("index", position),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
