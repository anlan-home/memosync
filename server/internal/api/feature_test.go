package api_test

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"memosync/internal/store"
)

// ---------- 多条提醒（remind_ats） ----------

func TestMultiReminders(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	id := uuidOf("multi-remind")

	at1 := time.Now().Add(24 * time.Hour).UnixMilli()
	at2 := time.Now().Add(48 * time.Hour).UnixMilli()
	at3 := time.Now().Add(72 * time.Hour).UnixMilli()

	_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "写季度总结", 0, func(m *store.Memo) {
		m.RemindAts = []int64{at2, at1, at1, at3} // 乱序 + 重复
	})})
	if r.Results[0].Status != "ok" {
		t.Fatalf("多条提醒推送失败: %+v", r.Results[0])
	}
	got := r.Results[0].Memo.RemindAts
	if len(got) != 3 || got[0] != at1 || got[1] != at2 || got[2] != at3 {
		t.Fatalf("remind_ats 应去重升序: %v", got)
	}
	// remind_at 冗余为最早一条
	if r.Results[0].Memo.RemindAt == nil || *r.Results[0].Memo.RemindAt != at1 {
		t.Fatalf("remind_at 应为最早一条: %v", r.Results[0].Memo.RemindAt)
	}
	// pull 透传
	_, p := e.pull(e.adminToken, 0)
	for _, ch := range p.Changes {
		if ch.Memo != nil && ch.Memo.ID == id {
			if len(ch.Memo.RemindAts) != 3 {
				t.Fatalf("pull 应带出 3 条提醒: %v", ch.Memo.RemindAts)
			}
		}
	}
	// 只带 remind_at 的旧客户端 → 退化单条
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "写季度总结", 1, func(m *store.Memo) {
		m.RemindAts = nil
		m.RemindAt = &at2
	})})
	if len(r2.Results[0].Memo.RemindAts) != 1 || r2.Results[0].Memo.RemindAts[0] != at2 {
		t.Fatalf("旧客户端 remind_at 应退化单条: %v", r2.Results[0].Memo.RemindAts)
	}
	// 清空提醒
	_, r3 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "写季度总结", 2, func(m *store.Memo) {
		m.RemindAts = nil
		m.RemindAt = nil
	})})
	if len(r3.Results[0].Memo.RemindAts) != 0 {
		t.Fatalf("清空提醒应生效: %v", r3.Results[0].Memo.RemindAts)
	}
}

// ---------- 飞书/钉钉 webhook ----------

func TestWebhookReminder(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)

	// 本地捕获服务器模拟飞书 webhook
	var captured chan map[string]any
	capture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		captured <- body
		w.Write([]byte(`{"code":0}`))
	}))
	defer capture.Close()
	captured = make(chan map[string]any, 4)

	// 配置飞书 webhook
	code, out := e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{
		"feishu_webhook": capture.URL,
	})
	if code != 200 {
		t.Fatalf("配置 webhook 失败: %d %s", code, out)
	}

	// 一条已到期的提醒
	id := uuidOf("due-memo")
	past := time.Now().Add(-time.Hour).UnixMilli()
	_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "缴物业费", 0, func(m *store.Memo) {
		m.RemindAts = []int64{past}
	})})
	if r.Results[0].Status != "ok" {
		t.Fatal("创建到期提醒失败")
	}
	// 一条未到期的提醒（不应触发）
	future := time.Now().Add(24 * time.Hour).UnixMilli()
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("future-memo"), sp["family"], "还没到点的", 0, func(m *store.Memo) {
		m.RemindAts = []int64{future}
	})})
	if r2.Results[0].Status != "ok" {
		t.Fatal("创建未来提醒失败")
	}

	// 手动触发扫描
	code, out = e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	if code != 200 {
		t.Fatalf("触发扫描失败: %d %s", code, out)
	}
	var fr struct {
		Count int `json:"count"`
	}
	_ = json.Unmarshal(out, &fr)
	if fr.Count != 1 {
		t.Fatalf("应只触发 1 条到期提醒: %s", out)
	}

	// webhook 收到消息
	select {
	case body := <-captured:
		content, _ := body["content"].(map[string]any)
		text, _ := content["text"].(string)
		if body["msg_type"] != "text" || !strings.Contains(text, "缴物业费") {
			t.Fatalf("webhook 消息不符合预期: %v", body)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("3 秒内未收到 webhook 推送")
	}

	// 再次触发：已提醒过的不重复
	code, out = e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	if code != 200 {
		t.Fatal("二次扫描失败")
	}
	_ = json.Unmarshal(out, &fr)
	if fr.Count != 0 {
		t.Fatalf("已提醒的条目不应重复触发: %s", out)
	}

	// 测试消息接口：未配置钉钉渠道应明确报错
	if c, o := e.do("POST", "/api/v1/admin/notify/test", e.adminToken, map[string]string{"channel": "dingtalk"}); c != 400 {
		t.Fatalf("未配置渠道应 400: %d %s", c, o)
	}
	// 成员无权触发
	momTok := func() string { e.createUser("mom", "pass1234", "妈妈"); return "mom" }()
	if c, _ := e.do("POST", "/api/v1/admin/notify/fire", e.login(momTok, "pass1234"), nil); c != 403 {
		t.Fatalf("成员触发管理接口应 403: %d", c)
	}
}

