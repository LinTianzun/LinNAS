package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// configFileName is the JSON config file inside DataDir.
const configFileName = "config.json"

// LoadConfig reads config from dataDir/config.json, creating defaults if absent.
func LoadConfig(dataDir string) (*Config, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, err
	}
	cfg := &Config{
		Port:                 DefaultPort,
		Host:                 "",
		DataDir:              dataDir,
		IdleLockMinutes:      30,
		TrashRetentionDays:   defaultTrashRetentionDays,
		TranscodeCacheMB:     defaultTranscodeCacheMB,
		IndexRefreshSeconds:  120,
		SpaceWarnPercent:     defaultSpaceWarnPercent,
		SpaceCriticalPercent: defaultSpaceCriticalPercent,
	}
	raw, err := os.ReadFile(filepath.Join(dataDir, configFileName))
	if errors.Is(err, os.ErrNotExist) {
		applyPortEnv(cfg)
		return cfg, nil // first run: defaults, no libraries yet
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, cfg); err != nil {
		return nil, err
	}
	cfg.DataDir = dataDir // never trust persisted DataDir
	applyPortEnv(cfg)
	if cfg.IdleLockMinutes <= 0 {
		cfg.IdleLockMinutes = 30
	}
	if cfg.SpaceWarnPercent <= 0 {
		cfg.SpaceWarnPercent = defaultSpaceWarnPercent
	}
	if cfg.SpaceCriticalPercent <= 0 {
		cfg.SpaceCriticalPercent = defaultSpaceCriticalPercent
	}
	return cfg, nil
}

// applyPortEnv 用 LINNAS_PORT 环境变量覆盖端口(优先于 config.json),
// 便于在同一台机器上跑多个实例(各自换端口 + 换 LINNAS_DATA)。
// 无效值(非数字 / 越界)打一条警告到 stderr 并忽略,不改动端口。
func applyPortEnv(cfg *Config) {
	p := strings.TrimSpace(os.Getenv("LINNAS_PORT"))
	if p == "" {
		return
	}
	n, err := strconv.Atoi(p)
	if err != nil || n <= 0 || n > 65535 {
		os.Stderr.WriteString("LINNAS_PORT 无效(需 1-65535 的数字),忽略: " + p + "\n")
		return
	}
	cfg.Port = n
}

// SaveConfig persists config to dataDir/config.json.
func (c *Config) SaveConfig() error {
	raw, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(c.DataDir, configFileName), raw, 0o644)
}
