package api_test

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"memosync/internal/store"
)

type pullRespRaw struct {
	Changes       []store.ChangeEntry `json:"changes"`
	NextCursor    int64               `json:"next_cursor"`
	LatestSeq     int64               `json:"latest_seq"`
	ResetRequired bool                `json:"reset_required"`
}

func (e *env) pullRaw(token string, since int64) pullRespRaw {
	code, out := e.do("GET", "/api/v1/sync/pull?since="+itoa(since), token, nil)
	if code != 200 {
		e.t.Fatalf("pull 失败: %d %s", code, out)
	}
	var r pullRespRaw
	_ = json.Unmarshal(out, &r)
	return r
}

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var b []byte
	for v > 0 {
		b = append([]byte{byte('0' + v%10)}, b...)
		v /= 10
	}
	if neg {
		return "-" + string(b)
	}
	return string(b)
}

// TestPullResetWhenCursorAhead 备份恢复后序号回退，客户端游标超前 → 要求全量重建。
func TestPullResetWhenCursorAhead(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("r1"), sp["family"], "一条", 0, nil)})

	// 正常游标不触发重建
	r := e.pullRaw(e.adminToken, 0)
	if r.ResetRequired {
		t.Fatal("正常游标不应要求重建")
	}
	// 游标远超服务端最大值（模拟恢复到旧备份后客户端仍记着更大序号）
	r = e.pullRaw(e.adminToken, 999999)
	if !r.ResetRequired {
		t.Fatal("游标超前应要求全量重建")
	}
	if len(r.Changes) == 0 {
		t.Fatal("重建时应从头返回全部变更")
	}
}

// TestPullResetWhenChangesReclaimed changes 被清理后，落后游标应要求重建。
func TestPullResetWhenChangesReclaimed(t *testing.T) {
	e := newEnv(t)
	sp := e.spaces(e.adminToken)
	// 造 3 条变更
	for i := 0; i < 3; i++ {
		e.push(e.adminToken, []store.PushInput{memoInput(uuidOf("old-"+itoa(int64(i))), sp["family"], "旧", 0, nil)})
	}
	// 手工回收中间段（模拟 90 天清理）
	if _, err := e.st.DB().Exec(`DELETE FROM changes WHERE seq <= 2`); err != nil {
		t.Fatal(err)
	}
	// 游标 1（落后于仍保留的最小序号 3）→ 重建
	r := e.pullRaw(e.adminToken, 1)
	if !r.ResetRequired {
		t.Fatalf("游标落后于保留窗口应要求重建: %+v", r)
	}
	// 游标 0（全新设备）不应被判为失效
	r = e.pullRaw(e.adminToken, 0)
	if r.ResetRequired {
		t.Fatal("全新设备（since=0）不应要求重建")
	}
	_ = context.Background()
	_ = time.Now()
}
