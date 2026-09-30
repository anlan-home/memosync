package api_test

import (
	"archive/zip"
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"memosync/internal/api"
	"memosync/internal/config"
	"memosync/internal/store"
)
// ---------- 测试脚手架 ----------

type env struct {
	t          *testing.T
	ts         *httptest.Server
	st         *store.Store
	adminToken string
}

func newEnv(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	st, err := store.Open(dir)
	if err != nil {
		t.Fatalf("打开存储: %v", err)
	}
	if _, err := st.BootstrapAdmin("admin", "admin-pass-123"); err != nil {
		t.Fatalf("创建管理员: %v", err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	h := api.New(st, config.Config{Listen: ":0", DataDir: dir}, logger)
	ts := httptest.NewServer(h)
	t.Cleanup(func() {
		ts.Close()
		st.Close()
	})
	e := &env{t: t, ts: ts, st: st}
	e.adminToken = e.login("admin", "admin-pass-123")
	return e
}

// do 发起带认证的 JSON 请求，返回状态码与响应体。
func (e *env) do(method, path, token string, body any) (int, []byte) {
	e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			e.t.Fatalf("marshal body: %v", err)
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, e.ts.URL+path, rd)
	if err != nil {
		e.t.Fatalf("new request: %v", err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, out
}

func (e *env) login(username, password string) string {
	e.t.Helper()
	code, out := e.do("POST", "/api/v1/auth/login", "", map[string]string{
		"username": username, "password": password, "device_name": "测试设备",
	})
	if code != 200 {
		e.t.Fatalf("登录失败: %d %s", code, out)
	}
	var r struct{ Token string }
	_ = json.Unmarshal(out, &r)
	if r.Token == "" {
		e.t.Fatalf("登录响应缺少 token: %s", out)
	}
	return r.Token
}

func (e *env) createUser(name, password, nickname string) string {
	e.t.Helper()
	code, out := e.do("POST", "/api/v1/admin/users", e.adminToken, map[string]string{
		"username": name, "password": password, "nickname": nickname, "role": "member",
	})
	if code != 200 {
		e.t.Fatalf("创建成员 %s: %d %s", name, code, out)
	}
	var u struct{ ID string }
	_ = json.Unmarshal(out, &u)
	return u.ID
}

func (e *env) spaces(token string) map[string]string { // type -> id
	e.t.Helper()
	code, out := e.do("GET", "/api/v1/spaces", token, nil)
	if code != 200 {
		e.t.Fatalf("获取空间: %d %s", code, out)
	}
	var r struct {
		Spaces []store.Space `json:"spaces"`
	}
	_ = json.Unmarshal(out, &r)
	m := map[string]string{}
	for _, sp := range r.Spaces {
		m[sp.Type] = sp.ID
	}
	return m
}

func (e *env) push(token string, inputs []store.PushInput) (int, struct {
	Results   []store.PushResult `json:"results"`
	LatestSeq int64              `json:"latest_seq"`
}) {
	e.t.Helper()
	var resp struct {
		Results   []store.PushResult `json:"results"`
		LatestSeq int64              `json:"latest_seq"`
	}
	code, out := e.do("POST", "/api/v1/sync/push", token, map[string]any{"memos": inputs})
	if code != 200 {
		e.t.Fatalf("push: %d %s", code, out)
	}
	_ = json.Unmarshal(out, &resp)
	return code, resp
}

func (e *env) pull(token string, since int64) (int, struct {
	Changes    []store.ChangeEntry `json:"changes"`
	NextCursor int64               `json:"next_cursor"`
	LatestSeq  int64               `json:"latest_seq"`
}) {
	e.t.Helper()
	var resp struct {
		Changes    []store.ChangeEntry `json:"changes"`
		NextCursor int64               `json:"next_cursor"`
		LatestSeq  int64               `json:"latest_seq"`
	}
	code, out := e.do("GET", fmt.Sprintf("/api/v1/sync/pull?since=%d", since), token, nil)
	if code != 200 {
		e.t.Fatalf("pull: %d %s", code, out)
	}
	_ = json.Unmarshal(out, &resp)
	return code, resp
}

// memoInput 构造新备忘录推送。
func memoInput(id, spaceID, title string, base int64, patch func(*store.Memo)) store.PushInput {
	m := store.Memo{
		ID: id, SpaceID: spaceID, Type: "note", Title: title,
		Content: `{"type":"note","text":"内容"}`, Color: "lemon",
		ClientMtime: time.Now().UnixMilli(),
	}
	if patch != nil {
		patch(&m)
	}
	return store.PushInput{Memo: m, BaseVersion: base}
}

func uuidOf(s string) string {
	// 测试用确定性 id：由名字派生 12 位十六进制尾段（服务端校验 8-4-4-4-12 格式）
	sum := sha256.Sum256([]byte(s))
	return "00000000-0000-4000-8000-" + hex.EncodeToString(sum[:6])
}

// ---------- 认证 ----------

func TestAuthLogin(t *testing.T) {
	e := newEnv(t)

	// 错误密码
	if code, _ := e.do("POST", "/api/v1/auth/login", "", map[string]string{"username": "admin", "password": "wrong"}); code != 401 {
		t.Fatalf("错误密码应 401，得到 %d", code)
	}
	// 不存在的用户
	if code, _ := e.do("POST", "/api/v1/auth/login", "", map[string]string{"username": "nobody", "password": "x"}); code != 401 {
		t.Fatalf("不存在用户应 401，得到 %d", code)
	}
	// 缺少凭证
	if code, _ := e.do("GET", "/api/v1/auth/me", "", nil); code != 401 {
		t.Fatalf("无 token 应 401，得到 %d", code)
	}
	// 伪造 token
	if code, _ := e.do("GET", "/api/v1/auth/me", "ms_fake", nil); code != 401 {
		t.Fatalf("伪造 token 应 401，得到 %d", code)
	}
	// 正常
	code, out := e.do("GET", "/api/v1/auth/me", e.adminToken, nil)
	if code != 200 || !strings.Contains(string(out), `"admin"`) {
		t.Fatalf("me 应 200 含用户名: %d %s", code, out)
	}
}

func TestSpaces(t *testing.T) {
	e := newEnv(t)
	adminSpaces := e.spaces(e.adminToken)
	if adminSpaces["family"] == "" || adminSpaces["personal"] == "" {
		t.Fatalf("管理员应有家庭与个人空间: %v", adminSpaces)
	}
	tok := e.login(func() string { e.createUser("mom", "pass1234", "妈妈"); return "mom" }(), "pass1234")
	momSpaces := e.spaces(tok)
	if momSpaces["family"] != adminSpaces["family"] {
		t.Fatalf("成员的家庭空间应与管理员一致: %v vs %v", momSpaces, adminSpaces)
	}
	if momSpaces["personal"] == adminSpaces["personal"] {
		t.Fatalf("成员个人空间不应与管理员相同")
	}
}

// ---------- 同步生命周期 ----------

func TestSyncLifecycle(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	id := uuidOf("memo-01")

	// 1. 新建
	_, r1 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "第一条", 0, nil)})
	if r1.Results[0].Status != "ok" || r1.Results[0].Version != 1 {
		t.Fatalf("新建应 ok v1: %+v", r1.Results[0])
	}
	// 2. 幂等重复推送同 base=0 → id 已存在 → base_version 0 != 1 → conflict（防重复创建）
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "第一条", 0, nil)})
	if r2.Results[0].Status != "conflict" {
		t.Fatalf("重复创建应 conflict: %+v", r2.Results[0])
	}
	// 3. 正确 base 更新
	_, r3 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "第一条-改", 1, func(m *store.Memo) {
		m.Pinned = true
	})})
	if r3.Results[0].Status != "ok" || r3.Results[0].Version != 2 {
		t.Fatalf("更新应 ok v2: %+v", r3.Results[0])
	}
	// 4. 过期 base 更新 → conflict + 返回服务端版本
	_, r4 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "旧编辑", 1, nil)})
	if r4.Results[0].Status != "conflict" || r4.Results[0].Memo == nil || r4.Results[0].Memo.Title != "第一条-改" {
		t.Fatalf("冲突应返回服务端版本: %+v", r4.Results[0])
	}
	// 5. 删除（当前 base=2）
	del := time.Now().UnixMilli()
	_, r5 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "", 2, func(m *store.Memo) {
		m.DeletedAt = &del
	})})
	if r5.Results[0].Status != "ok" {
		t.Fatalf("删除应 ok: %+v", r5.Results[0])
	}
	// 6. pull 能看到墓碑
	_, p := e.pull(e.adminToken, 0)
	var tomb *store.ChangeEntry
	for i := range p.Changes {
		if p.Changes[i].Memo != nil && p.Changes[i].Memo.ID == id {
			tomb = &p.Changes[i]
		}
	}
	if tomb == nil || tomb.Memo.DeletedAt == nil {
		t.Fatalf("pull 应含删除墓碑: %+v", p.Changes)
	}
	// 7. 游标推进：since=next_cursor 应为空
	_, p2 := e.pull(e.adminToken, p.NextCursor)
	if len(p2.Changes) != 0 {
		t.Fatalf("游标后应为空增量: %+v", p2.Changes)
	}
}

