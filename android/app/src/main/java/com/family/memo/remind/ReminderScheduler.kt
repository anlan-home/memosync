package com.family.memo.remind

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.family.memo.MemoApp
import com.family.memo.core.ContentCodec
import com.family.memo.data.local.MemoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 精确闹钟调度：每条备忘录可设多个提醒时间（remind_ats）。
 * 已排程闹钟登记在 SharedPreferences——用户删掉某个提醒时间后，对应闹钟会被取消（防幽灵提醒）。
 */
class ReminderScheduler(private val context: Context) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("scheduled_alarms", Context.MODE_PRIVATE)

    private val am: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 后台异步重排全部提醒（UI/Worker/Boot 都调这个）。 */
    fun rescheduleAllAsync() {
        com.family.memo.sync.WorkersScope.launch { rescheduleAll() }
    }

    suspend fun rescheduleAll() {
        val repo = (context.applicationContext as MemoApp).container.repository
        val now = System.currentTimeMillis()
        val desired = HashMap<String, PendingIntent>()
        val upcoming = repo.db.memoDao().activeOnce()
        for (memo in upcoming) {
            val text = ContentCodec.plainText(memo.content)
            for (at in memo.remindAtList) {
                if (at > now) {
                    desired[alarmKey(memo.id, at)] = buildPendingIntent(memo.id, at, memo.title.ifBlank { "备忘提醒" }, text)
                }
            }
        }
        // 取消不再需要的旧闹钟
        val prev = prefs.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()
        for (key in prev) {
            if (!desired.containsKey(key)) {
                am.cancel(buildRawPendingIntent(key))
            }
        }
        // 排程新的
        for ((key, pi) in desired) {
            val at = key.substringAfter('|').toLongOrNull() ?: continue
            scheduleExact(pi, at)
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, desired.keys).apply()
    }

    private fun scheduleExact(pi: PendingIntent, at: Long) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun alarmKey(memoId: String, at: Long) = "$memoId|$at"

    private fun pendingIntent(memoId: String, at: Long, title: String, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra("memo_id", memoId)
            putExtra("title", title)
            putExtra("text", text)
            putExtra("at", at)
        }
        return PendingIntent.getBroadcast(
            context,
            keyHash(memoId, at),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 用于取消：内容可空，只要求 requestCode/intent filterEqual 一致。 */
    private fun buildRawPendingIntent(key: String): PendingIntent {
        val memoId = key.substringBefore('|')
        val at = key.substringAfter('|').toLongOrNull() ?: 0L
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_REMIND }
        return PendingIntent.getBroadcast(
            context, keyHash(memoId, at), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: PendingIntent.getBroadcast(
            context, keyHash(memoId, at), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildPendingIntent(memoId: String, at: Long, title: String, text: String): PendingIntent =
        pendingIntent(memoId, at, title, text)

    private fun keyHash(memoId: String, at: Long): Int = "$memoId#$at".hashCode()

    companion object {
        const val ACTION_REMIND = "com.family.memo.REMIND"
        private const val KEY_SCHEDULED = "keys"
    }
}

/** 闹钟触发 → 发通知。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        val id = intent.getStringExtra("memo_id") ?: return
        val title = intent.getStringExtra("title") ?: "备忘提醒"
        val text = intent.getStringExtra("text") ?: ""
        Notifications.showMemoReminder(context, id, title, text)
    }
}

/** 开机后补排全部提醒（闹钟不跨重启）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            com.family.memo.sync.WorkersScope.launch {
                try {
                    (context.applicationContext as MemoApp).container.reminderScheduler.rescheduleAll()
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
