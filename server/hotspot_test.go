package main

import "testing"

// TestNormalizeHotspot 校验热点原始输出到前端结构的映射,
// 重点是 WinRT 结果码(尤其是 SelectedNetworkBandNotAvailable)能否翻译成可读原因。
func TestNormalizeHotspot(t *testing.T) {
	cases := []struct {
		name    string
		raw     map[string]any
		support bool
		running bool
		reason  string
	}{
		{
			name:    "band unavailable",
			raw:     map[string]any{"ok": true, "capability": "Enabled", "state": "Off", "result": "SelectedNetworkBandNotAvailable"},
			support: true, running: false, reason: "band-unavailable",
		},
		{
			name:    "started ok",
			raw:     map[string]any{"ok": true, "capability": "Enabled", "state": "On", "result": "Success"},
			support: true, running: true, reason: "",
		},
		{
			name:    "wifi adapter off",
			raw:     map[string]any{"ok": true, "capability": "Enabled", "state": "Off", "result": "WiFiDeviceOff"},
			support: true, running: false, reason: "wifi-off",
		},
		{
			name:    "no internet sharing source",
			raw:     map[string]any{"ok": true, "capability": "Enabled", "state": "Off", "result": "NetworkConnectivityFailure"},
			support: true, running: false, reason: "no-internet",
		},
		{
			name:    "capability disabled",
			raw:     map[string]any{"ok": true, "capability": "Disabled", "state": "Off"},
			support: false, running: false, reason: "disabled",
		},
		{
			name:    "script failed",
			raw:     map[string]any{"ok": false, "error": "boom"},
			support: false, running: false, reason: "error",
		},
	}
	for _, c := range cases {
		got := normalizeHotspot(c.raw)
		if got["supported"] != c.support {
			t.Errorf("%s: supported=%v want %v", c.name, got["supported"], c.support)
		}
		if got["running"] != c.running {
			t.Errorf("%s: running=%v want %v", c.name, got["running"], c.running)
		}
		if got["reason"] != c.reason {
			t.Errorf("%s: reason=%q want %q", c.name, got["reason"], c.reason)
		}
	}
}
