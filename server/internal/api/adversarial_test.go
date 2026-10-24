package api_test

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"memosync/internal/api"
	"memosync/internal/store"
)

// 本文件是「循环测试」中新增的对抗性测试：覆盖权限、通知失败、数据清理等边界。

// ---------- 用户删除与工作组 ----------

// TestDeleteGroupOwner 组长被删除后，工作组不应变成无人可管理的孤儿。
func TestDeleteGroupOwner(t *testing.T) {
	e := newEnv(t)
	momID := e.createUser("mom", "pass1234", "妈妈")
	e.createUser("dad", "pass1234", "爸爸")
	momTok := e.login("mom", "pass1234")
	dadTok := e.login("dad", "pass1234")

	code, out := e.do("POST", "/api/v1/spaces", momTok, map[string]string{"name": "妈妈组"})
	if code != 200 {
		t.Fatalf("建组失败: %d %s", code, out)
	}
	var grp store.Space
	_ = json.Unmarshal(out, &grp)
	if c, o := e.do("POST", "/api/v1/spaces/"+grp.ID+"/members", momTok, map[string]string{"username": "dad"}); c != 200 {
		t.Fatalf("拉人失败: %d %s", c, o)
	}

	// 管理员删除组长（妈妈）
	if c, o := e.do("DELETE", "/api/v1/admin/users/"+momID, e.adminToken, nil); c != 200 {
		t.Fatalf("删除组长失败: %d %s", c, o)
	}

	// 组仍在，但组员应能管理（不能留下无法管理的孤儿组）
	members, status := func() ([]store.SpaceMember, int) {
		code, out := e.do("GET", "/api/v1/spaces/"+grp.ID+"/members", dadTok, nil)
		var r struct {
			Members []store.SpaceMember `json:"members"`
		}
		_ = json.Unmarshal(out, &r)
		return r.Members, code
	}()
	if status != 200 {
		t.Fatalf("组员应仍可访问该组: %d", status)
	}
	hasOwner := false
	for _, m := range members {
		if m.Role == "owner" {
			hasOwner = true
		}
	}
	if !hasOwner {
		t.Fatalf("组长被删除后应转让组长给剩余组员，实际成员: %+v", members)
	}
	// 新组长应能拉人（验证权限确实转移）
	if c, o := e.do("POST", "/api/v1/spaces/"+grp.ID+"/members", dadTok, map[string]string{"username": "dad"}); c != 200 {
		t.Fatalf("转让后的组长应有管理权: %d %s", c, o)
	}
}

// TestDeleteUserCleansMembership 删除用户应清理其 space_members 记录。
func TestDeleteUserCleansMembership(t *testing.T) {
	e := newEnv(t)
	momID := e.createUser("mom", "pass1234", "妈妈")
	momTok := e.login("mom", "pass1234")
	code, out := e.do("POST", "/api/v1/spaces", momTok, map[string]string{"name": "组"})
	if code != 200 {
		t.Fatal("建组失败")
	}
	var grp store.Space
	_ = json.Unmarshal(out, &grp)

	if c, _ := e.do("DELETE", "/api/v1/admin/users/"+momID, e.adminToken, nil); c != 200 {
		t.Fatalf("删除用户失败: %d", c)
	}
	var n int
	if err := e.st.DB().QueryRow(`SELECT COUNT(*) FROM space_members WHERE user_id=?`, momID).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("删除用户后应清理其成员记录，剩余 %d 条", n)
	}
}

// ---------- 通知失败重试 ----------

// TestWebhookFailureRetries 所有渠道都失败时不能标记已提醒（否则提醒永久丢失）。
func TestWebhookFailureRetries(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)

	var hits int32
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(http.StatusInternalServerError) // 模拟 webhook 故障
	}))
	defer bad.Close()

	if c, o := e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{"feishu_webhook": bad.URL}); c != 200 {
		t.Fatalf("配置 webhook: %d %s", c, o)
	}
	past := time.Now().Add(-time.Minute).UnixMilli()
	id := uuidOf("retry-remind")
	if _, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "会失败的提醒", 0, func(m *store.Memo) {
		m.RemindAts = []int64{past}
	})}); r.Results[0].Status != "ok" {
		t.Fatal("创建提醒失败")
	}

	// 第一次触发：发送失败
	code, out := e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	if code != 200 {
		t.Fatalf("触发失败: %d %s", code, out)
	}
	var r1 struct {
		Count int `json:"count"`
		Fired []store.FiredReminder `json:"fired"`
	}
	_ = json.Unmarshal(out, &r1)
	if r1.Count == 0 {
		t.Fatal("应尝试发送（哪怕失败）")
	}
	if len(r1.Fired) > 0 && r1.Fired[0].Errors == "" {
		t.Fatal("失败结果应带错误信息")
	}

	// 第二次触发：仍应重试（不能被标记为已提醒）
	code, out = e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	if code != 200 {
		t.Fatalf("二次触发失败: %d", code)
	}
	var r2 struct {
		Count int `json:"count"`
	}
	_ = json.Unmarshal(out, &r2)
	if r2.Count == 0 {
		t.Fatal("上次发送失败，本次应重试（当前实现错误地标记为已提醒）")
	}
	if atomic.LoadInt32(&hits) < 2 {
		t.Fatalf("应至少调用 webhook 两次，实际 %d", atomic.LoadInt32(&hits))
	}
}

