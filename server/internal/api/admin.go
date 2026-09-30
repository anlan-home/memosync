package api

import (
	"fmt"
	"net/http"
	"strings"
	"time"

	"memosync/internal/store"
	"memosync/internal/uuid"
)

type userReq struct {
	Username string `json:"username"`
	Password string `json:"password"`
	Nickname string `json:"nickname"`
	Role     string `json:"role"`
}

func (s *Server) handleUserList(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	users, err := s.st.ListUsers()
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	if users == nil {
		users = []store.User{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"users": users})
}

func (s *Server) handleUserCreate(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req userReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	if req.Role == "" {
		req.Role = "member"
	}
	u, err := s.st.CreateUser(req.Username, req.Password, req.Nickname, req.Role)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, u)
}

func (s *Server) handleUserDelete(w http.ResponseWriter, r *http.Request, _ *store.User) {
	if err := s.st.DeleteUser(r.PathValue("id")); err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"ok": "1"})
}

func (s *Server) handleUserResetPassword(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req userReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	if err := s.st.ResetPassword(r.PathValue("id"), req.Password); err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"ok": "1"})
}

func (s *Server) handleDeviceList(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	devices, err := s.st.ListDevices()
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	if devices == nil {
		devices = []store.Device{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": devices})
}

func (s *Server) handleDeviceDelete(w http.ResponseWriter, r *http.Request, _ *store.User) {
	if !uuid.Valid(r.PathValue("id")) {
		writeErr(w, http.StatusBadRequest, "设备 id 非法")
		return
	}
	if err := s.st.DeleteDevice(r.PathValue("id"), "", true); err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"ok": "1"})
}

type pairCodeReq struct {
	UserID   string `json:"user_id"`
	TTLHours int    `json:"ttl_hours"` // 有效期，默认 24，最大 168
}

type pairCodeResp struct {
	Code      string `json:"code"`
	ExpiresAt int64  `json:"expires_at"`
	URL       string `json:"url"` // 携带服务器地址（base_url 优先，否则按请求推断）
}

func (s *Server) handlePairCodeCreate(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req pairCodeReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	ttl := time.Duration(req.TTLHours) * time.Hour
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	if ttl > 168*time.Hour {
		ttl = 168 * time.Hour
	}
	code, expiresAt, err := s.st.CreatePairCode(req.UserID, ttl)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, pairCodeResp{Code: code, ExpiresAt: expiresAt, URL: s.publicBaseURL(r)})
}

// publicBaseURL 配对码携带的服务器地址。
func (s *Server) publicBaseURL(r *http.Request) string {
	if s.cfg.BaseURL != "" {
		return strings.TrimRight(s.cfg.BaseURL, "/")
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return fmt.Sprintf("%s://%s", scheme, r.Host)
}

type restoreReq struct {
	Name    string `json:"name"`
	Confirm bool   `json:"confirm"` // 必须显式确认为 true
}

// handleRestore 准备从备份恢复（重启服务后生效）。
func (s *Server) handleRestore(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req restoreReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	if !req.Confirm {
		writeErr(w, http.StatusBadRequest, "缺少 confirm: true（恢复会覆盖当前数据）")
		return
	}
	if err := s.st.PrepareRestore(req.Name); err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":              "1",
		"restart_required": true,
		"message":         "恢复已就绪，重启服务后生效（fnOS 重启应用 / docker restart memosync）",
	})
}

func (s *Server) handleStats(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	st, err := s.st.Stats()
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, st)
}

func (s *Server) handleBackupRun(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	name, err := s.st.RunBackup(7)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"name": name})
}

func (s *Server) handleBackupList(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	backups, err := s.st.ListBackups()
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	if backups == nil {
		backups = []store.BackupInfo{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"backups": backups})
}

// handleExport 全量导出 zip（memos.json + attachments/）。
func (s *Server) handleExport(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf(`attachment; filename="memosync-export-%s.zip"`, time.Now().Format("20060102-150405")))
	if err := s.st.ExportZip(w); err != nil {
		// 头已发出，只能记录并截断
		s.log.Error("export", "err", err)
	}
}

// ---------- 飞书/钉钉群机器人通知 ----------

type notifyReq struct {
	FeishuWebhook   *string `json:"feishu_webhook"`
	DingtalkWebhook *string `json:"dingtalk_webhook"`
	DingtalkSecret  *string `json:"dingtalk_secret"`
}

func (s *Server) handleNotifyGet(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	writeJSON(w, http.StatusOK, s.st.GetNotifyConfig())
}

// handleNotifySet 增量更新通知配置（只更新出现的字段；传空串即清除）。
func (s *Server) handleNotifySet(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req notifyReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	cfg := s.st.GetNotifyConfig()
	if req.FeishuWebhook != nil {
		cfg.FeishuWebhook = *req.FeishuWebhook
	}
	if req.DingtalkWebhook != nil {
		cfg.DingtalkWebhook = *req.DingtalkWebhook
	}
	if req.DingtalkSecret != nil {
		cfg.DingtalkSecret = *req.DingtalkSecret
	}
	if err := s.st.SetNotifyConfig(cfg); err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, cfg)
}

// handleNotifyFire 手动触发一次到期提醒扫描（返回触发条数与各条发送结果）。
func (s *Server) handleNotifyFire(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	fired, err := s.st.FireDueReminders(time.Now().UnixMilli())
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	if fired == nil {
		fired = []store.FiredReminder{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"fired": fired, "count": len(fired)})
}

type notifyTestReq struct {
	Channel string `json:"channel"` // feishu | dingtalk
}

func (s *Server) handleNotifyTest(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req notifyTestReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	if err := s.st.SendTestMessage(req.Channel); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"ok": "1"})
}
