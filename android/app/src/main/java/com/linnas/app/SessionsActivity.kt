package com.linnas.app

import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v1.7.0 活跃会话管理:列出登录会话,可吊销(非管理员只能吊销自己的);并修改本人密码。 */
class SessionsActivity : ScreenScaffold() {

    override fun title() = "会话管理"

    override fun onCreateScreen() {
        addHeaderAction("改密码") { showChangePassword() }
        addHeaderAction("刷新") { load() }
        load()
    }

    /** 修改当前登录用户的密码(/api/users/password,任何登录用户可用)。 */
    private fun showChangePassword() {
        val sheet = Sheet(this, "修改我的密码")
        sheet.input("当前密码", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.input("新密码", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.primary("保存") {
            val old = sheet.inputView(0)?.text?.toString().orEmpty()
            val new = sheet.inputView(1)?.text?.toString().orEmpty()
            if (old.isBlank() || new.length < 4) {
                sheet.inputView(1)?.error = "请填写当前密码,新密码至少 4 位"
                toast("请填写当前密码,新密码至少 4 位")
                return@primary
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.changeMyPassword(old, new) }
                    sheet.dismiss()
                    toast("密码已修改")
                } catch (e: Exception) { toast("修改失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun load() {
        clearContent()
        addLoading()
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { client.sessions() }
            } catch (e: Exception) {
                clearContent()
                addEmptyState("读取失败", e.message ?: "无法获取会话列表")
                return@launch
            }
            clearContent()
            if (list.isEmpty()) {
                addEmptyState("没有活跃会话", "当前没有设备登录到服务器")
                return@launch
            }

            val current = list.filter { it.current }
            val others = list.filter { !it.current }

            if (current.isNotEmpty()) {
                addSectionHeader("当前会话")
                for (s in current) addSessionRow(s)
            }
            if (others.isNotEmpty()) {
                addSectionHeader("其他会话")
                for (s in others) addSessionRow(s)
            }
            addText("共 ${list.size} 个会话 · 点按可吊销")
        }
    }

    /** 一条会话卡片行。 */
    private fun addSessionRow(s: SessionInfo) {
        val subtitle = buildString {
            append("IP ").append(s.ip.ifBlank { "—" })
            append("  ·  最近 ").append(formatTs(s.lastSeen))
            append("  ·  ID ").append(s.id)
        }
        addIconRow(
            iconRes = if (s.current) R.drawable.ic_user else R.drawable.ic_devices,
            title = "${s.username} · ${roleLabel(s.role)}",
            subtitle = subtitle,
            value = if (s.current) "本机" else "",
            tintAttr = if (s.current) R.attr.lPrimary else R.attr.lTextDim,
            onClick = { confirmRevoke(s) },
        )
    }

    private fun confirmRevoke(s: SessionInfo) {
        val sheet = Sheet(
            this,
            "吊销会话",
            "吊销「${s.username}」在 ${s.ip} 上的会话?" + if (s.current) "\n\n注意:这是当前设备的会话,吊销后需要重新登录。" else "",
        )
        sheet.danger("吊销") {
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.revokeSession(s.id) }
                    toast("已吊销")
                    load()
                } catch (e: Exception) { toast("失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }
}
