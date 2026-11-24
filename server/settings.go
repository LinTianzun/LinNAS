package main

import (
	"net/http"
	"runtime"
	"time"
)

// handleSettingsInfo GET /api/settings — 系统信息(只读)
func (s *Server) handleSettingsInfo(w http.ResponseWriter, r *http.Request) {
	info := map[string]any{
		"version":         Version,
		"port":            s.cfg.Port,
		"host":            s.cfg.Host,
		"dataDir":         s.cfg.DataDir,
		"tokenSet":        s.cfg.Token != "",
		"libCount":        len(s.cfg.Libraries),
		"goos":            runtime.GOOS,
		"goarch":          runtime.GOARCH,
		"ffmpeg":          findFFmpeg() != "",
		"vaultDir":        s.vaultDir(),
		"serverNow":       time.Now().Unix(),
		"idleLockMinutes": s.auth.IdleMinutes(),
		"multiUser":       s.auth.MultiUser(),
		"userCount":       s.auth.UserCount(),
	}
	if s.index != nil {
		st := s.index.Status()
		st["stats"] = s.index.Stats()
		info["index"] = st
	} else {
		info["index"] = nil
	}
	if vc, err := s.readVaultCfg(); err == nil {
		info["vaultExists"] = vc != nil
	}
	writeJSON(w, 200, info)
}

// handleSettingsUpdate POST /api/settings {token} — 修改访问口令(立即生效)
func (s *Server) handleSettingsUpdate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token           *string `json:"token"`
		IdleLockMinutes *int    `json:"idleLockMinutes"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if req.Token != nil {
		s.cfg.Token = *req.Token
	}
	if req.IdleLockMinutes != nil {
		m := *req.IdleLockMinutes
		if m < 1 {
			m = 1
		}
		if m > 1440 {
			m = 1440
		}
		s.cfg.IdleLockMinutes = m
		s.auth.idle = time.Duration(m) * time.Minute
	}
	if err := s.cfg.SaveConfig(); err != nil {
		writeErr(w, 500, "保存配置失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "tokenSet": s.cfg.Token != "", "idleLockMinutes": s.auth.IdleMinutes()})
}
