package api_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"memosync/internal/store"
)

// TestReminderRescheduledFiresAgain 改提醒时间后应重新触发（不能因为"已提醒"而永久沉默）。
func TestReminderRescheduledFiresAgain(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)

	var hits int
	hook := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		w.Write([]byte(`{"code":0}`))
	}))
	defer hook.Close()
	if c, _ := e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{"feishu_webhook": hook.URL}); c != 200 {
		t.Fatal("配置失败")
	}

	id := uuidOf("reschedule")
	past1 := time.Now().Add(-2 * time.Minute).UnixMilli()
	e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "会改时间的提醒", 0, func(m *store.Memo) {
		m.RemindAts = []int64{past1}
	})})
	e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	if hits != 1 {
		t.Fatalf("首次应触发 1 次，实际 %d", hits)
	}
	// 重复扫描不应再触发
	e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	if hits != 1 {
		t.Fatalf("已提醒不应重复，实际 %d", hits)
	}
	// 用户改了提醒时间（新时间也已到点）→ 应重新提醒
	past2 := time.Now().Add(-time.Minute).UnixMilli()
	if _, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "会改时间的提醒", 1, func(m *store.Memo) {
		m.RemindAts = []int64{past2}
	})}); r.Results[0].Status != "ok" {
		t.Fatal("改时间失败")
	}
	e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	if hits != 2 {
		t.Fatalf("改时间后应重新提醒，实际触发 %d 次", hits)
	}
}

// TestNotifyConfigPartialUpdate 增量更新：只改一个渠道不能清空另一个。
func TestNotifyConfigPartialUpdate(t *testing.T) {
	e := newEnv(t)
	if c, _ := e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{
		"feishu_webhook":   "https://example.com/feishu",
		"dingtalk_webhook": "https://example.com/ding",
		"dingtalk_secret":  "SECabc",
	}); c != 200 {
		t.Fatal("初始配置失败")
	}
	// 只更新飞书
	code, out := e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{
		"feishu_webhook": "https://example.com/feishu2",
	})
	if code != 200 {
		t.Fatalf("更新失败: %d %s", code, out)
	}
	var cfg store.NotifyConfig
	_ = json.Unmarshal(out, &cfg)
	if cfg.DingtalkWebhook != "https://example.com/ding" || cfg.DingtalkSecret != "SECabc" {
		t.Fatalf("未提交的字段应保留: %+v", cfg)
	}
	if cfg.FeishuWebhook != "https://example.com/feishu2" {
		t.Fatalf("提交的字段应更新: %+v", cfg)
	}
	// 传空串清除
	e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{"dingtalk_webhook": ""})
	_, out = e.do("GET", "/api/v1/admin/notify", e.adminToken, nil)
	_ = json.Unmarshal(out, &cfg)
	if cfg.DingtalkWebhook != "" {
		t.Fatalf("空串应清除配置: %+v", cfg)
	}
}

// TestReminderOfDeletedMemoNotFired 已删除的备忘录不再推送提醒。
func TestReminderOfDeletedMemoNotFired(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	var hits int
	hook := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		w.Write([]byte(`{"code":0}`))
	}))
	defer hook.Close()
	e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{"feishu_webhook": hook.URL})

	id := uuidOf("deleted-remind")
	due := time.Now().Add(-time.Minute).UnixMilli()
	e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "删掉就别提醒", 0, func(m *store.Memo) {
		m.RemindAts = []int64{due}
	})})
	// 删除（软删）后再扫描
	del := time.Now().UnixMilli()
	e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "删掉就别提醒", 1, func(m *store.Memo) {
		m.RemindAt = nil
		m.DeletedAt = &del
	})})
	e.do("POST", "/api/v1/admin/notify/fire", e.adminToken, nil)
	if hits != 0 {
		t.Fatalf("已删除的备忘录不应推送提醒，实际 %d", hits)
	}
}
