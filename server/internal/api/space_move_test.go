package api_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"memosync/internal/store"
)

// ---------- 空间转换 ----------

// TestMoveMemoBetweenSpaces 备忘录在家庭/工作组/个人空间之间转换（需求：支持转换）。
func TestMoveMemoBetweenSpaces(t *testing.T) {
	e := newEnv(t)
	adminSp := e.spaces(e.adminToken)
	e.createUser("mom", "pass1234", "妈妈")
	momTok := e.login("mom", "pass1234")

	// 管理员在家庭空间建一条，转换到自己的个人空间
	id := uuidOf("movable")
	if _, r := e.push(e.adminToken, []store.PushInput{memoInput(id, adminSp["family"], "要转为私人的笔记", 0, nil)}); r.Results[0].Status != "ok" {
		t.Fatal("创建失败")
	}
	_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, adminSp["personal"], "要转为私人的笔记", 1, nil)})
	if r.Results[0].Status != "ok" {
		t.Fatalf("转换空间应成功: %+v", r.Results[0])
	}
	if got := r.Results[0].Memo.SpaceID; got != adminSp["personal"] {
		t.Fatalf("返回的 space_id 应为新空间，实际 %s", got)
	}

	// 服务端持久化必须真的换了空间：家人再拉取不应对这条再有可见变更
	_, pMom := e.pull(momTok, 0)
	if len(pMom.Changes) != 0 {
		for _, ch := range pMom.Changes {
			if ch.Memo != nil && ch.Memo.ID == id {
				t.Fatalf("已移入他人私人空间的备忘录不应再推给家人: %+v", ch.Memo)
			}
		}
	}
	// 自己拉取：space 已变更
	_, pAdmin := e.pull(e.adminToken, 0)
	var seen *store.Memo
	for _, ch := range pAdmin.Changes {
		if ch.Memo != nil && ch.Memo.ID == id {
			seen = ch.Memo
		}
	}
	if seen == nil {
		t.Fatal("自己应能拉到该备忘录")
	}
	if seen.SpaceID != adminSp["personal"] {
		t.Fatalf("服务端存储的 space_id 未更新为个人空间，实际 %s", seen.SpaceID)
	}

	// 转换到工作组：组员可见
	code, out := e.do("POST", "/api/v1/spaces", momTok, map[string]string{"name": "协作组"})
	if code != 200 {
		t.Fatal("建组失败")
	}
	var grp store.Space
	_ = json.Unmarshal(out, &grp)
	if c, _ := e.do("POST", "/api/v1/spaces/"+grp.ID+"/members", momTok, map[string]string{"username": "admin"}); c != 200 {
		t.Fatal("拉人失败")
	}
	// 管理员是组员，可以把备忘录移进组
	_, r3 := e.push(e.adminToken, []store.PushInput{memoInput(id, grp.ID, "要转为私人的笔记", 2, nil)})
	if r3.Results[0].Status != "ok" {
		t.Fatalf("移入工作组应成功: %+v", r3.Results[0])
	}
	if r3.Results[0].Memo.SpaceID != grp.ID {
		t.Fatalf("space_id 应为工作组")
	}
	// 妈妈（组长，组员）能看到
	_, pMom2 := e.pull(momTok, 0)
	found := false
	for _, ch := range pMom2.Changes {
		if ch.Memo != nil && ch.Memo.ID == id && ch.Memo.SpaceID == grp.ID {
			found = true
		}
	}
	if !found {
		t.Fatal("移入工作组后组员应能看到该备忘录（且 space_id 正确）")
	}
}

// TestMoveToInaccessibleSpaceRejected 转换到无权空间必须拒绝。
func TestMoveToInaccessibleSpaceRejected(t *testing.T) {
	e := newEnv(t)
	adminSp := e.spaces(e.adminToken)
	e.createUser("mom", "pass1234", "妈妈")
	momTok := e.login("mom", "pass1234")
	id := uuidOf("stay-put")
	e.push(e.adminToken, []store.PushInput{memoInput(id, adminSp["family"], "标题", 0, nil)})

	// 妈妈尝试把家庭空间的备忘录移进管理员的私人空间
	_, r := e.push(momTok, []store.PushInput{memoInput(id, adminSp["personal"], "标题", 1, nil)})
	if r.Results[0].Status != "error" {
		t.Fatalf("移动到无权空间应 error: %+v", r.Results[0])
	}
}