func TestPushUnknownBase(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	id := uuidOf("ghost-x")
	_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "幽灵", 5, nil)})
	if r.Results[0].Status != "conflict" || r.Results[0].Memo != nil {
		t.Fatalf("未知基线应 conflict 且无服务端版本: %+v", r.Results[0])
	}
}

func TestPullPagination(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	var inputs []store.PushInput
	for i := 0; i < 30; i++ {
		inputs = append(inputs, memoInput(uuidOf(fmt.Sprintf("page-%03d", i)), sp["family"], fmt.Sprintf("备忘%d", i), 0, nil))
	}
	e.push(e.adminToken, inputs)
	// limit=10 逐页拉
	var cursor int64
	count := 0
	for {
		code, out := e.do("GET", fmt.Sprintf("/api/v1/sync/pull?since=%d&limit=10", cursor), e.adminToken, nil)
		if code != 200 {
			t.Fatalf("pull: %d", code)
		}
		var r struct {
			Changes    []store.ChangeEntry `json:"changes"`
			NextCursor int64               `json:"next_cursor"`
		}
		_ = json.Unmarshal(out, &r)
		if len(r.Changes) == 0 {
			break
		}
		count += len(r.Changes)
		if r.NextCursor <= cursor {
			t.Fatalf("游标必须单调递增: %d -> %d", cursor, r.NextCursor)
		}
		cursor = r.NextCursor
		if count > 100 {
			t.Fatal("分页未收敛")
		}
	}
	if count != 30 {
		t.Fatalf("应拉到 30 条，得到 %d", count)
	}
}

// ---------- 权限隔离 ----------