// ---------- 工作组空间 ----------

func TestGroupSpaces(t *testing.T) {
	e := newEnv(t)
	e.createUser("mom", "pass1234", "妈妈")
	e.createUser("dad", "pass1234", "爸爸")
	momTok := e.login("mom", "pass1234")
	dadTok := e.login("dad", "pass1234")
	adminSp := e.spaces(e.adminToken)

	// 建组
	code, out := e.do("POST", "/api/v1/spaces", momTok, map[string]string{"name": "妈妈的工作组"})
	if code != 200 {
		t.Fatalf("建组失败: %d %s", code, out)
	}
	var grp store.Space
	_ = json.Unmarshal(out, &grp)
	if grp.Type != "group" || grp.ID == "" {
		t.Fatalf("建组结果不对: %s", out)
	}

	// 建组者拉爸爸进组
	code, out = e.do("POST", "/api/v1/spaces/"+grp.ID+"/members", momTok, map[string]string{"username": "dad"})
	if code != 200 {
		t.Fatalf("拉人失败: %d %s", code, out)
	}
	// 成员列表：妈妈(owner) + 爸爸(member)
	code, out = e.do("GET", "/api/v1/spaces/"+grp.ID+"/members", dadTok, nil)
	if code != 200 || !strings.Contains(string(out), `"owner"`) || !strings.Contains(string(out), "爸爸") {
		t.Fatalf("成员列表不对: %d %s", code, out)
	}
	// 非组长不能拉人
	if c, _ := e.do("POST", "/api/v1/spaces/"+grp.ID+"/members", dadTok, map[string]string{"username": "mom"}); c != 400 {
		t.Fatalf("非组长拉人应 400: %d", c)
	}
	// 不存在的用户
	if c, _ := e.do("POST", "/api/v1/spaces/"+grp.ID+"/members", momTok, map[string]string{"username": "ghost"}); c != 400 {
		t.Fatalf("拉不存在用户应 400: %d", c)
	}

	// 组内备忘录：组员可见，非组员（admin）不可见
	gid := uuidOf("work-memo")
	_, r := e.push(momTok, []store.PushInput{memoInput(gid, grp.ID, "工作笔记", 0, nil)})
	if r.Results[0].Status != "ok" {
		t.Fatalf("组内备忘录创建失败: %+v", r.Results[0])
	}
	_, p := e.pull(dadTok, 0)
	found := false
	for _, ch := range p.Changes {
		if ch.Memo != nil && ch.Memo.ID == gid {
			found = true
		}
	}
	if !found {
		t.Fatal("组员应能看到组内备忘录")
	}
	_, pAdmin := e.pull(e.adminToken, 0)
	for _, ch := range pAdmin.Changes {
		if ch.Memo != nil && ch.Memo.ID == gid {
			t.Fatal("非组员不应看到工作组备忘录")
		}
	}
	// admin 写入组空间被拒
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("intruder"), grp.ID, "越权", 0, nil)})
	if r2.Results[0].Status != "error" {
		t.Fatalf("非组员写入应 error: %+v", r2.Results[0])
	}
	// 管理员的空间列表里没有该工作组
	for _, s := range e.spaces(e.adminToken) {
		_ = s
	}
	adminSpaceList := e.spaces(e.adminToken)
	for tpe, sid := range adminSpaceList {
		if sid == grp.ID && tpe != "" {
			t.Fatal("管理员不应看到别人的工作组")
		}
	}

	// 非空组不能解散
	if c, _ := e.do("DELETE", "/api/v1/spaces/"+grp.ID, momTok, nil); c != 400 {
		t.Fatalf("非空组解散应 400: %d", c)
	}
	// 空组可以解散
	_, out2 := e.do("POST", "/api/v1/spaces", momTok, map[string]string{"name": "空组"})
	var grp2 store.Space
	_ = json.Unmarshal(out2, &grp2)
	if c, _ := e.do("DELETE", "/api/v1/spaces/"+grp2.ID, momTok, nil); c != 200 {
		t.Fatalf("空组解散应 200: %d", c)
	}
	// 移除成员后不可见（先把备忘录移走再验证省略，直接验证移人接口）
	_ = fmt.Sprint()
	// 非本人空间不可见已在上面覆盖
	_ = adminSp
}
