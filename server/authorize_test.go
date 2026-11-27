package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestAuthorizeRoleMatrix 覆盖 admin / user / viewer 三个角色对代表性接口的授权矩阵。
// 目的：把 security.go 里分散的 if 分支固化成可回归的表格，避免新增路由时漏加守卫。
func TestAuthorizeRoleMatrix(t *testing.T) {
	s := &Server{}
	cases := []struct {
		name   string
		role   string
		method string
		path   string
		want   bool
	}{
		// ---- admin：全部放行 ----
		{"admin read files", "admin", http.MethodGet, "/api/ls", true},
		{"admin delete user", "admin", http.MethodDelete, "/api/users", true},
		{"admin run organize", "admin", http.MethodPost, "/api/organize/run", true},
		{"admin maintenance", "admin", http.MethodPost, "/api/maintenance/cleanup", true},

		// ---- user：可读写文件，但不能改系统/用户 ----
		{"user read files", "user", http.MethodGet, "/api/ls", true},
		{"user delete file", "user", http.MethodPost, "/api/delete", true},
		{"user upload init", "user", http.MethodPost, "/api/upload/init", true},
		{"user change own password", "user", http.MethodPost, "/api/users/password", true},
		{"user read settings", "user", http.MethodGet, "/api/settings", true},
		{"user write settings", "user", http.MethodPost, "/api/settings", false},
		{"user list users", "user", http.MethodGet, "/api/users", false},
		{"user create user", "user", http.MethodPost, "/api/users", false},
		{"user read audit", "user", http.MethodGet, "/api/audit", false},
		{"user read libs", "user", http.MethodGet, "/api/libs", true},
		{"user add lib", "user", http.MethodPost, "/api/libs", false},
		{"user update lib", "user", http.MethodPost, "/api/libs/update", false},
		{"user fs mkdir", "user", http.MethodPost, "/api/fs/mkdir", false},
		{"user clear devices", "user", http.MethodPost, "/api/devices/clear", false},
		{"user read remote", "user", http.MethodGet, "/api/remote", true},
		{"user tailscale serve", "user", http.MethodPost, "/api/remote/serve", false},
		{"user read autostart", "user", http.MethodGet, "/api/autostart", true},
		{"user set autostart", "user", http.MethodPost, "/api/autostart", false},
		{"user cleanup", "user", http.MethodPost, "/api/maintenance/cleanup", false},
		{"user read wol", "user", http.MethodGet, "/api/wol", true},
		{"user send wol", "user", http.MethodPost, "/api/wol/send", false},
		{"user read organize rules", "user", http.MethodGet, "/api/organize/rules", false},
		{"user preview organize", "user", http.MethodPost, "/api/organize/preview", false},
		{"user ack alerts", "user", http.MethodPost, "/api/alerts/ack", true},
		{"user get playback", "user", http.MethodGet, "/api/playback", true},
		{"user put playback", "user", http.MethodPost, "/api/playback", true},

		// ---- viewer：只读，例外仅 auth / vault unlock|lock / 自己的进度与告警 ----
		{"viewer read files", "viewer", http.MethodGet, "/api/ls", true},
		{"viewer read media", "viewer", http.MethodGet, "/api/media", true},
		{"viewer delete file", "viewer", http.MethodPost, "/api/delete", false},
		{"viewer delete share", "viewer", http.MethodDelete, "/api/shares", false},
		{"viewer login", "viewer", http.MethodPost, "/api/auth/login", true},
		{"viewer logout", "viewer", http.MethodPost, "/api/auth/logout", true},
		{"viewer vault unlock", "viewer", http.MethodPost, "/api/vault/unlock", true},
		{"viewer vault lock", "viewer", http.MethodPost, "/api/vault/lock", true},
		{"viewer vault setup", "viewer", http.MethodPost, "/api/vault/setup", false},
		{"viewer ack alerts", "viewer", http.MethodPost, "/api/alerts/ack", true},
		{"viewer put playback", "viewer", http.MethodPost, "/api/playback", true},
		{"viewer own password", "viewer", http.MethodPost, "/api/users/password", true},
		{"viewer list users", "viewer", http.MethodGet, "/api/users", false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(tc.method, tc.path, nil)
			got := s.authorize(&Session{Role: tc.role, Username: "tester"}, req)
			if got != tc.want {
				t.Fatalf("authorize(%s %s) role=%s = %v, want %v", tc.method, tc.path, tc.role, got, tc.want)
			}
		})
	}
}
