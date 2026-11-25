package main

import "fmt"

// Version of linNas server.
const Version = "1.6.0"

// DefaultPort is the port linNas listens on by default.
const DefaultPort = 52700

// Config holds all server configuration.
type Config struct {
	Port      int       // HTTP listen port
	Host      string    // listen address, "" = all interfaces
	Token     string    // shared access token; empty = no auth (LAN only)
	DataDir   string    // where linNas keeps its own data (config etc.)
	VaultDir  string    // 隐私保险箱目录(默认放在第一个可写存储库里,如 H:\NAS\.linNasVault)
	Libraries []Library // registered storage libraries
	// IdleLockMinutes 会话闲置多少分钟后自动锁定(需重新登录),默认 30。
	IdleLockMinutes int `json:"idleLockMinutes,omitempty"`
}

// Library is one registered storage location (e.g. H:\, I:\).
type Library struct {
	ID       string   `json:"id"`       // short stable id, e.g. "lib1"
	Name     string   `json:"name"`     // display name, e.g. "磁盘"
	Path     string   `json:"path"`     // absolute filesystem path
	ReadOnly bool     `json:"readOnly"` // disallow uploads/deletes
	Enabled  bool     `json:"enabled"`  // whether it is served
	Exclude  []string `json:"exclude"`  // 相对子目录黑名单(隐藏且不索引),如 "U盘备份"
}

// String renders config for logging.
func (c Config) String() string {
	return fmt.Sprintf("linNas v%s port=%d libs=%d", Version, c.Port, len(c.Libraries))
}

// LibraryByID finds a library by its id.
func (c *Config) LibraryByID(id string) *Library {
	for i := range c.Libraries {
		if c.Libraries[i].ID == id {
			return &c.Libraries[i]
		}
	}
	return nil
}