func TestPersonalSpaceIsolation(t *testing.T) {
	e := newEnv(t)
	adminSp := e.spaces(e.adminToken)
	e.createUser("mom", "pass1234", "妈妈")
	momTok := e.login("mom", "pass1234")
	momSp := e.spaces(momTok)

	// 管理员私人备忘录
	_, r1 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("priv-a"), adminSp["personal"], "管理员的私事", 0, nil)})
	if r1.Results[0].Status != "ok" {
		t.Fatalf("私人备忘录创建失败: %+v", r1.Results[0])
	}
	// 家庭备忘录
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("fam-a"), adminSp["family"], "全家可见", 0, nil)})
	if r2.Results[0].Status != "ok" {
		t.Fatalf("家庭备忘录创建失败: %+v", r2.Results[0])
	}
	// 成员拉取：只能看到家庭那条
	_, p := e.pull(momTok, 0)
	for _, ch := range p.Changes {
		if ch.Memo != nil && ch.Memo.ID == uuidOf("priv-a") {
			t.Fatal("成员不应看到他人私人空间的备忘录")
		}
	}
	found := false
	for _, ch := range p.Changes {
		if ch.Memo != nil && ch.Memo.ID == uuidOf("fam-a") {
			found = true
		}
	}
	if !found {
		t.Fatal("成员应看到家庭空间的备忘录")
	}
	// 成员不能写管理员的私人空间
	_, r3 := e.push(momTok, []store.PushInput{memoInput(uuidOf("hack-1"), adminSp["personal"], "越权", 0, nil)})
	if r3.Results[0].Status != "error" {
		t.Fatalf("越权写入应 error: %+v", r3.Results[0])
	}
	// 管理员不能写成员的私人空间
	_, r4 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("hack-2"), momSp["personal"], "越权", 0, nil)})
	if r4.Results[0].Status != "error" {
		t.Fatalf("越权写入应 error: %+v", r4.Results[0])
	}
}

// ---------- 附件 ----------

func (e *env) upload(data []byte, filename string) string {
	e.t.Helper()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	fw, _ := w.CreateFormFile("file", filename)
	fw.Write(data)
	w.Close()
	req, _ := http.NewRequest("POST", e.ts.URL+"/api/v1/attachments", &buf)
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+e.adminToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatalf("upload: %v", err)
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		e.t.Fatalf("upload: %d %s", resp.StatusCode, out)
	}
	var r struct {
		ID     string `json:"id"`
		Sha256 string `json:"sha256"`
		Size   int64  `json:"size"`
	}
	_ = json.Unmarshal(out, &r)
	want := sha256.Sum256(data)
	if r.Sha256 != hex.EncodeToString(want[:]) {
		e.t.Fatalf("sha 不符: %s", r.Sha256)
	}
	if int64(len(data)) != r.Size {
		e.t.Fatalf("size 不符: %d != %d", r.Size, len(data))
	}
	return r.ID
}

func TestAttachmentFlow(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	data := []byte("fake-png-bytes-0123456789")

	sha1 := e.upload(data, "a.png")
	sha2 := e.upload(data, "b.png") // 同内容 → 同 sha（去重）
	if sha1 != sha2 {
		t.Fatal("同内容附件应去重为同一 sha")
	}

	// check 秒传
	code, out := e.do("POST", "/api/v1/attachments/check", e.adminToken, map[string]any{
		"sha256s": []string{sha1, strings.Repeat("ab", 32)},
	})
	if code != 200 {
		t.Fatalf("check: %d %s", code, out)
	}
	var ck struct {
		Existing map[string]int64 `json:"existing"`
	}
	_ = json.Unmarshal(out, &ck)
	if ck.Existing[sha1] != int64(len(data)) || len(ck.Existing) != 1 {
		t.Fatalf("check 结果不对: %+v", ck.Existing)
	}

	// 带附件的备忘录
	_, r := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("att-memo"), sp["family"], "有图", 0, func(m *store.Memo) {
		m.Attachments = []store.Attachment{{ID: sha1, Sha256: sha1, Filename: "a.png", Mime: "image/png", Size: int64(len(data))}}
	})})
	if r.Results[0].Status != "ok" {
		t.Fatalf("带附件推送失败: %+v", r.Results[0])
	}

	// 引用未上传的 sha → error
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("att-bad"), sp["family"], "坏图", 0, func(m *store.Memo) {
		m.Attachments = []store.Attachment{{ID: strings.Repeat("cd", 32), Sha256: strings.Repeat("cd", 32), Filename: "x.png", Size: 1}}
	})})
	if r2.Results[0].Status != "error" {
		t.Fatalf("未上传附件应 error: %+v", r2.Results[0])
	}

	// pull 返回附件元数据
	_, p := e.pull(e.adminToken, 0)
	var got *store.Memo
	for _, ch := range p.Changes {
		if ch.Memo != nil && ch.Memo.ID == uuidOf("att-memo") {
			got = ch.Memo
		}
	}
	if got == nil || len(got.Attachments) != 1 || got.Attachments[0].Sha256 != sha1 {
		t.Fatalf("pull 应返回附件元数据: %+v", got)
	}

	// 下载与 ETag 304
	req, _ := http.NewRequest("GET", e.ts.URL+"/api/v1/attachments/"+sha1, nil)
	req.Header.Set("Authorization", "Bearer "+e.adminToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !bytes.Equal(body, data) {
		t.Fatal("下载内容不符")
	}
	etag := resp.Header.Get("ETag")
	req2, _ := http.NewRequest("GET", e.ts.URL+"/api/v1/attachments/"+sha1, nil)
	req2.Header.Set("Authorization", "Bearer "+e.adminToken)
	req2.Header.Set("If-None-Match", etag)
	resp2, _ := http.DefaultClient.Do(req2)
	if resp2.StatusCode != 304 {
		t.Fatalf("ETag 应命中 304: %d", resp2.StatusCode)
	}
	resp2.Body.Close()

	// 非法 id → 400
	if code, _ := e.do("GET", "/api/v1/attachments/not-a-sha", e.adminToken, nil); code != 400 {
		t.Fatalf("非法 sha 应 400: %d", code)
	}
}

