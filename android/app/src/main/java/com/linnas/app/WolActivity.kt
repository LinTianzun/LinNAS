package com.linnas.app

import android.widget.EditText
import android.widget.LinearLayout
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
        addLoading()
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { client.wolList() }
            } catch (e: Exception) {
                clearContent()
                addEmptyState("读取失败", e.message ?: "无法获取唤醒主机列表")
                return@launch
            }
            clearContent()
            if (list.isEmpty()) {
                addEmptyState("还没有保存的唤醒主机", "点右上角「添加」,保存名称与 MAC 地址")
                return@launch
            }

            addSectionHeader("唤醒主机")
            for (h in list) {
                addIconRow(
                    iconRes = R.drawable.ic_wifi,
                    title = h.name.ifBlank { h.mac },
                    subtitle = "MAC 地址",
                    value = h.mac,
                    onClick = { send(h) },
                    onLongClick = { confirmDelete(h) },
                )
            }
            addText("共 ${list.size} 台 · 点按发送唤醒包,长按删除")
        }
    }

    private fun showAdd() {
        val sheet = Sheet(this, "添加唤醒主机")
        sheet.input("名称(如 客厅电脑)")
        sheet.input("MAC(如 AA:BB:CC:DD:EE:FF)")
        sheet.primary("保存") {
            val name = sheet.value(0)
            val mac = sheet.value(1)
            if (mac.isBlank()) {
                sheet.inputView(1)?.error = "请填写 MAC"
                toast("请填写 MAC")
                return@primary
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.wolAdd(name, mac) }
                    sheet.dismiss()
                    toast("已保存")
                    load()
                } catch (e: Exception) { toast("保存失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
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
        val sheet = Sheet(this, "删除主机", "删除「${h.name}」?")
        sheet.danger("删除") {
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.wolDelete(h.id) }
                    toast("已删除")
                    load()
                } catch (e: Exception) { toast("删除失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }
}
