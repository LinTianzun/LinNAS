package main

import (
	"testing"
	"time"
)

// TestAlertStoreSyncKeepsFirstAtAndAcked 持续存在的告警保留 firstAt 与已读状态,
// 内容变化时刷新 lastAt。
func TestAlertStoreSyncKeepsFirstAtAndAcked(t *testing.T) {
	dir := t.TempDir()
	st := newAlertStore(dir)
	now := time.Unix(1700000000, 0)
	st.Sync([]spaceAlert{{
		ID: "space:lib1:warn", Level: "warn", LibID: "lib1",
		Text: "「H盘」可用空间不足 10%",
	}}, now)

	if got := st.Ack(nil, false); got != 0 {
		t.Errorf("没有 id 且 all=false 时 Ack 应为 0,得到 %d", got)
	}
	if got := st.Ack([]string{"space:lib1:warn"}, false); got != 1 {
		t.Fatalf("Ack 单个告警应返回 1,得到 %d", got)
	}

	// 一小时后同一告警(文案随阈值变化):firstAt/acked 保留,lastAt 刷新。
	later := now.Add(time.Hour)
	st.Sync([]spaceAlert{{
		ID: "space:lib1:warn", Level: "warn", LibID: "lib1",
		Text: "「H盘」可用空间不足 5%",
	}}, later)

	items := st.List()
	if len(items) != 1 {
		t.Fatalf("应有 1 条告警,得到 %d: %#v", len(items), items)
	}
	it := items[0]
	if it.FirstAt != now.Unix() {
		t.Errorf("FirstAt = %d, 应保留首次出现时间 %d", it.FirstAt, now.Unix())
	}
	if it.LastAt != later.Unix() {
		t.Errorf("LastAt = %d, 应刷新为 %d", it.LastAt, later.Unix())
	}
	if !it.Acked {
		t.Error("Acked 应被保留(已读状态不因内容变化而重置)")
	}
	if !it.Active {
		t.Error("持续存在的告警 Active 应为 true")
	}
	if it.Text != "「H盘」可用空间不足 5%" {
		t.Errorf("Text 应更新为新文案,得到 %q", it.Text)
	}
	if n := st.Unread(); n != 0 {
		t.Errorf("已读告警 Unread 应为 0,得到 %d", n)
	}

	// 落盘后重新加载,状态仍然保留。
	st2 := newAlertStore(dir)
	items2 := st2.List()
	if len(items2) != 1 {
		t.Fatalf("重新加载应有 1 条告警,得到 %d", len(items2))
	}
	if items2[0].FirstAt != now.Unix() || !items2[0].Acked || !items2[0].Active {
		t.Errorf("重新加载后状态不一致: %#v", items2[0])
	}
}

// TestAlertStoreSyncMarksVanishedInactive 本次未出现的告警标记为已恢复。
func TestAlertStoreSyncMarksVanishedInactive(t *testing.T) {
	st := newAlertStore(t.TempDir())
	now := time.Unix(1700000000, 0)
	st.Sync([]spaceAlert{{ID: "space:lib1:crit", Level: "warn", Text: "不足 3%"}}, now)
	// 第二次只剩另一条:第一条消失 → Active=false。
	st.Sync([]spaceAlert{{ID: "index:disabled", Level: "info", Text: "媒体索引未启用"}}, now.Add(time.Minute))

	byID := map[string]spaceAlert{}
	for _, it := range st.List() {
		byID[it.ID] = it
	}
	if len(byID) != 2 {
		t.Fatalf("消失的告警应仍保留在列表中,得到 %#v", byID)
	}
	if byID["space:lib1:crit"].Active {
		t.Error("消失的告警 Active 应为 false")
	}
	if !byID["index:disabled"].Active {
		t.Error("仍存在的告警 Active 应为 true")
	}
	if byID["space:lib1:crit"].FirstAt != now.Unix() {
		t.Error("消失的告警应保留 FirstAt")
	}
	if n := st.Unread(); n != 1 {
		t.Errorf("Unread 只应统计仍生效的告警,得到 %d", n)
	}
}

// TestAlertStoreAckAllAndUnread Ack(all) 计数与未读数。
func TestAlertStoreAckAllAndUnread(t *testing.T) {
	st := newAlertStore(t.TempDir())
	now := time.Unix(1700000000, 0)
	st.Sync([]spaceAlert{
		{ID: "space:lib1:crit", Level: "warn", Text: "不足 3%"},
		{ID: "space:lib2:warn", Level: "warn", Text: "不足 10%"},
		{ID: "index:disabled", Level: "info", Text: "媒体索引未启用"},
	}, now)
	if n := st.Unread(); n != 3 {
		t.Fatalf("Unread = %d, 应为 3", n)
	}
	if n := st.Ack(nil, true); n != 3 {
		t.Errorf("Ack(all) 应标记 3 条,得到 %d", n)
	}
	if n := st.Unread(); n != 0 {
		t.Errorf("全部已读后 Unread 应为 0,得到 %d", n)
	}
	if n := st.Ack(nil, true); n != 0 {
		t.Errorf("重复 Ack(all) 不应再标记,得到 %d", n)
	}
	// 恢复的告警再次出现:已读状态保留,不会重新计入未读。
	st.Sync([]spaceAlert{{ID: "space:lib1:crit", Level: "warn", Text: "不足 2%"}}, now.Add(time.Minute))
	if n := st.Unread(); n != 0 {
		t.Errorf("已读告警重新出现时 Unread 仍应为 0,得到 %d", n)
	}
}

// TestAlertStoreListOrdering List 按 Active 优先、LastAt 倒序。
func TestAlertStoreListOrdering(t *testing.T) {
	st := newAlertStore(t.TempDir())
	now := time.Unix(1700000000, 0)
	st.Sync([]spaceAlert{{ID: "index:disabled", Level: "info", Text: "旧"}}, now)
	st.Sync([]spaceAlert{
		{ID: "space:lib2:warn", Level: "warn", Text: "新"},
		{ID: "space:lib1:crit", Level: "warn", Text: "新"},
	}, now.Add(time.Minute))

	items := st.List()
	if len(items) != 3 {
		t.Fatalf("应有 3 条记录,得到 %d", len(items))
	}
	if items[0].Active != true || items[1].Active != true {
		t.Errorf("Active 条目应排在前面: %#v", items)
	}
	if items[2].ID != "index:disabled" {
		t.Errorf("已恢复的条目应排在最后,得到 %#v", items[2])
	}
}
