package main

import (
	"log"
	"time"
)

// startHousekeeping 启动后台维护任务:进程启动 2 分钟后执行一次,之后每 6 小时执行一次。
// 负责:按保留天数自动清理回收站、按上限淘汰转码缓存、清理过期的保险箱会话。
// 这些任务都是「尽力而为」,失败只记录日志,不影响主服务。
func (s *Server) startHousekeeping() {
	go func() {
		time.Sleep(2 * time.Minute)
		s.runHousekeeping()
		ticker := time.NewTicker(6 * time.Hour)
		defer ticker.Stop()
		for range ticker.C {
			s.runHousekeeping()
		}
	}()
}

// runHousekeeping 立即执行一轮维护清理。
func (s *Server) runHousekeeping() {
	if days := s.cfg.TrashRetentionDays; days > 0 {
		if n := s.cleanTrashByAge(days); n > 0 {
			log.Printf("回收站自动清理: 删除 %d 项(保留 %d 天)", n, days)
		}
	}
	if mb := s.cfg.TranscodeCacheMB; mb > 0 {
		if r, freed := s.trimTranscodeCache(mb); r > 0 {
			log.Printf("转码缓存自动淘汰: %d 个文件,释放 %.1f MB", r, float64(freed)/(1024*1024))
		}
	}
	if n := pruneVaultSessions(); n > 0 {
		log.Printf("清理过期保险箱会话: %d 个", n)
	}
}
