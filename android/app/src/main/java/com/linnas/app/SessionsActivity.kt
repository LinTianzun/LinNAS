package com.linnas.app

import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val etOld = EditText(this).apply { hint = "当前密码"; setSingleLine() }
        val etNew = EditText(this).apply { hint = "新密码"; setSingleLine() }
        box.addView(etOld)
        box.addView(etNew)
        AlertDialog.Builder(this).setTitle("修改我的密码").setView(box)
            .setPositiveButton("保存") { _, _ ->
                val old = etOld.text.toString()
                val new = etNew.text.toString()
                if (old.isBlank() || new.length < 4) { toast("请填写当前密码,新密码至少 4 位"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.changeMyPassword(old, new) }
                        toast("密码已修改")
                    } catch (e: Exception) { toast("修改失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun load() {
        clearContent()
        addText("加载中…")
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { client.sessions() }
            } catch (e: Exception) {
                clearContent(); addText("读取失败: ${e.message}"); return@launch
            }
            clearContent()
            if (list.isEmpty()) { addText("无活跃会话"); return@launch }
            addText("共 ${list.size} 个会话 · 点按可吊销")
            for (s in list) {
                addRow(
                    title = "${s.username} · ${roleLabel(s.role)}" + if (s.current) " · 当前" else "",
                    subtitle = "ID ${s.id} · IP ${s.ip} · 最近 ${formatTs(s.lastSeen)}",
                    onClick = { confirmRevoke(s) },
                )
            }
        }
    }

    private fun confirmRevoke(s: SessionInfo) {
        AlertDialog.Builder(this)
            .setTitle("吊销会话")
            .setMessage("吊销「${s.username}」在 ${s.ip} 上的会话?" + if (s.current) "\n\n注意:这是当前设备的会话,吊销后需要重新登录。" else "")
            .setPositiveButton("吊销") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.revokeSession(s.id) }
                        toast("已吊销")
                        load()
                    } catch (e: Exception) { toast("失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