// ---------- 配对码 ----------

func TestPairCode(t *testing.T) {
	e := newEnv(t)
	momID := e.createUser("mom", "pass1234", "妈妈")

	code, out := e.do("POST", "/api/v1/admin/pair-code", e.adminToken, map[string]any{
		"user_id": momID, "ttl_hours": 24,
	})
	if code != 200 {
		t.Fatalf("生成配对码: %d %s", code, out)
	}
	var pc struct {
		Code string `json:"code"`
		URL  string `json:"url"`
	}
	_ = json.Unmarshal(out, &pc)
	if len(pc.Code) != 8 || pc.URL == "" {
		t.Fatalf("配对码格式不对: %+v", pc)
	}

	// 兑换
	code2, out2 := e.do("POST", "/api/v1/auth/pair", "", map[string]string{
		"code": strings.ToLower(pc.Code), "device_name": "妈妈的手机",
	})
	if code2 != 200 {
		t.Fatalf("兑换配对码(小写也应成功): %d %s", code2, out2)
	}
	var pr struct {
		Token string `json:"token"`
		User  struct {
			Username string `json:"username"`
		} `json:"user"`
	}
	_ = json.Unmarshal(out2, &pr)
	if pr.User.Username != "mom" || pr.Token == "" {
		t.Fatalf("兑换结果不对: %s", out2)
	}
	// token 可用
	if c, _ := e.do("GET", "/api/v1/auth/me", pr.Token, nil); c != 200 {
		t.Fatalf("配对 token 应可用: %d", c)
	}
	// 一次性：再次兑换 → 400
	if c, _ := e.do("POST", "/api/v1/auth/pair", "", map[string]string{"code": pc.Code}); c != 400 {
		t.Fatalf("配对码应一次性: %d", c)
	}
	// 错误码
	if c, _ := e.do("POST", "/api/v1/auth/pair", "", map[string]string{"code": "AAAAAAAA"}); c != 400 {
		t.Fatalf("错误配对码应 400: %d", c)
	}
	// 成员不能生成配对码
	momTok := e.login("mom", "pass1234")
	if c, _ := e.do("POST", "/api/v1/admin/pair-code", momTok, map[string]any{"user_id": momID}); c != 403 {
		t.Fatalf("成员生成配对码应 403: %d", c)
	}
}

// ---------- 成员与设备管理 ----------

func TestUserManagement(t *testing.T) {
	e := newEnv(t)
	// 重名
	if code, _ := e.do("POST", "/api/v1/admin/users", e.adminToken, map[string]string{"username": "admin", "password": "12345", "nickname": "x", "role": "member"}); code != 400 {
		t.Fatalf("重名应 400: %d", code)
	}
	// 成员无管理权限
	e.createUser("mom", "pass1234", "妈妈")
	momTok := e.login("mom", "pass1234")
	if code, _ := e.do("GET", "/api/v1/admin/users", momTok, nil); code != 403 {
		t.Fatalf("成员访问管理接口应 403: %d", code)
	}
	// 不能删除最后的管理员
	adminID := ""
	code, out := e.do("GET", "/api/v1/admin/users", e.adminToken, nil)
	_ = code
	var list struct {
		Users []store.User `json:"users"`
	}
	_ = json.Unmarshal(out, &list)
	for _, u := range list.Users {
		if u.Role == "admin" {
			adminID = u.ID
		}
	}
	if c, _ := e.do("DELETE", "/api/v1/admin/users/"+adminID, e.adminToken, nil); c != 400 {
		t.Fatalf("删除最后管理员应 400: %d", c)
	}
	// 删除成员后其设备 token 失效
	e.do("DELETE", "/api/v1/admin/users/"+list.Users[len(list.Users)-1].ID, e.adminToken, nil)
	if c, _ := e.do("GET", "/api/v1/auth/me", momTok, nil); c != 401 {
		t.Fatalf("被删成员的 token 应失效: %d", c)
	}
	// 重置密码 → 旧设备全部下线，新密码可登录
	momID2 := e.createUser("mom2", "pass1234", "妈妈2")
	tok2 := e.login("mom2", "pass1234")
	if c, _ := e.do("GET", "/api/v1/auth/me", tok2, nil); c != 200 {
		t.Fatalf("登录应有效: %d", c)
	}
	if c, _ := e.do("POST", "/api/v1/admin/users/"+momID2+"/password", e.adminToken, map[string]string{"password": "newpass99"}); c != 200 {
		t.Fatalf("重置密码失败")
	}
	if c, _ := e.do("GET", "/api/v1/auth/me", tok2, nil); c != 401 {
		t.Fatalf("重置密码后旧 token 应失效: %d", c)
	}
	if c, _ := e.do("POST", "/api/v1/auth/login", "", map[string]string{"username": "mom2", "password": "newpass99"}); c != 200 {
		t.Fatalf("新密码应可登录: %d", c)
	}
	// 修改自己密码
	tok3 := e.login("mom2", "newpass99")
	if c, _ := e.do("POST", "/api/v1/auth/password", tok3, map[string]string{"old_password": "wrong", "new_password": "abcdef1"}); c != 400 {
		t.Fatalf("旧密码错误应 400: %d", c)
	}
	if c, _ := e.do("POST", "/api/v1/auth/password", tok3, map[string]string{"old_password": "newpass99", "new_password": "abcdef1"}); c != 200 {
		t.Fatalf("修改密码应成功: %d", c)
	}
	if c, _ := e.do("POST", "/api/v1/auth/login", "", map[string]string{"username": "mom2", "password": "abcdef1"}); c != 200 {
		t.Fatalf("修改后新密码应可登录")
	}
}

