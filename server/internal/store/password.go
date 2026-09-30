package store

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

// argon2id 参数：面向 NAS 低功耗 CPU 折中（32MB 内存，3 轮）。
const (
	argonMemory  = 32 * 1024 // KiB
	argonTime    = 3
	argonThreads = 1
	argonKeyLen  = 32
	argonSaltLen = 16
)

// HashPassword 生成 PHC 字符串格式：$argon2id$v=19$m=...,t=...,p=1$salt$hash
func HashPassword(password string) string {
	salt := make([]byte, argonSaltLen)
	if _, err := rand.Read(salt); err != nil {
		panic("store: 读取随机盐失败: " + err.Error())
	}
	key := argon2.IDKey([]byte(password), salt, argonTime, argonMemory, argonThreads, argonKeyLen)
	return fmt.Sprintf("$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s",
		argonMemory, argonTime, argonThreads,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(key))
}

// VerifyPassword 校验密码，格式或参数不同均返回 false。
func VerifyPassword(password, encoded string) bool {
	parts := strings.Split(encoded, "$")
	// ["", "argon2id", "v=19", "m=...,t=...,p=1", salt, hash]
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false
	}
	var (
		m   uint32
		t   uint32
		p   uint8
		ver int
	)
	if _, err := fmt.Sscanf(parts[2], "v=%d", &ver); err != nil || ver != 19 {
		return false
	}
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &m, &t, &p); err != nil {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return false
	}
	got := argon2.IDKey([]byte(password), salt, t, m, p, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1
}

// NewToken 生成登录 token（明文发给客户端；服务端只存其 SHA-256）。
func NewToken() (token, tokenHash string) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic("store: 读取随机数失败: " + err.Error())
	}
	token = "ms_" + base64.RawURLEncoding.EncodeToString(b)
	tokenHash = HashToken(token)
	return token, tokenHash
}

// PairCode 生成 8 位易读配对码（去掉易混淆的 0/O/1/I/L）。
func PairCode() string {
	const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		panic("store: 读取随机数失败: " + err.Error())
	}
	out := make([]byte, 8)
	for i := range b {
		out[i] = alphabet[int(b[i])%len(alphabet)]
	}
	return string(out)
}
