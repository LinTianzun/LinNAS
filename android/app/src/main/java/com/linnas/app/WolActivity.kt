package com.linnas.app

import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v1.7.0 网络唤醒(WoL):保存主机(MAC)并发送魔术包开机。 */
class WolActivity : ScreenScaffold() {

    override fun title() = "网络唤醒"

    override fun onCreateScreen() {
        addHeaderAction("添加") { showAdd() }
        addHeaderAction("刷新") { load() }
        load()
    }

    private fun load() {
        clearContent()
        addText("加载中…")
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { client.wolList() }
            } catch (e: Exception) {
                clearContent(); addText("读取失败: ${e.message}"); return@launch
            }
            clearContent()
            if (list.isEmpty()) { addText("还没有保存的唤醒主机,点右上角「添加」。"); return@launch }
            addText("共 ${list.size} 台 · 点按发送唤醒包,长按删除")
            for (h in list) {
                addRow(
                    title = h.name,
                    subtitle = h.mac,
                    onClick = { send(h) },
                    onLongClick = { confirmDelete(h) },
                )
            }
        }
    }

    private fun showAdd() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val etName = EditText(this).apply { hint = "名称(如 客厅电脑)"; setSingleLine() }
        val etMac = EditText(this).apply { hint = "MAC(如 AA:BB:CC:DD:EE:FF)"; setSingleLine() }
        box.addView(etName)
        box.addView(etMac)
        AlertDialog.Builder(this).setTitle("添加唤醒主机").setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val mac = etMac.text.toString().trim()
                if (mac.isBlank()) { toast("请填写 MAC"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.wolAdd(name, mac) }
                        toast("已保存")
                        load()
                    } catch (e: Exception) { toast("保存失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun send(h: WolHost) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.wolSend(h.mac) }
                toast("已向 ${h.mac} 发送唤醒包")
            } catch (e: Exception) {
                toast("发送失败: ${e.message}")
            }
        }
    }

    private fun confirmDelete(h: WolHost) {
        AlertDialog.Builder(this).setTitle("删除主机").setMessage("删除「${h.name}」?")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.wolDelete(h.id) }
                        toast("已删除")
                        load()
                    } catch (e: Exception) { toast("删除失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }
}