// ---------- 回收站恢复 ----------

// TestRestoreFromTrash 删除后恢复（客户端"从回收站恢复"）必须能同步回其他成员。
func TestRestoreFromTrash(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	e.createUser("mom", "pass1234", "妈妈")
	momTok := e.login("mom", "pass1234")
	id := uuidOf("trash-restore")

	e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "会被删又恢复", 0, nil)})
	del := time.Now().UnixMilli()
	_, r2 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "会被删又恢复", 1, func(m *store.Memo) {
		m.DeletedAt = &del
	})})
	if r2.Results[0].Status != "ok" {
		t.Fatal("删除失败")
	}
	// 恢复：deleted_at 置空
	_, r3 := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "会被删又恢复", 2, func(m *store.Memo) {
		m.DeletedAt = nil
	})})
	if r3.Results[0].Status != "ok" {
		t.Fatalf("恢复应成功: %+v", r3.Results[0])
	}
	if r3.Results[0].Memo.DeletedAt != nil {
		t.Fatal("恢复后 deleted_at 应为空")
	}
	// 家人拉取：最新状态应是未删除
	_, p := e.pull(momTok, 0)
	var latest *store.Memo
	for _, ch := range p.Changes {
		if ch.Memo != nil && ch.Memo.ID == id {
			latest = ch.Memo
		}
	}
	if latest == nil || latest.DeletedAt != nil {
		t.Fatalf("家人应看到恢复后的（未删除）状态: %+v", latest)
	}
}

// ---------- 共享附件与清理 ----------

// TestSharedBlobSurvivesCleanup 两个备忘录引用同一附件，删掉其中一个后附件不能被清理。
func TestSharedBlobSurvivesCleanup(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	data := []byte("shared-image-bytes-for-cleanup-test")
	sha := e.upload(data, "shared.png")

	for _, id := range []string{uuidOf("share-a"), uuidOf("share-b")} {
		_, r := e.push(e.adminToken, []store.PushInput{memoInput(id, sp["family"], "带同一张图", 0, func(m *store.Memo) {
			m.Attachments = []store.Attachment{{ID: sha, Sha256: sha, Filename: "shared.png", Mime: "image/png", Size: int64(len(data))}}
		})})
		if r.Results[0].Status != "ok" {
			t.Fatalf("创建失败: %+v", r.Results[0])
		}
	}
	// 删掉 A（软删）
	del := time.Now().UnixMilli()
	e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("share-a"), sp["family"], "", 1, func(m *store.Memo) {
		m.DeletedAt = &del
	})})
	// 执行清理（不应对仍被 B 引用的 blob 动手）
	if err := e.st.Cleanup(); err != nil {
		t.Fatalf("清理失败: %v", err)
	}
	if _, err := e.st.BlobSize(sha); err != nil {
		t.Fatalf("仍被引用的附件不应被清理: %v", err)
	}
}

// ---------- 钉钉加签 ----------

// TestDingtalkSignedWebhook 钉钉加签：请求应带 timestamp 与 sign 参数，且 sign 为 URL 编码的 base64。
func TestDingtalkSignedWebhook(t *testing.T) {
	e := newEnv(t)
	var gotURL string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURL = r.URL.String()
		w.Write([]byte(`{"errcode":0}`))
	}))
	defer srv.Close()

	// 加签要拼在 query 上，测试服务器要能接受
	if c, o := e.do("PUT", "/api/v1/admin/notify", e.adminToken, map[string]any{
		"dingtalk_webhook": srv.URL + "/robot/send?access_token=abc",
		"dingtalk_secret":  "SECtestsecret123",
	}); c != 200 {
		t.Fatalf("配置失败: %d %s", c, o)
	}
	code, out := e.do("POST", "/api/v1/admin/notify/test", e.adminToken, map[string]string{"channel": "dingtalk"})
	if code != 200 {
		t.Fatalf("测试消息失败: %d %s", code, out)
	}
	u, err := url.Parse(gotURL)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	if q.Get("access_token") != "abc" {
		t.Fatalf("原有 query 参数应保留: %s", gotURL)
	}
	if q.Get("timestamp") == "" || q.Get("sign") == "" {
		t.Fatalf("应附加 timestamp 与 sign: %s", gotURL)
	}
	if strings.Contains(q.Get("sign"), " ") {
		t.Fatalf("sign 不应含空格（需 URL 编码）: %s", q.Get("sign"))
	}
}
