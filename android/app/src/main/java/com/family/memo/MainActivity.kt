package com.family.memo

import android.os.Bundle
import androidx.activity.ComponentActivity
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


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val loggedIn by prefs.loggedIn.collectAsState(initial = null)
            val openMemoIdOnce = if (navGuardUsed) null else openMemoId

            MemoTheme(theme) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.platform.LocalDensity.current.let {
                        androidx.compose.ui.unit.Density(it.density, fontScale * if (elderMode) 1.15f else 1f)
                    },
                ) {
                    val nav = rememberNavController()
                    val start = loggedIn?.let { if (it) "main" else "login" } ?: return@CompositionLocalProvider
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
}
