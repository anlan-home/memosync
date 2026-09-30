//go:build !unix

package main

import "errors"

// restartSelf Windows 开发环境不支持 exec 自身（生产 NAS 为 Linux）。
func restartSelf() error {
	return errors.New("当前平台不支持原地重启（仅在 fnOS/Linux 上生效），请在应用中心手动重启")
}
