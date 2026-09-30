package com.family.memo.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.family.memo.MemoApp
import com.family.memo.data.api.toApiError
import java.util.concurrent.TimeUnit

/** WorkManager 后台同步任务。 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MemoApp
        val repo = app.container.repository
        return try {
            val report = app.container.syncEngine.syncNow()
            app.container.prefs.setSyncResult(System.currentTimeMillis(), null)
            app.container.reminderScheduler.rescheduleAllAsync()
            if (report.warnings.isNotEmpty()) {
                app.container.prefs.setSyncResult(System.currentTimeMillis(), report.warnings.first())
            }
            Result.success()
        } catch (e: Exception) {
            val err = e.toApiError()
            app.container.prefs.setSyncResult(System.currentTimeMillis(), err.message)
            // 离线或服务器 5xx：指数退避重试；鉴权等 4xx：不重试（等用户操作）
            if (err.offline) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val ONE_TIME = "memosync-sync-once"
        private const val PERIODIC = "memosync-sync-periodic"

        /** 立即同步一次（去重：排队的请求合并）。 */
        fun requestNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, req)
        }

        /** 应用启动时安装 30 分钟兜底周期同步。 */
        fun ensurePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
