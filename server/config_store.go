package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
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
		return cfg, nil // first run: defaults, no libraries yet
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, cfg); err != nil {
		return nil, err
	}
	cfg.DataDir = dataDir // never trust persisted DataDir
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

// SaveConfig persists config to dataDir/config.json.
func (c *Config) SaveConfig() error {
	raw, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(c.DataDir, configFileName), raw, 0o644)
}