// ---------- 备份与导出 ----------

func TestBackupAndExport(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	data := []byte("attachment-for-export-test")
	sha := e.upload(data, "x.bin")
	e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("exp-memo"), sp["family"], "导出测试", 0, func(m *store.Memo) {
		m.Attachments = []store.Attachment{{ID: sha, Sha256: sha, Filename: "x.bin", Size: int64(len(data))}}
	})})

	// 备份
	code, out := e.do("POST", "/api/v1/admin/backup", e.adminToken, nil)
	if code != 200 {
		t.Fatalf("备份失败: %d %s", code, out)
	}
	var br struct{ Name string }
	_ = json.Unmarshal(out, &br)
	if !strings.HasPrefix(br.Name, "backup-") || !strings.HasSuffix(br.Name, ".db") {
		t.Fatalf("备份名应形如 backup-xxx.db: %s", br.Name)
	}
	if _, err := os.Stat(filepath.Join(e.st.DataDir(), "backup", br.Name)); err != nil {
		t.Fatalf("备份文件不存在: %v", err)
	}
	// 列表
	code, out = e.do("GET", "/api/v1/admin/backups", e.adminToken, nil)
	if code != 200 || !strings.Contains(string(out), br.Name) {
		t.Fatalf("备份列表应含刚建的备份: %d %s", code, out)
	}

	// 导出 zip
	req, _ := http.NewRequest("GET", e.ts.URL+"/api/v1/admin/export", nil)
	req.Header.Set("Authorization", "Bearer "+e.adminToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	zipBytes, _ := io.ReadAll(resp.Body)
	zr, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		t.Fatalf("导出不是合法 zip: %v", err)
	}
	names := map[string]bool{}
	for _, f := range zr.File {
		names[f.Name] = true
	}
	if !names["memos.json"] || !names["attachments/"+sha] {
		t.Fatalf("导出包缺少内容: %v", names)
	}
}

// ---------- 并发 ----------

func TestConcurrentPushDistinctMemos(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	const workers, per = 8, 10
	var wg sync.WaitGroup
	errCh := make(chan error, workers)
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			var inputs []store.PushInput
			for i := 0; i < per; i++ {
				inputs = append(inputs, memoInput(uuidOf(fmt.Sprintf("c%02d-%02d", w, i)), sp["family"], "并发", 0, nil))
			}
			_, r := e.push(e.adminToken, inputs)
			for _, res := range r.Results {
				if res.Status != "ok" {
					errCh <- fmt.Errorf("并发推送失败: %+v", res)
					return
				}
			}
		}(w)
	}
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Fatal(err)
	}
	_, p := e.pull(e.adminToken, 0)
	if len(p.Changes) != workers*per {
		t.Fatalf("应收到 %d 条变更，实际 %d", workers*per, len(p.Changes))
	}
}

func TestConcurrentSameMemo(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	id := uuidOf("race-memo")
	if _, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "初始", 0, nil)}); r.Results[0].Status != "ok" {
		t.Fatal("初始创建失败")
	}
	const n = 6
	var wg sync.WaitGroup
	okCount := make(chan int, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], fmt.Sprintf("竞争%d", i), 1, nil)})
			if r.Results[0].Status == "ok" {
				okCount <- 1
			}
		}(i)
	}
	wg.Wait()
	close(okCount)
	wins := len(okCount)
	if wins != 1 {
		t.Fatalf("同 base 并发更新应恰好 1 个成功，实际 %d", wins)
	}
}

// ---------- 清理（store 层） ----------