// TestWebhookRetryGivesUp 连续失败达到上限后应放弃，避免每 48 小时反复轰炸。
func TestWebhookRetryGivesUp(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer bad.Close()
	if c, _ := e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{"feishu_webhook": bad.URL}); c != 200 {
		t.Fatal("配置失败")
	}
	past := time.Now().Add(-time.Minute).UnixMilli()
	e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("giveup"), sp["family"], "总是失败", 0, func(m *store.Memo) {
		m.RemindAts = []int64{past}
	})})
	// 触发 6 次（上限 3 次 + 余量）
	for i := 0; i < 6; i++ {
		e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	}
	var out struct {
		Count int `json:"count"`
	}
	_, body := e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	_ = json.Unmarshal(body, &out)
	if out.Count != 0 {
		t.Fatalf("超过重试上限后应放弃，仍返回 %d", out.Count)
	}
}

// ---------- 输入清洗 ----------

// TestUsernameSanitized 用户名首尾空格应被清洗，避免出现"看起来一样"的重复账号。
func TestUsernameSanitized(t *testing.T) {
	e := newEnv(t)
	code, out := e.do("POST", "/api/v1/admin/users", e.adminToken, map[string]string{
		"username": "  dad  ", "password": "pass1234", "nickname": "爸爸", "role": "member",
	})
	if code != 200 {
		t.Fatalf("创建失败: %d %s", code, out)
	}
	var u store.User
	_ = json.Unmarshal(out, &u)
	if u.Username != "dad" {
		t.Fatalf("用户名应去除首尾空格，实际 %q", u.Username)
	}
	// 用清洗后的名字可以登录
	if c, _ := e.do("POST", "/api/v1/auth/login", "", map[string]string{"username": "dad", "password": "pass1234"}); c != 200 {
		t.Fatalf("清洗后的用户名应可登录: %d", c)
	}
	// 再创建 "dad" 应重名失败
	if c, _ := e.do("POST", "/api/v1/admin/users", e.adminToken, map[string]string{
		"username": "dad", "password": "pass1234", "nickname": "爸爸2", "role": "member",
	}); c != 400 {
		t.Fatalf("重名应 400: %d", c)
	}
	// 非法字符应拒绝
	if c, _ := e.do("POST", "/api/v1/admin/users", e.adminToken, map[string]string{
		"username": "a b c", "password": "pass1234", "nickname": "x", "role": "member",
	}); c != 400 {
		t.Fatalf("含空格的用户名应拒绝: %d", c)
	}
}

// ---------- 端口切换的可用性探测 ----------

// TestSetPortRejectsOccupied 目标端口已被占用时应拒绝，而不是改完配置导致服务起不来。
func TestSetPortRejectsOccupied(t *testing.T) {
	e := newEnv(t)
	cfgPath := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(cfgPath, []byte("listen: \":15280\"\ndata_dir: \"x\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	api.ConfigFilePath = cfgPath
	api.Restart = func() {}
	defer func() { api.ConfigFilePath = ""; api.Restart = nil }()

	// 占住一个随机端口（与服务端同一种地址形式：所有接口）
	busy, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	defer busy.Close()
	port := busy.Addr().(*net.TCPAddr).Port

	code, out := e.do("POST", "/api/v1/admin/port", e.adminToken, map[string]any{"port": port})
	if code != 400 {
		t.Fatalf("端口被占用应拒绝修改，实际 %d %s", code, out)
	}
	if !strings.Contains(string(out), "占用") {
		t.Fatalf("应提示端口被占用: %s", out)
	}
}
