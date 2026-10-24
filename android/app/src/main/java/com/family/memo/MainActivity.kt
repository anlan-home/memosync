package com.family.memo

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.family.memo.data.prefs.Prefs
import com.family.memo.ui.MemoAppRoot
import com.family.memo.ui.auth.LoginScreen
import com.family.memo.ui.editor.EditorScreen
import com.family.memo.ui.theme.MemoTheme
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSetupDeepLink(intent)
        val openMemoId = intent?.getStringExtra("open_memo_id")
        setContent {
            // 深链只消费一次：旋转/重建后不再重复入栈
            var navGuardUsed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            val container = (application as MemoApp).container
            val prefs = container.prefs
            val theme by prefs.theme.collectAsState(initial = "system")
            val fontScale by produceState(1f) {
                value = when (prefs.snapshot().fontSize) {
                    "large" -> 1.15f
                    "xlarge" -> 1.3f
                    else -> 1f
                }
            }
            val elderMode by prefs.elderMode.collectAsState(initial = false)
            val openMemoIdOnce = if (navGuardUsed) null else openMemoId
            // 启动路由只决定一次：动态改变 startDestination 会让 NavHost 重建后丢失目的地（黑屏）
            var startRoute by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (startRoute == null) {
                    startRoute = if (prefs.snapshot().token.isNotBlank()) "main" else "login"
                }
            }

            MemoTheme(theme) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.platform.LocalDensity.current.let {
                        androidx.compose.ui.unit.Density(it.density, fontScale * if (elderMode) 1.15f else 1f)
                    },
                ) {
                    val nav = rememberNavController()
                    val start = startRoute
                    if (start == null) {
                        // 启动瞬间：铺一层页面底色，避免黑屏闪烁
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                        )
                        return@CompositionLocalProvider
                    }
                    NavHost(nav, startDestination = start) {
                        composable("login") {
                            LoginScreen(
                                onDone = {
                                    nav.navigate("main") { popUpTo("login") { inclusive = true } }
                                },
                            )
                        }
                        composable("main") {
                            MemoAppRoot(
                                openMemoId = openMemoIdOnce?.also { navGuardUsed = true },
                                onOpenEditor = { id -> nav.navigate("editor/$id") },
                                onOpenAsset = { id -> nav.navigate("asset/$id") },
                            )
                        }
                        composable("editor/{memoId}") { entry ->
                            val memoId = entry.arguments?.getString("memoId") ?: return@composable
                            EditorScreen(
                                memoId = memoId,
                                onClose = { nav.popBackStack() },
                            )
                        }
                        composable("asset/{memoId}") { entry ->
                            val memoId = entry.arguments?.getString("memoId") ?: return@composable
                            com.family.memo.ui.assets.AssetEditorScreen(
                                memoId = memoId,
                                onClose = { nav.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSetupDeepLink(intent)
    }

    /**
     * 深链接配置服务器地址：familymemo://setup?primary=http://192.168.1.10:5231&alt=http://100.64.0.2:5231
     * 用于快速接入（也可把这段链接做成二维码）。
     */
    private fun handleSetupDeepLink(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "familymemo" || data.host != "setup") return
        val primary = data.getQueryParameter("primary").orEmpty()
        val alt = data.getQueryParameter("alt").orEmpty()
        if (primary.isBlank() && alt.isBlank()) return
        val app = application as MemoApp
        lifecycleScope.launch {
            app.container.prefs.setEndpoints(normalize(primary), normalize(alt))
            app.container.syncWorkerRequest()
        }
    }

    private fun normalize(raw: String): String {
        val v = raw.trim().trimEnd('/')
        if (v.isEmpty()) return ""
        return if (v.startsWith("http")) v else "http://$v"
    }
}
