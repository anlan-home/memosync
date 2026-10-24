package store

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// 通知渠道配置存 meta 表（管理页可改，无需重启）。
type NotifyConfig struct {
	FeishuWebhook   string `json:"feishu_webhook"`
	DingtalkWebhook string `json:"dingtalk_webhook"`
	DingtalkSecret  string `json:"dingtalk_secret"`
}

func (s *Store) GetNotifyConfig() NotifyConfig {
	var cfg NotifyConfig
	metaGet := func(key string) string {
		var v string
		_ = s.db.QueryRow(`SELECT value FROM meta WHERE key=?`, key).Scan(&v)
		return v
	}
	cfg.FeishuWebhook = metaGet("notify_feishu_webhook")
	cfg.DingtalkWebhook = metaGet("notify_dingtalk_webhook")
	cfg.DingtalkSecret = metaGet("notify_dingtalk_secret")
	return cfg
}

func (s *Store) SetNotifyConfig(cfg NotifyConfig) error {
	for key, val := range map[string]string{
		"notify_feishu_webhook":   strings.TrimSpace(cfg.FeishuWebhook),
		"notify_dingtalk_webhook": strings.TrimSpace(cfg.DingtalkWebhook),
		"notify_dingtalk_secret":  strings.TrimSpace(cfg.DingtalkSecret),
	} {
		if _, err := s.db.Exec(`INSERT INTO meta(key, value) VALUES(?,?)
			ON CONFLICT(key) DO UPDATE SET value=excluded.value`, key, val); err != nil {
			return err
		}
	}
	return nil
}

type FiredReminder struct {
	MemoID string `json:"memo_id"`
	Title  string `json:"title"`
	At     int64  `json:"at"`
	Errors string `json:"errors,omitempty"` // 各渠道发送失败原因（分号分隔）
}

var httpClient = &http.Client{Timeout: 8 * time.Second}

// FireDueReminders 扫描到点的提醒（笔记提醒 + 资产到期提醒统一走 remind_ats），
// 向已配置的飞书/钉钉群机器人推送文本消息。
//
// 失败重试语义：只要有一个渠道发送失败就整体算失败——不标记已提醒，下次扫描重试；
// 连续失败 maxNotifyAttempts 次后放弃（标记已提醒），避免故障期间无限重试。
// now 之前 48 小时内的才触发——久未上线的设备不会收到历史轰炸。
func (s *Store) FireDueReminders(now int64) ([]FiredReminder, error) {
	cfg := s.GetNotifyConfig()
	if cfg.FeishuWebhook == "" && cfg.DingtalkWebhook == "" {
		return nil, nil
	}
	windowFrom := now - 48*3600*1000
	rows, err := s.db.Query(`
		SELECT r.id, r.memo_id, r.at, r.attempts, m.title, m.type, m.content
		FROM reminders r JOIN memos m ON m.id = r.memo_id
		WHERE r.reminded_at IS NULL AND r.at <= ? AND r.at > ?
		ORDER BY r.at`, now, windowFrom)
	if err != nil {
		return nil, err
	}
	type due struct {
		id       int64
		memoID   string
		at       int64
		attempts int
		title    string
		mtype    string
		content  string
	}
	var dues []due
	for rows.Next() {
		var d due
		if err := rows.Scan(&d.id, &d.memoID, &d.at, &d.attempts, &d.title, &d.mtype, &d.content); err == nil {
			dues = append(dues, d)
		}
	}
	rows.Close()

	fired := make([]FiredReminder, 0, len(dues))
	for _, d := range dues {
		text := buildReminderText(d.at, d.title, d.mtype, d.content)
		errs := s.sendWebhookText(cfg, text)
		if len(errs) == 0 {
			_, _ = s.db.Exec(`UPDATE reminders SET reminded_at=?, attempts=attempts+1 WHERE id=?`, now, d.id)
		} else {
			attempts := d.attempts + 1
			if attempts >= maxNotifyAttempts {
				// 放弃：标记已提醒，避免持续重试
				_, _ = s.db.Exec(`UPDATE reminders SET reminded_at=?, attempts=? WHERE id=?`, now, attempts, d.id)
			} else {
				_, _ = s.db.Exec(`UPDATE reminders SET attempts=? WHERE id=?`, attempts, d.id)
			}
		}
		fired = append(fired, FiredReminder{
			MemoID: d.memoID, Title: d.title, At: d.at,
			Errors: strings.Join(errs, "; "),
		})
	}
	return fired, nil
}

// maxNotifyAttempts 单个提醒最多尝试推送次数。
const maxNotifyAttempts = 3

