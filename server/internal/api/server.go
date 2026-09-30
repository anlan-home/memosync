// Package api：HTTP 层。只做参数校验、错误映射与鉴权，SQL 全部在 store 层。
package api

import (
	"embed"
	"encoding/json"
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"memosync/internal/config"
	"memosync/internal/store"
)

//go:embed web/index.html
var adminFS embed.FS

type Server struct {
	st  *store.Store
	cfg config.Config
	log *slog.Logger
	hub *hub
}

type ctxKey struct{}

// New 构建路由与中间件。
func New(st *store.Store, cfg config.Config, logger *slog.Logger) http.Handler {
	s := &Server{st: st, cfg: cfg, log: logger, hub: newHub()}
	// store 层每次变更提交后 → 广播给所有 SSE 订阅者（客户端收到后立即 pull）
	st.SetChangeNotify(s.hub.broadcast)
	mux := http.NewServeMux()

	// 认证（登录/配对无需 token）
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /api/v1/auth/login", s.handleLogin)
	mux.HandleFunc("POST /api/v1/auth/pair", s.handlePairRedeem)
	mux.HandleFunc("POST /api/v1/auth/password", s.authed(s.handleChangePassword))
	mux.HandleFunc("GET /api/v1/auth/me", s.authed(s.handleMe))

	// 业务
	mux.HandleFunc("GET /api/v1/spaces", s.authed(s.handleSpaces))
	mux.HandleFunc("POST /api/v1/spaces", s.authed(s.handleSpaceCreate))
	mux.HandleFunc("GET /api/v1/spaces/{id}/members", s.authed(s.handleSpaceMembers))
	mux.HandleFunc("POST /api/v1/spaces/{id}/members", s.authed(s.handleSpaceMembers))
	mux.HandleFunc("DELETE /api/v1/spaces/{id}/members/{uid}", s.authed(s.handleSpaceMemberDelete))
	mux.HandleFunc("DELETE /api/v1/spaces/{id}", s.authed(s.handleSpaceDelete))
	mux.HandleFunc("POST /api/v1/sync/push", s.authed(s.handlePush))
	mux.HandleFunc("GET /api/v1/sync/pull", s.authed(s.handlePull))
	mux.HandleFunc("GET /api/v1/sync/events", s.authed(s.handleEvents))
	mux.HandleFunc("POST /api/v1/attachments/check", s.authed(s.handleAttachmentCheck))
	mux.HandleFunc("POST /api/v1/attachments", s.authed(s.handleAttachmentUpload))
	mux.HandleFunc("GET /api/v1/attachments/{id}", s.authed(s.handleAttachmentDownload))

	// 管理端
	mux.HandleFunc("GET /api/v1/admin/users", s.admin(s.handleUserList))
	mux.HandleFunc("POST /api/v1/admin/users", s.admin(s.handleUserCreate))
	mux.HandleFunc("DELETE /api/v1/admin/users/{id}", s.admin(s.handleUserDelete))
	mux.HandleFunc("POST /api/v1/admin/users/{id}/password", s.admin(s.handleUserResetPassword))
	mux.HandleFunc("GET /api/v1/admin/devices", s.admin(s.handleDeviceList))
	mux.HandleFunc("DELETE /api/v1/admin/devices/{id}", s.admin(s.handleDeviceDelete))
	mux.HandleFunc("POST /api/v1/admin/pair-code", s.admin(s.handlePairCodeCreate))
	mux.HandleFunc("GET /api/v1/admin/stats", s.admin(s.handleStats))
	mux.HandleFunc("POST /api/v1/admin/backup", s.admin(s.handleBackupRun))
	mux.HandleFunc("GET /api/v1/admin/backups", s.admin(s.handleBackupList))
	mux.HandleFunc("POST /api/v1/admin/restore", s.admin(s.handleRestore))
	mux.HandleFunc("POST /api/v1/admin/port", s.admin(s.handleSetPort))
	mux.HandleFunc("GET /api/v1/admin/notify", s.admin(s.handleNotifyGet))
	mux.HandleFunc("PUT /api/v1/admin/notify", s.admin(s.handleNotifySet))
	mux.HandleFunc("POST /api/v1/admin/notify/fire", s.admin(s.handleNotifyFire))
	mux.HandleFunc("POST /api/v1/admin/notify/test", s.admin(s.handleNotifyTest))
	mux.HandleFunc("GET /api/v1/admin/export", s.admin(s.handleExport))

	// 管理页（内嵌静态文件）
	if sub, err := fs.Sub(adminFS, "web"); err == nil {
		mux.Handle("GET /{$}", http.FileServer(http.FS(sub)))
		mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, "/", http.StatusTemporaryRedirect)
		})
	}

	return s.recoverMiddleware(s.logMiddleware(mux))
}

// ---------- 中间件 ----------

func (s *Server) authed(next func(http.ResponseWriter, *http.Request, *store.User)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		u, ok := s.authenticate(w, r)
		if !ok {
			return
		}
		next(w, r, u)
	}
}

func (s *Server) admin(next func(http.ResponseWriter, *http.Request, *store.User)) http.HandlerFunc {
	return s.authed(func(w http.ResponseWriter, r *http.Request, u *store.User) {
		if u.Role != "admin" {
			writeErr(w, http.StatusForbidden, "需要管理员权限")
			return
		}
		next(w, r, u)
	})
}

func (s *Server) authenticate(w http.ResponseWriter, r *http.Request) (*store.User, bool) {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(h, prefix) {
		writeErr(w, http.StatusUnauthorized, "缺少登录凭证")
		return nil, false
	}
	_, u, err := s.st.Auth(strings.TrimSpace(strings.TrimPrefix(h, prefix)))
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "登录已失效，请重新登录")
		return nil, false
	}
	return u, true
}

func (s *Server) recoverMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				s.log.Error("panic", "path", r.URL.Path, "err", rec)
				writeErr(w, http.StatusInternalServerError, "服务器内部错误")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *Server) logMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		if strings.HasPrefix(r.URL.Path, "/api/") {
			s.log.Info("http", "method", r.Method, "path", r.URL.Path, "dur", time.Since(start).Round(time.Millisecond))
		}
	})
}

// ---------- JSON 辅助 ----------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

// readJSON 解析请求体，限制大小防止滥用。
func readJSON(w http.ResponseWriter, r *http.Request, dst any, maxBytes int64) bool {
	if maxBytes <= 0 {
		maxBytes = 8 << 20 // 8MB
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeErr(w, http.StatusRequestEntityTooLarge, "请求体过大")
			return false
		}
		writeErr(w, http.StatusBadRequest, "请求体不是合法 JSON: "+err.Error())
		return false
	}
	return true
}

// mapStoreErr 领域错误 → HTTP 状态码。
func mapStoreErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, http.StatusNotFound, "资源不存在")
	case errors.Is(err, store.ErrAuth):
		writeErr(w, http.StatusUnauthorized, err.Error())
	case errors.Is(err, store.ErrBadRequest):
		writeErr(w, http.StatusBadRequest, err.Error())
	default:
		writeErr(w, http.StatusInternalServerError, err.Error())
	}
}