func TestCleanupPurgesAndTombstones(t *testing.T) {
	newEnvStore := func(t *testing.T) (*store.Store, string) {
		dir := t.TempDir()
		st, err := store.Open(dir)
		if err != nil {
			t.Fatalf("open: %v", err)
		}
		t.Cleanup(func() { st.Close() })
		if _, err := st.BootstrapAdmin("admin", "admin-pass-123"); err != nil {
			t.Fatal(err)
		}
		tok, u, err := st.Login("admin", "admin-pass-123", "test")
		if err != nil {
			t.Fatal(err)
		}
		_ = tok
		return st, u.ID
	}
	st, adminID := newEnvStore(t)

	spaces, _ := st.SpacesForUser(adminID)
	family := ""
	for _, sp := range spaces {
		if sp.Type == "family" {
			family = sp.ID
		}
	}
	id := uuidOf("clean-memo")
	res, err := st.Push(adminID, []store.PushInput{memoPush(id, family)})
	if err != nil || res[0].Status != "ok" {
		t.Fatalf("创建失败: %+v err=%v", res, err)
	}
	// 软删
	del := time.Now().AddDate(0, 0, -31).UnixMilli()
	res, err = st.Push(adminID, []store.PushInput{{
		Memo: store.Memo{ID: id, SpaceID: family, Type: "note", Title: "x", Content: "{}", ClientMtime: time.Now().UnixMilli(), DeletedAt: &del},
		BaseVersion: 1,
	}})
	if err != nil || res[0].Status != "ok" {
		t.Fatalf("删除失败: %+v err=%v", res, err)
	}
	// 清理
	if err := st.Cleanup(); err != nil {
		t.Fatalf("cleanup: %v", err)
	}
	// pull 应返回 memo=nil 的墓碑（空间过滤仍生效）
	entries, _, _, err := st.Pull(adminID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	var tomb *store.ChangeEntry
	for i := range entries {
		if entries[i].Memo == nil || entries[i].Memo.ID == id {
			e := entries[i]
			tomb = &e
		}
	}
	if tomb == nil || tomb.Memo != nil {
		t.Fatalf("清理后应下发 memo=nil 墓碑: %+v", tomb)
	}
}

func memoPush(id, family string) store.PushInput {
	return store.PushInput{Memo: store.Memo{
		ID: id, SpaceID: family, Type: "note", Title: "待清理", Content: "{}",
		ClientMtime: time.Now().UnixMilli(),
	}, BaseVersion: 0}
}

// ---------- 数字资产（type=asset） ----------

func TestAssetMemoSync(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	e.createUser("mom", "pass1234", "妈妈")
	momTok := e.login("mom", "pass1234")

	id := uuidOf("iqiyi-vip")
	content := `{"type":"asset","text":"","items":[],"category":"video","account":"13800138000","password":"vip-pass","extra":"妈妈手机号开的，自动续费","expires_at":1798761600000,"remind_before":7}`
	_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "爱奇艺黄金VIP", 0, func(m *store.Memo) {
		m.Type = "asset"
		m.Content = content
		exp := int64(1798761600000)
		m.RemindAt = &exp
	})})
	if r.Results[0].Status != "ok" {
		t.Fatalf("asset 备忘录推送失败: %+v", r.Results[0])
	}
	// 成员同步可见，结构化字段完整透传
	_, p := e.pull(momTok, 0)
	var got *store.Memo
	for _, ch := range p.Changes {
		if ch.Memo != nil && ch.Memo.ID == id {
			got = ch.Memo
		}
	}
	if got == nil {
		t.Fatal("成员应能拉到资产备忘录")
	}
	for _, want := range []string{`"account":"13800138000"`, `"password":"vip-pass"`, `"expires_at":1798761600000`} {
		if !strings.Contains(got.Content, want) {
			t.Fatalf("资产内容缺字段 %s: %s", want, got.Content)
		}
	}
	// 非法类型仍被拒
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("bad-type"), sp["family"], "x", 0, func(m *store.Memo) {
		m.Type = "hacker"
	})})
	if r2.Results[0].Status != "error" {
		t.Fatalf("非法 type 应 error: %+v", r2.Results[0])
	}
}

// ---------- 清单条目级合并 ----------

func clJSON(items [][2]any) string {
	// items: {{text, done}}
	arr := make([]string, 0, len(items))
	for _, it := range items {
		done := "false"
		if it[1] == true {
			done = "true"
		}
		arr = append(arr, fmt.Sprintf(`{"text":"%v","done":%s}`, it[0], done))
	}
	return `{"type":"checklist","text":"","items":[` + strings.Join(arr, ",") + `]}`
}

func TestChecklistMerge(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	id := uuidOf("shopping-list")

	// v1：初始清单 [牛奶, 鸡蛋]
	content := clJSON([][2]any{{"牛奶", false}, {"鸡蛋", false}})
	_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "购物清单", 0, func(m *store.Memo) {
		m.Type = "checklist"
		m.Content = content
	})})
	if r.Results[0].Status != "ok" {
		t.Fatalf("初始清单创建失败: %+v", r.Results[0])
	}

	// 成员基于 v1 勾选牛奶 → 正常更新 v2
	e.createUser("mom", "pass1234", "妈妈")
	momTok := e.login("mom", "pass1234")
	_, r2 := e.push(momTok, []store.PushInput{memoInput(id, sp["family"], "购物清单", 1, func(m *store.Memo) {
		m.Type = "checklist"
		m.Content = clJSON([][2]any{{"牛奶", true}, {"鸡蛋", false}})
	})})
	if r2.Results[0].Status != "ok" || r2.Results[0].Version != 2 {
		t.Fatalf("成员勾选应 ok v2: %+v", r2.Results[0])
	}

	// 管理员仍基于 v1 增加水果（过期基线）→ 条目合并而不是冲突
	_, r3 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "购物清单", 1, func(m *store.Memo) {
		m.Type = "checklist"
		m.Content = clJSON([][2]any{{"牛奶", false}, {"鸡蛋", false}, {"水果", false}})
	})})
	if r3.Results[0].Status != "ok" {
		t.Fatalf("清单过期基线应合并成功而不是 conflict: %+v", r3.Results[0])
	}
	merged := r3.Results[0].Memo
	if merged == nil {
		t.Fatal("合并结果应回传 memo")
	}
	// 合并语义：牛奶勾选保留（或）、鸡蛋保留、水果追加
	for _, want := range []string{`{"text":"牛奶","done":true}`, `{"text":"鸡蛋"`, `{"text":"水果","done":false}`} {
		if !strings.Contains(merged.Content, want) {
			t.Fatalf("合并结果缺 %s: %s", want, merged.Content)
		}
	}
	// 单端删除不可识别：成员用过期基线(base=2)推送不含鸡蛋的版本 → 走合并路径，鸡蛋仍在
	_, r4 := e.push(momTok, []store.PushInput{memoInput(id, sp["family"], "购物清单", 2, func(m *store.Memo) {
		m.Type = "checklist"
		m.Content = clJSON([][2]any{{"牛奶", true}, {"水果", false}})
	})})
	if r4.Results[0].Status != "ok" || !strings.Contains(r4.Results[0].Memo.Content, `"鸡蛋"`) {
		t.Fatalf("合并不应丢弃条目: %+v", r4.Results[0])
	}
	// 笔记类型过期基线仍走冲突（不受合并影响）
	noteID := uuidOf("plain-note")
	e.push(e.adminToken, []store.PushInput{memoInput(noteID, sp["family"], "笔记", 0, nil)})
	_, r5 := e.push(e.adminToken, []store.PushInput{memoInput(noteID, sp["family"], "笔记v2", 1, nil)})
	if r5.Results[0].Status != "ok" {
		t.Fatal("准备笔记 v2 失败")
	}
	_, r6 := e.push(momTok, []store.PushInput{memoInput(noteID, sp["family"], "过期的笔记编辑", 1, nil)})
	if r6.Results[0].Status != "conflict" {
		t.Fatalf("笔记过期基线应 conflict: %+v", r6.Results[0])
	}
}

