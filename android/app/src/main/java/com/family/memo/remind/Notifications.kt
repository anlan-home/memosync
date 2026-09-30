package com.family.memo.remind

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.family.memo.MainActivity
import com.family.memo.R

object Notifications {
    const val CHANNEL_REMIND = "reminders"
    private const val BASE_ID = 42000

    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_REMIND,
            "备忘录提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "到点的备忘录提醒通知"
        }
        nm.createNotificationChannel(ch)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showMemoReminder(context: Context, memoId: String, title: String, text: String) {
        if (!hasPermission(context)) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_memo_id", memoId)
        }
        val pi = PendingIntent.getActivity(
            context, memoId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_REMIND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏰ $title")
            .setContentText(text.ifBlank { "时间到了，点开看看" })
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify((BASE_ID + memoId.hashCode()).let { if (it < 0) -it else it }, n)
    }
}
