package com.family.memo

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.family.memo.data.api.ApiError
import com.family.memo.data.local.AppDatabase
import com.family.memo.data.prefs.Prefs
import com.family.memo.data.repo.MemoRepository
import com.family.memo.data.repo.RemoteGateway
import com.family.memo.remind.Notifications
import com.family.memo.remind.ReminderScheduler
import com.family.memo.sync.SyncEngine
import com.family.memo.sync.SyncWorker
import com.family.memo.ui.MemoFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 手工依赖容器（应用规模不需要 DI 框架）。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val prefs = Prefs(appContext)
    val db: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "memo.db")
        .addMigrations(*AppDatabase.ALL)
        .fallbackToDestructiveMigration()
        .build()
    val repository = MemoRepository(appContext, db, prefs)
    val remote = RemoteGateway(prefs, appContext.filesDir)
    val syncEngine = SyncEngine(repository, remote)
    val reminderScheduler = ReminderScheduler(appContext)

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    @Volatile private var monitorCall: okhttp3.Call? = null

    init {
        repository.initDirs()
        MemoFiles.init(repository.attachmentCacheDir())
        repository.syncRequester = { SyncWorker.requestNow(appContext) }
        // 已登录时保持 SSE 实时监听；未登录时循环空转等待
        startEventMonitor()
    }

    /**
     * SSE 实时同步监听：服务端有家庭成员变更 → 立即触发一次同步。
     * 断线指数退避重连（1s → 60s）；登出后取消连接。
     */
    fun startEventMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = monitorScope.launch { eventMonitorLoop() }
    }

    fun stopEventMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        monitorCall?.cancel()
        monitorCall = null
    }

    private suspend fun eventMonitorLoop() {
        var backoff = 1_000L
        // readTimeout=0：SSE 长连接无读超时
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        while (monitorScope.isActive) {
            val snap = prefs.snapshot()
            if (snap.token.isBlank() || snap.baseUrl.isBlank()) {
                delay(5_000)
                continue
            }
            try {
                val req = Request.Builder()
                    .url(snap.baseUrl.trimEnd('/') + "/api/v1/sync/events")
                    .header("Authorization", "Bearer " + snap.token)
                    .build()
                val call = client.newCall(req)
                monitorCall = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw ApiError("SSE HTTP ${resp.code}", resp.code >= 500)
                    val src = resp.body?.source() ?: throw ApiError("SSE 无响应体")
                    backoff = 1_000L
                    while (true) {
                        val line = src.readUtf8Line() ?: break // 服务端关闭
                        if (line.startsWith("event: change")) {
                            SyncWorker.requestNow(appContext)
                        }
                        // "event: ping" 仅保活，无需处理
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return
                // 断线退避重连；离线期间 WorkManager 周期同步兜底
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }
}

class MemoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.initChannels(this)
        SyncWorker.ensurePeriodic(this)
    }
}
