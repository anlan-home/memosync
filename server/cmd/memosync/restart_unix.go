//go:build unix

package main

import (
	"os"
	"syscall"
)

// restartSelf 原地重启：exec 替换当前进程映像，pid 不变（fnOS 应用中心无感知）。
func restartSelf() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return syscall.Exec(exe, os.Args, os.Environ())
}
