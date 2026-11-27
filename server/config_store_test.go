package main

import (
	"os"
	"testing"
)

func TestLoadConfigPortEnvOverride(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("LINNAS_PORT", "52999")
	cfg, err := LoadConfig(dir)
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.Port != 52999 {
		t.Fatalf("cfg.Port = %d, want 52999", cfg.Port)
	}
}

func TestLoadConfigPortEnvInvalidFallsBack(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("LINNAS_PORT", "notanumber")
	cfg, err := LoadConfig(dir)
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.Port != DefaultPort {
		t.Fatalf("cfg.Port = %d, want default %d", cfg.Port, DefaultPort)
	}
}

func TestLoadConfigPortEnvOutOfRangeFallsBack(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("LINNAS_PORT", "99999")
	cfg, err := LoadConfig(dir)
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.Port != DefaultPort {
		t.Fatalf("cfg.Port = %d, want default %d", cfg.Port, DefaultPort)
	}
}

func TestSaveConfigRoundTripsPort(t *testing.T) {
	dir := t.TempDir()
	os.Unsetenv("LINNAS_PORT")
	cfg, err := LoadConfig(dir)
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	cfg.Port = 52888
	if err := cfg.SaveConfig(); err != nil {
		t.Fatalf("SaveConfig: %v", err)
	}
	cfg2, err := LoadConfig(dir)
	if err != nil {
		t.Fatalf("LoadConfig again: %v", err)
	}
	if cfg2.Port != 52888 {
		t.Fatalf("round-trip cfg.Port = %d, want 52888", cfg2.Port)
	}
}
