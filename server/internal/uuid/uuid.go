// Package uuid 生成 RFC 4122 v4 随机 UUID，无第三方依赖。
package uuid

import (
	"crypto/rand"
	"encoding/hex"
)

// New 返回形如 550e8400-e29b-41d4-a716-446655440000 的 UUID 字符串。
func New() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("uuid: 读取随机数失败: " + err.Error())
	}
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant RFC 4122
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

// Valid 检查是否像我们生成的 UUID（宽容校验：8-4-4-4-12 十六进制）。
func Valid(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, c := range s {
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			if !isHex(byte(c)) {
				return false
			}
		}
	}
	return true
}

func isHex(c byte) bool {
	return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
}
