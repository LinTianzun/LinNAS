package main

import (
	"fmt"
	"net/http"
	"syscall"
	"unsafe"
)

// handleDisks reports free/total space of each registered library root.
func (s *Server) handleDisks(w http.ResponseWriter, r *http.Request) {
	type diskInfo struct {
		ID      string `json:"id"`
		Name    string `json:"name"`
		Path    string `json:"path"`
		FreeMB  uint64 `json:"freeMB"`
		TotalMB uint64 `json:"totalMB"`
	}
	out := []diskInfo{}
	for _, lib := range s.cfg.Libraries {
		free, total, err := diskUsage(lib.Path)
		if err != nil {
			continue
		}
		out = append(out, diskInfo{ID: lib.ID, Name: lib.Name, Path: lib.Path, FreeMB: free / 1e6, TotalMB: total / 1e6})
	}
	writeJSON(w, 200, out)
}

var (
	modkernel32      = syscall.NewLazyDLL("kernel32.dll")
	procGetDiskFree  = modkernel32.NewProc("GetDiskFreeSpaceExW")
)

// diskUsage returns free and total bytes for the volume containing dir.
func diskUsage(dir string) (free uint64, total uint64, err error) {
	utf16, err := syscall.UTF16PtrFromString(dir)
	if err != nil {
		return 0, 0, err
	}
	var freeToCaller, totalBytes, totalFree uint64
	r1, _, callErr := procGetDiskFree.Call(
		uintptr(unsafe.Pointer(utf16)),
		uintptr(unsafe.Pointer(&freeToCaller)),
		uintptr(unsafe.Pointer(&totalBytes)),
		uintptr(unsafe.Pointer(&totalFree)),
	)
	if r1 == 0 {
		return 0, 0, fmt.Errorf("GetDiskFreeSpaceExW: %v", callErr)
	}
	return totalFree, totalBytes, nil
}
