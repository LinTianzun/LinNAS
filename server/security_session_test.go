package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestSessionPersistsAcrossRestart 验证会话落盘:服务重启后带上原来的 token 仍然有效(无需重新登录)。
func TestSessionPersistsAcrossRestart(t *testing.T) {
	dir := t.TempDir()
	a := newAuthStore(dir, 30)
	if _, err := a.CreateUser("admin", "pw-123456", "admin"); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	sess, err := a.Login("admin", "pw-123456", "127.0.0.1")
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	tok := sess.Token
	if tok == "" {
		t.Fatal("token 为空")
	}
	if _, err := os.Stat(filepath.Join(dir, "sessions.json")); err != nil {
		t.Fatalf("sessions.json 未落盘: %v", err)
	}

	// 模拟服务重启:同一数据目录新建 authStore
	b := newAuthStore(dir, 30)
	got := b.Get(tok)
	if got == nil {
		t.Fatal("重启后会话应仍然有效")
	}
	if got.Username != "admin" || got.Role != "admin" {
		t.Fatalf("恢复的会话信息不对: %+v", got)
	}

	// 退出登录后重启不应再恢复
	b.Logout(tok)
	c := newAuthStore(dir, 30)
	if c.Get(tok) != nil {
		t.Fatal("退出登录后会话不应被恢复")
	}
}

// TestSessionNotRestoredForDeletedUser 验证用户被删除后其会话不会被恢复。
func TestSessionNotRestoredForDeletedUser(t *testing.T) {
	dir := t.TempDir()
	a := newAuthStore(dir, 30)
	u, err := a.CreateUser("bob", "pw-123456", "user")
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	sess, err := a.Login("bob", "pw-123456", "127.0.0.1")
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	tok := sess.Token
	if err := a.DeleteUser(u.ID); err != nil {
		t.Fatalf("DeleteUser: %v", err)
	}
	b := newAuthStore(dir, 30)
	if b.Get(tok) != nil {
		t.Fatal("已删除用户的会话不应被恢复")
	}
}

// TestSessionIdleExpiryStillApplies 验证持久化不会绕过闲置超时:超时后会话失效。
func TestSessionIdleExpiryStillApplies(t *testing.T) {
	dir := t.TempDir()
	a := newAuthStore(dir, 30)
	if _, err := a.CreateUser("carol", "pw-123456", "user"); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	sess, err := a.Login("carol", "pw-123456", "127.0.0.1")
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	a.mu.Lock()
	a.sessions[sess.Token].LastSeen = time.Now().Add(-31 * time.Minute).Unix()
	a.mu.Unlock()
	if a.Get(sess.Token) != nil {
		t.Fatal("超过闲置时长的会话应失效")
	}
}
