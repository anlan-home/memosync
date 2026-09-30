package store

import (
	"strings"
	"testing"
)

func TestPasswordHashRoundTrip(t *testing.T) {
	h := HashPassword(" correct horse 🔑 ")
	if !strings.HasPrefix(h, "$argon2id$") {
		t.Fatalf("应为 argon2id PHC 格式: %s", h)
	}
	if !VerifyPassword(" correct horse 🔑 ", h) {
		t.Fatal("正确密码应通过校验")
	}
	if VerifyPassword("wrong", h) {
		t.Fatal("错误密码不应通过")
	}
	if VerifyPassword("wrong", "not-a-hash") {
		t.Fatal("坏格式不应通过")
	}
	// 同密码两次哈希应不同（随机盐）
	if HashPassword("x1234") == HashPassword("x1234") {
		t.Fatal("相同密码的哈希不应相同（需随机盐）")
	}
}

func TestPairCodeAlphabet(t *testing.T) {
	for i := 0; i < 200; i++ {
		c := PairCode()
		if len(c) != 8 {
			t.Fatalf("配对码应 8 位: %s", c)
		}
		for _, ch := range c {
			if strings.ContainsRune("OI01L", ch) {
				t.Fatalf("配对码含易混淆字符: %s", c)
			}
		}
	}
}
