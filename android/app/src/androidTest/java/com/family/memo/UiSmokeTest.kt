package com.family.memo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/**
 * 真机 UI 冒烟测试（Compose 语义层，不依赖输入法/坐标）。
 * 需要一台可达的 memosync 服务端；地址通过 -Puitest.server 传入（默认 192.168.5.13:5231）。
 */
class UiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val server = System.getProperty("uitest.server") ?: "http://192.168.5.13:5231"
    private val user = System.getProperty("uitest.user") ?: "admin"
    private val pass = System.getProperty("uitest.pass") ?: "uitest1234"

    private fun waitText(text: String, timeoutMs: Long = 20_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun loginIfNeeded() {
        rule.waitForIdle()
        val onLogin = rule.onAllNodesWithText("NAS 地址", substring = true)
            .fetchSemanticsNodes().isNotEmpty()
        if (!onLogin) return
        val fields = rule.onAllNodes(hasSetTextAction())
        // 顺序：主地址、备用地址、账号、密码
        fields[0].performTextInput(server)
        fields[2].performTextInput(user)
        fields[3].performTextInput(pass)
        rule.onNodeWithText("登 录").performClick()
        waitText("备忘录", 25_000)
    }

    @Test
    fun 登录并验证五个页签() {
        loginIfNeeded()
        listOf("备忘录", "家庭", "清单", "资产", "我的").forEach { tab ->
            rule.onNodeWithText(tab).assertIsDisplayed()
        }
    }

    @Test
    fun 新建笔记并在列表看到卡片() {
        loginIfNeeded()
        val title = "UI测试笔记" + System.currentTimeMillis().toString().takeLast(4)
        rule.onNodeWithText("新建").performClick()
        rule.onNodeWithText("新建笔记").performClick()
        rule.waitForIdle()
        // 编辑器：标题输入框
        val editFields = rule.onAllNodes(hasSetTextAction())
        editFields[0].performTextInput(title)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("返回").performClick()
        waitText(title, 20_000)
        rule.onNodeWithText(title, substring = true).assertIsDisplayed()
    }

    @Test
    fun 资产页可进入并新建() {
        loginIfNeeded()
        rule.onNodeWithText("资产").performClick()
        waitText("记一笔资产")
        rule.onNodeWithText("记一笔资产").performClick()
        rule.waitForIdle()
        val fields = rule.onAllNodes(hasSetTextAction())
        val name = "UI测试资产" + System.currentTimeMillis().toString().takeLast(4)
        fields[0].performTextInput(name)
        rule.onNodeWithContentDescription("返回").performClick()
        waitText(name, 20_000)
    }

    @Test
    fun 设置页显示服务器地址与测试连接() {
        loginIfNeeded()
        rule.onNodeWithText("我的").performClick()
        waitText("设置")
        rule.onNodeWithText("设置").performClick()
        waitText("服务器地址（双地址）")
        rule.onNodeWithText("当前生效：", substring = true).assertIsDisplayed()
        rule.onNodeWithText("测试连接").performClick()
        // 探测完成后应给出结论（可用 / 都连不上）
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("可用：", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("已切换到", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("都连不上", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
