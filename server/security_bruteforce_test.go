package main

import (
	"testing"
	"time"
)

func TestLoginLockoutAfterRepeatedFailures(t *testing.T) {
	a := newAuthStore(t.TempDir(), 30)
	if _, err := a.CreateUser("alice", "rightpw", "admin"); err != nil {
		t.Fatal(err)
	}

	// 4 次失败:只报错,不锁定
	for i := 0; i < bruteMaxFails-1; i++ {
		_, err := a.Login("alice", "wrongpw", "10.0.0.1")
		if err == nil {
			t.Fatal("login with wrong password unexpectedly succeeded")
		}
		if _, ok := err.(*LockoutError); ok {
			t.Fatalf("locked out after only %d failures", i+1)
		}
	}
	// 第 5 次失败:触发锁定
	_, err := a.Login("alice", "wrongpw", "10.0.0.1")
	le, ok := err.(*LockoutError)
	if !ok {
		t.Fatalf("5th failure should lock out, got %v", err)
	}
	if le.RetryAfter <= 0 {
		t.Errorf("RetryAfter = %d, want > 0", le.RetryAfter)
	}
	// 锁定期间:即使密码正确也拒绝
	_, err = a.Login("alice", "rightpw", "10.0.0.1")
	if err == nil {
		t.Fatal("locked account accepted the correct password")
	}
	if _, ok := err.(*LockoutError); !ok {
		t.Fatalf("expected LockoutError, got %v", err)
	}
	if rem := a.LockRemaining("alice", "10.0.0.1"); rem <= 0 {
		t.Error("LockRemaining should be positive while locked")
	}
	// 另一个 IP 不受影响
	if _, err := a.Login("alice", "rightpw", "10.0.0.2"); err != nil {
		t.Errorf("login from a different IP was blocked: %v", err)
	}
}

func TestLockoutExponentialGrowthCapped(t *testing.T) {
	a := newAuthStore(t.TempDir(), 30)
	if _, err := a.CreateUser("bob", "rightpw", "user"); err != nil {
		t.Fatal(err)
	}
	key := bruteKey("bob", "1.2.3.4")

	// 反复失败,观察每次锁定时长
	lastLock := time.Duration(0)
	for round := 0; round < 6; round++ {
		for i := 0; i < bruteMaxFails; i++ {
			_, _ = a.Login("bob", "bad", "1.2.3.4")
		}
		a.mu.Lock()
		st := a.fails[key]
		lock := time.Duration(0)
		if st != nil {
			lock = time.Until(st.lockUntil)
		}
		a.mu.Unlock()
		if lock <= lastLock {
			t.Fatalf("lock window did not grow: round %d = %v, previous %v", round, lock, lastLock)
		}
		lastLock = lock
		// 解锁后继续(直接清空锁,模拟等待到期)
		a.mu.Lock()
		a.fails[key].lockUntil = time.Time{}
		a.mu.Unlock()
	}
	if lastLock > bruteMaxLock+time.Second {
		t.Errorf("lock window exceeded cap: %v", lastLock)
	}
}

func TestSuccessfulLoginResetsFailures(t *testing.T) {
	a := newAuthStore(t.TempDir(), 30)
	if _, err := a.CreateUser("carol", "rightpw", "user"); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < bruteMaxFails-1; i++ {
		_, _ = a.Login("carol", "bad", "9.9.9.9")
	}
	if _, err := a.Login("carol", "rightpw", "9.9.9.9"); err != nil {
		t.Fatal(err)
	}
	a.mu.Lock()
	_, exists := a.fails[bruteKey("carol", "9.9.9.9")]
	a.mu.Unlock()
	if exists {
		t.Error("fail counter was not reset after a successful login")
	}
}

func TestSessionsListAndRevoke(t *testing.T) {
	a := newAuthStore(t.TempDir(), 30)
	if _, err := a.CreateUser("dave", "pw1234", "admin"); err != nil {
		t.Fatal(err)
	}
	if _, err := a.CreateUser("erin", "pw5678", "user"); err != nil {
		t.Fatal(err)
	}
	s1, err := a.Login("dave", "pw1234", "10.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	s2, err := a.Login("dave", "pw1234", "10.1.1.2")
	if err != nil {
		t.Fatal(err)
	}
	s3, err := a.Login("erin", "pw5678", "10.1.1.3")
	if err != nil {
		t.Fatal(err)
	}
	if s1.IP != "10.1.1.1" {
		t.Errorf("session IP not recorded: %q", s1.IP)
	}

	// admin 看到全部 3 个会话,当前会话恰好标记一个
	list := a.Sessions(s1.Token)
	if len(list) != 3 {
		t.Fatalf("Sessions() = %d rows, want 3", len(list))
	}
	current := 0
	for _, si := range list {
		if si.Current {
			current++
		}
		if len(si.ID) != 6 {
			t.Errorf("session id %q is not a 6-char prefix", si.ID)
		}
	}
	if current != 1 {
		t.Errorf("current session count = %d, want 1", current)
	}

	// 非管理员只能吊销自己的会话
	n, err := a.Revoke(s2.Token[:6], s3)
	if err == nil || n != 0 {
		t.Fatalf("non-admin revoked another user's session: n=%d err=%v", n, err)
	}
	n, err = a.Revoke(s3.Token[:6], s3)
	if err != nil || n != 1 {
		t.Fatalf("self revoke failed: n=%d err=%v", n, err)
	}
	if a.Get(s3.Token) != nil {
		t.Error("revoked session is still valid")
	}
	// admin 可以吊销任何会话
	n, err = a.Revoke(s2.Token[:6], s1)
	if err != nil || n != 1 {
		t.Fatalf("admin revoke failed: n=%d err=%v", n, err)
	}
	if a.Get(s2.Token) != nil {
		t.Error("admin-revoked session is still valid")
	}
	// 其余会话不受影响
	if a.Get(s1.Token) == nil {
		t.Error("unrelated session was revoked")
	}
	// 无效前缀
	if _, err := a.Revoke("abc", s1); err == nil {
		t.Error("short id prefix accepted")
	}
}
