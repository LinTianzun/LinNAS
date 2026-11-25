package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNormalizeMAC(t *testing.T) {
	cases := map[string]string{
		"AA:BB:CC:DD:EE:FF":   "aa:bb:cc:dd:ee:ff",
		"aa-bb-cc-dd-ee-ff":   "aa:bb:cc:dd:ee:ff",
		"aabbccddeeff":        "aa:bb:cc:dd:ee:ff",
		" aa bb cc dd ee ff ": "aa:bb:cc:dd:ee:ff",
	}
	for in, want := range cases {
		got, err := normalizeMAC(in)
		if err != nil {
			t.Errorf("normalizeMAC(%q) 出错: %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("normalizeMAC(%q) = %q, want %q", in, got, want)
		}
	}
	for _, bad := range []string{"", "zz:bb:cc:dd:ee:ff", "aa:bb:cc", "aabbccddeeff00"} {
		if _, err := normalizeMAC(bad); err == nil {
			t.Errorf("normalizeMAC(%q) 应该报错", bad)
		}
	}
}

func TestMagicPacket(t *testing.T) {
	pkt, err := magicPacket("aa:bb:cc:dd:ee:ff")
	if err != nil {
		t.Fatal(err)
	}
	if len(pkt) != 102 {
		t.Fatalf("magic packet 长度 = %d, 应为 102", len(pkt))
	}
	for i := 0; i < 6; i++ {
		if pkt[i] != 0xFF {
			t.Fatalf("前 6 字节应为 0xFF")
		}
	}
	// 之后应是 16 次 MAC
	mac := []byte{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff}
	for i := 0; i < 16; i++ {
		chunk := pkt[6+i*6 : 6+i*6+6]
		if !bytes.Equal(chunk, mac) {
			t.Fatalf("第 %d 段 MAC 不正确: %x", i, chunk)
		}
	}
	if _, err := magicPacket("bad"); err == nil {
		t.Errorf("非法 MAC 应报错")
	}
}

func newWolTestServer(t *testing.T) *Server {
	t.Helper()
	return &Server{cfg: &Config{DataDir: t.TempDir()}}
}

func TestWolAddListDelete(t *testing.T) {
	s := newWolTestServer(t)
	// 添加
	body, _ := json.Marshal(map[string]string{"name": "客厅电脑", "mac": "AA-BB-CC-DD-EE-FF"})
	rec := httptest.NewRecorder()
	s.handleWolAdd(rec, httptest.NewRequest(http.MethodPost, "/api/wol", bytes.NewReader(body)))
	if rec.Code != 200 {
		t.Fatalf("添加失败 %d: %s", rec.Code, rec.Body.String())
	}
	var addResp struct {
		Host WolHost `json:"host"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &addResp)
	if addResp.Host.MAC != "aa:bb:cc:dd:ee:ff" {
		t.Errorf("MAC 未规范化: %q", addResp.Host.MAC)
	}
	if addResp.Host.ID == "" || addResp.Host.Name != "客厅电脑" {
		t.Errorf("主机字段不正确: %+v", addResp.Host)
	}

	// 列表
	rec = httptest.NewRecorder()
	s.handleWolList(rec, httptest.NewRequest(http.MethodGet, "/api/wol", nil))
	var listResp struct {
		Hosts []WolHost `json:"hosts"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &listResp)
	if len(listResp.Hosts) != 1 {
		t.Fatalf("列表应有 1 台,得到 %d", len(listResp.Hosts))
	}

	// 删除
	rec = httptest.NewRecorder()
	s.handleWolDelete(rec, httptest.NewRequest(http.MethodDelete, "/api/wol?id="+addResp.Host.ID, nil))
	if rec.Code != 200 {
		t.Fatalf("删除失败 %d: %s", rec.Code, rec.Body.String())
	}
	if len(s.cfg.WolHosts) != 0 {
		t.Errorf("删除后列表应为空")
	}

	// 删除不存在的
	rec = httptest.NewRecorder()
	s.handleWolDelete(rec, httptest.NewRequest(http.MethodDelete, "/api/wol?id=nope", nil))
	if rec.Code != 404 {
		t.Errorf("删除不存在主机应 404,得到 %d", rec.Code)
	}
}

func TestWolSend(t *testing.T) {
	s := newWolTestServer(t)
	body, _ := json.Marshal(map[string]string{"mac": "aa:bb:cc:dd:ee:ff", "addr": "127.0.0.1:9"})
	rec := httptest.NewRecorder()
	s.handleWolSend(rec, httptest.NewRequest(http.MethodPost, "/api/wol/send", bytes.NewReader(body)))
	if rec.Code != 200 {
		t.Fatalf("发送应成功 %d: %s", rec.Code, rec.Body.String())
	}
	// 非法 MAC
	body, _ = json.Marshal(map[string]string{"mac": "nope"})
	rec = httptest.NewRecorder()
	s.handleWolSend(rec, httptest.NewRequest(http.MethodPost, "/api/wol/send", bytes.NewReader(body)))
	if rec.Code != 400 {
		t.Errorf("非法 MAC 应 400,得到 %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "发送失败") && rec.Code == 400 {
		t.Logf("body=%s", rec.Body.String())
	}
}