// ---------- 标签 ----------

func TestTags(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	id := uuidOf("tagged")

	_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "带标签", 0, func(m *store.Memo) {
		m.Tags = []string{"家用", " 购物 ", "家用", "随手记", "", strings.Repeat("超长标签", 10)}
	})})
	if r.Results[0].Status != "ok" {
		t.Fatalf("带标签推送失败: %+v", r.Results[0])
	}
	if got := r.Results[0].Memo.Tags; len(got) != 4 {
		t.Fatalf("标签应去空/去重/截断后为 4 个（含超长截断）: %v", got)
	}
	if got := r.Results[0].Memo.Tags[1]; got != "购物" {
		t.Fatalf("标签应去首尾空格: %q", got)
	}
	// pull 透传标签
	_, p := e.pull(e.adminToken, 0)
	var got []string
	for _, ch := range p.Changes {
		if ch.Memo != nil && ch.Memo.ID == id {
			got = ch.Memo.Tags
		}
	}
	if len(got) != 4 || got[0] != "家用" {
		t.Fatalf("pull 应返回清洗后的标签: %v", got)
	}
	// 超过 10 个标签被限量
	var many []string
	for i := 0; i < 15; i++ {
		many = append(many, fmt.Sprintf("标签%d", i))
	}
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("many-tags"), sp["family"], "多标签", 0, func(m *store.Memo) {
		m.Tags = many
	})})
	if len(r2.Results[0].Memo.Tags) != 10 {
		t.Fatalf("标签应限量 10 个: %d", len(r2.Results[0].Memo.Tags))
	}
	// 更新标签
	_, r3 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "带标签", 1, func(m *store.Memo) {
		m.Tags = []string{"新标签"}
	})})
	if r3.Results[0].Status != "ok" || len(r3.Results[0].Memo.Tags) != 1 || r3.Results[0].Memo.Tags[0] != "新标签" {
		t.Fatalf("标签更新失败: %+v", r3.Results[0])
	}
}

// ---------- SSE 实时推送 ----------

func TestSSEBroadcast(t *testing.T) {
	e := newEnv(t)
	req, _ := http.NewRequest("GET", e.ts.URL+"/api/v1/sync/events", nil)
	req.Header.Set("Authorization", "Bearer "+e.adminToken)
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	resp, err := http.DefaultClient.Do(req.WithContext(ctx))
	if err != nil {
		t.Fatalf("订阅 SSE: %v", err)
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("SSE Content-Type 不对: %s", ct)
	}
	got := make(chan string, 1)
	go func() {
		sc := bufio.NewScanner(resp.Body)
		for sc.Scan() {
			if sc.Text() == "event: change" {
				got <- "change"
				return
			}
		}
	}()
	time.Sleep(300 * time.Millisecond) // 等订阅在服务端建立
	sp := e.spaces(e.adminToken)
	e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("sse-memo"), sp["family"], "实时推送", 0, nil)})
	select {
	case <-got: // 收到即通过
	case <-time.After(3 * time.Second):
		t.Fatal("3 秒内未收到 SSE change 事件")
	}
	// 未认证订阅应 401
	if code, _ := e.do("GET", "/api/v1/sync/events", "", nil); code != 401 {
		t.Fatalf("未认证 SSE 应 401: %d", code)
	}
}

// ---------- 备份恢复 ----------

