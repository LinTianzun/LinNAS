package main

import "time"

// timeNow returns current unix seconds (kept for future session expiry).
func timeNow() int64 { return time.Now().Unix() }
