package main

import (
	"fmt"
	"strings"
)

// Version of linNas server.
const Version = "1.9.1"

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
	// TrashRetentionDays 回收站保留天数,超过后自动清理;0 = 不自动清理。默认 30。
	// 刻意不加 omitempty:0(关闭)也需要能持久化。
	TrashRetentionDays int `json:"trashRetentionDays"`
	// TranscodeCacheMB 转码缓存上限(MB),超出按最久未用淘汰;0 = 不限制。默认 2048。
	TranscodeCacheMB int `json:"transcodeCacheMB"`
	// IndexRefreshSeconds 索引自动对账间隔(秒),用于发现资源管理器等外部改动;0 = 关闭。默认 120。
	// 刻意不加 omitempty:0(关闭)也需要能持久化。
	IndexRefreshSeconds int `json:"indexRefreshSeconds"`
	// SpaceWarnPercent 单库可用空间百分比低于该值时给出告警。默认 10。
	SpaceWarnPercent int `json:"spaceWarnPercent"`
	// SpaceCriticalPercent 单库可用空间百分比低于该值时给出更严重的告警。默认 3。
	SpaceCriticalPercent int `json:"spaceCriticalPercent"`
	// WolHosts 记住的 Wake-on-LAN 主机列表。
	WolHosts []WolHost `json:"wolHosts,omitempty"`
	// ServerName 用户自定义的服务器显示名(如「客厅NAS」),空 = 默认 "LinNAS"。
	ServerName string `json:"serverName,omitempty"`
}

// DisplayName 返回用于界面展示的服务器名(未设置则回退 "LinNAS")。
func (c Config) DisplayName() string {
	if n := strings.TrimSpace(c.ServerName); n != "" {
		return n
	}
	return "LinNAS"
}

// 默认值(0 表示用户显式关闭,与「未设置」区分)。
const (
	defaultTrashRetentionDays   = 30
	defaultTranscodeCacheMB     = 2048
	defaultSpaceWarnPercent     = 10
	defaultSpaceCriticalPercent = 3
)

// Library is one registered storage location (e.g. H:\, I:\).
type Library struct {
	ID       string   `json:"id"`       // short stable id, e.g. "lib1"
	Name     string   `json:"name"`     // display name, e.g. "磁盘"
	Path     string   `json:"path"`     // absolute filesystem path
	ReadOnly bool     `json:"readOnly"` // disallow uploads/deletes
	Enabled  bool     `json:"enabled"`  // whether it is served
	Exclude  []string `json:"exclude"`  // 相对子目录黑名单(隐藏且不索引),如 "U盘备份"
	// MediaHide 标记为「不在媒体库展示」的路径:相对库根、正斜杠(文件或目录)。
	// 这些路径仍索引、仍可在文件浏览/搜索中访问,只是不出现在媒体库(视频/图片/音乐)。
	MediaHide []string `json:"mediaHide"`
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