func TestRestoreFlow(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	// 备份点：只有 A
	_, r1 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("restore-a"), sp["family"], "备份前就有", 0, nil)})
	if r1.Results[0].Status != "ok" {
		t.Fatal("创建 A 失败")
	}
	code, out := e.do("POST", "/api/v1/admin/backup", e.adminToken, nil)
	if code != 200 {
		t.Fatalf("备份失败: %d %s", code, out)
	}
	var br struct{ Name string }
	_ = json.Unmarshal(out, &br)
	// 备份之后新增 B
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("restore-b"), sp["family"], "备份后新增", 0, nil)})
	if r2.Results[0].Status != "ok" {
		t.Fatal("创建 B 失败")
	}
	// 缺 confirm 应 400
	if c, _ := e.do("POST", "/api/v1/admin/restore", e.adminToken, map[string]any{"name": br.Name}); c != 400 {
		t.Fatalf("缺少 confirm 应 400: %d", c)
	}
	// 发起恢复
	code, out = e.do("POST", "/api/v1/admin/restore", e.adminToken, map[string]any{"name": br.Name, "confirm": true})
	if code != 200 {
		t.Fatalf("恢复准备失败: %d %s", code, out)
	}
	// 非法备份名应 400
	if c, _ := e.do("POST", "/api/v1/admin/restore", e.adminToken, map[string]any{"name": "../evil", "confirm": true}); c != 400 {
		t.Fatalf("路径穿越应 400: %d", c)
	}
	// 模拟重启：关库 → RestoreIfPending → 重开
	dataDir := e.st.DataDir()
	if err := e.st.Close(); err != nil {
		t.Fatalf("关闭旧库: %v", err)
	}
	restored, err := store.RestoreIfPending(dataDir)
	if err != nil || !restored {
		t.Fatalf("应执行恢复: restored=%v err=%v", restored, err)
	}
	st2, err := store.Open(dataDir)
	if err != nil {
		t.Fatalf("重开库: %v", err)
	}
	defer st2.Close()
	var nA, nB int
	if err := st2.DB().QueryRow(`SELECT COUNT(*) FROM memos WHERE id=?`, uuidOf("restore-a")).Scan(&nA); err != nil {
		t.Fatal(err)
	}
	if err := st2.DB().QueryRow(`SELECT COUNT(*) FROM memos WHERE id=?`, uuidOf("restore-b")).Scan(&nB); err != nil {
		t.Fatal(err)
	}
	if nA != 1 || nB != 0 {
		t.Fatalf("恢复后应回到备份点：A=%d B=%d", nA, nB)
	}
	// 再次启动无 pending 时应返回 false
	if restored, err := store.RestoreIfPending(dataDir); err != nil || restored {
		t.Fatalf("无 pending 不应重复恢复: %v %v", restored, err)
	}
}

// ---------- 端口切换与旧端口跳转 ----------

func TestSetPortAndRedirect(t *testing.T) {
	e := newEnv(t)
	cfgPath := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(cfgPath, []byte("listen: \":15270\"\ndata_dir: \"x\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	api.ConfigFilePath = cfgPath
	api.Restart = func() {} // 测试中不真重启
	defer func() { api.ConfigFilePath = ""; api.Restart = nil }()

	// 合法改端口：写配置
	code, out := e.do("POST", "/api/v1/admin/port", e.adminToken, map[string]any{"port": 15271})
	if code != 200 {
		t.Fatalf("改端口失败: %d %s", code, out)
	}
	// 首次改端口：测试环境主端口解析为 0，不应产生跳转列表
	data, rerr := os.ReadFile(cfgPath)
	if rerr != nil {
		t.Fatalf("读配置失败: %v", rerr)
	}
	if !strings.Contains(string(data), `listen: ":15271"`) || strings.Contains(string(data), "redirect_ports") {
		t.Fatalf("首次改端口配置不正确:\n%s", data)
	}
	// 二次改端口：15271 进入跳转列表
	if c, o := e.do("POST", "/api/v1/admin/port", e.adminToken, map[string]any{"port": 15272}); c != 200 {
		t.Fatalf("二次改端口失败: %d %s", c, o)
	}
	data, rerr = os.ReadFile(cfgPath)
	if rerr != nil {
		t.Fatalf("读配置失败: %v", rerr)
	}
	if !strings.Contains(string(data), `listen: ":15272"`) || !strings.Contains(string(data), "redirect_ports: [15271]") {
		t.Fatalf("二次改端口应写入跳转列表:\n%s", data)
	}
	// 响应含提示
	if !strings.Contains(string(out), "15271") {
		t.Fatalf("响应应含新端口: %s", out)
	}
	// 重复相同端口（当前已是 15272）→ 400
	if c, _ := e.do("POST", "/api/v1/admin/port", e.adminToken, map[string]any{"port": 15272}); c != 400 {
		t.Fatalf("相同端口应 400: %d", c)
	}
	// 非法端口 → 400
	if c, _ := e.do("POST", "/api/v1/admin/port", e.adminToken, map[string]any{"port": 80}); c != 400 {
		t.Fatalf("特权端口应 400: %d", c)
	}
	// 未认证 → 401
	if c, _ := e.do("POST", "/api/v1/admin/port", "", map[string]any{"port": 15272}); c != 401 {
		t.Fatalf("未认证应 401: %d", c)
	}

	// 旧端口 302 跳转器：访问 15270 → 302 到 15271（保留路径）
	api.StartRedirects(config.Config{Listen: ":15271", RedirectPorts: []int{15270}},
		func(msg string, args ...any) {})
	time.Sleep(500 * time.Millisecond)
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse // 不自动跟随 302
	}}
	resp, err := client.Get("http://127.0.0.1:15270/some/path")
	if err != nil {
		t.Fatalf("访问跳转端口: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusTemporaryRedirect {
		t.Fatalf("应 302，得到 %d", resp.StatusCode)
	}
	if loc := resp.Header.Get("Location"); !strings.HasSuffix(loc, ":15271/some/path") {
		t.Fatalf("Location 应指向新端口并保留路径: %q", loc)
	}
}

// ---------- 健康检查：管理页 ----------

func TestAdminPageServed(t *testing.T) {
	e := newEnv(t)
	resp, err := http.Get(e.ts.URL + "/")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || !bytes.Contains(body, []byte("家庭备忘录")) {
		t.Fatalf("管理页应可访问: %d", resp.StatusCode)
	}
}