// SendTestMessage 管理页「发送测试消息」。
func (s *Store) SendTestMessage(channel string) error {
	cfg := s.GetNotifyConfig()
	text := "✅ 家庭备忘录测试消息：通知渠道已打通。"
	var errs []string
	switch channel {
	case "feishu":
		if cfg.FeishuWebhook == "" {
			return fmt.Errorf("%w: 飞书 webhook 未配置", ErrBadRequest)
		}
		if err := postFeishu(cfg.FeishuWebhook, text); err != nil {
			errs = append(errs, "feishu: "+err.Error())
		}
	case "dingtalk":
		if cfg.DingtalkWebhook == "" {
			return fmt.Errorf("%w: 钉钉 webhook 未配置", ErrBadRequest)
		}
		if err := postDingtalk(cfg.DingtalkWebhook, cfg.DingtalkSecret, text); err != nil {
			errs = append(errs, "dingtalk: "+err.Error())
		}
	default:
		return fmt.Errorf("%w: channel 只能是 feishu/dingtalk", ErrBadRequest)
	}
	if len(errs) > 0 {
		return fmt.Errorf("%s", strings.Join(errs, "; "))
	}
	return nil
}

func (s *Store) sendWebhookText(cfg NotifyConfig, text string) []string {
	var errs []string
	if cfg.FeishuWebhook != "" {
		if err := postFeishu(cfg.FeishuWebhook, text); err != nil {
			errs = append(errs, "feishu: "+err.Error())
		}
	}
	if cfg.DingtalkWebhook != "" {
		if err := postDingtalk(cfg.DingtalkWebhook, cfg.DingtalkSecret, text); err != nil {
			errs = append(errs, "dingtalk: "+err.Error())
		}
	}
	return errs
}

func postJSON(url string, payload any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	resp, err := httpClient.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var out struct {
		Code int    `json:"code"`
		Err  int    `json:"errcode"`
		Msg  string `json:"msg"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	if out.Code != 0 || out.Err != 0 {
		return fmt.Errorf("webhook 拒绝: code=%d errcode=%d msg=%s", out.Code, out.Err, out.Msg)
	}
	return nil
}

func postFeishu(webhook, text string) error {
	return postJSON(webhook, map[string]any{
		"msg_type": "text",
		"content":  map[string]any{"text": text},
	})
}

// postDingtalk 钉钉自定义机器人；配置了加签密钥时按官方算法签名。
func postDingtalk(webhook, secret, text string) error {
	if secret != "" {
		ts := time.Now().UnixMilli()
		stringToSign := fmt.Sprintf("%d\n%s", ts, secret)
		mac := hmac.New(sha256.New, []byte(secret))
		mac.Write([]byte(stringToSign))
		sign := url.QueryEscape(base64.StdEncoding.EncodeToString(mac.Sum(nil)))
		sep := "?"
		if strings.Contains(webhook, "?") {
			sep = "&"
		}
		webhook = fmt.Sprintf("%s%stimestamp=%d&sign=%s", webhook, sep, ts, sign)
	}
	return postJSON(webhook, map[string]any{
		"msgtype": "text",
		"text":    map[string]any{"content": text},
	})
}

// buildReminderText 组装提醒文案（笔记/清单/资产共用 remind_ats 通道）。
func buildReminderText(at int64, title, mtype, content string) string {
	when := time.UnixMilli(at).Format("1月2日 15:04")
	head := "⏰ 备忘提醒"
	if mtype == "asset" {
		head = "📦 资产提醒"
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s：%s\n时间：%s", head, title, when)
	if detail := plainTextFromContent(content); detail != "" {
		if len([]rune(detail)) > 100 {
			detail = string([]rune(detail)[:100]) + "…"
		}
		fmt.Fprintf(&b, "\n%s", detail)
	}
	return b.String()
}

// plainTextFromContent 服务端侧的内容摘要（只识别客户端 content JSON 的已知字段）。
func plainTextFromContent(content string) string {
	var c struct {
		Text  string `json:"text"`
		Items []struct {
			Text string `json:"text"`
			Done bool   `json:"done"`
		} `json:"items"`
	}
	if err := json.Unmarshal([]byte(content), &c); err != nil {
		return ""
	}
	if len(c.Items) > 0 {
		parts := make([]string, 0, len(c.Items))
		for _, it := range c.Items {
			mark := "□ "
			if it.Done {
				mark = "☑ "
			}
			parts = append(parts, mark+it.Text)
		}
		return strings.Join(parts, " / ")
	}
	return strings.TrimSpace(c.Text)
}
