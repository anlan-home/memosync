package api

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"memosync/internal/config"
	"memosync/internal/store"
)

// 端口切换的支持设施：
//   1) 管理页改端口 → 写配置文件 → 原地重启（fnOS 下由 Restart 钩子执行 exec，pid 不变）
//   2) 旧端口启动 302 跳转器：桌面图标仍指旧端口时自动跳到新端口（绑定失败静默忽略）

// ConfigFilePath 配置文件路径（main 注入）；为空表示无法持久化端口修改。
var ConfigFilePath string

// Restart 由 main 注入的原地重启函数（关闭存储后 exec 自身）。
var Restart func()

type setPortReq struct {
	Port int `json:"port"`
}

// handleSetPort 管理页修改服务端口。
func (s *Server) handleSetPort(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req setPortReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	cur := s.cfg.Port()
	if req.Port < 1024 || req.Port > 65535 {
		writeErr(w, http.StatusBadRequest, "端口需在 1024-65535 之间")
		return
	}
	if req.Port == cur {
		writeErr(w, http.StatusBadRequest, "新端口与当前端口相同")
		return
	}
	if ConfigFilePath == "" {
		writeErr(w, http.StatusInternalServerError, "服务未以配置文件方式启动，无法修改端口")
		return
	}
	s.cfg.Listen = fmt.Sprintf(":%d", req.Port)
	// 旧端口进入跳转列表（最多保留 3 个，去重，剔除与主端口相同的）
	np := []int{}
	for _, p := range s.cfg.RedirectPorts {
		if p != req.Port && p != cur && !containsInt(np, p) {
			np = append(np, p)
		}
	}
	if cur > 0 {
		np = append(np, cur)
	}
	if len(np) > 3 {
		np = np[len(np)-3:]
	}
	s.cfg.RedirectPorts = np
	if err := writeConfigFile(ConfigFilePath, &s.cfg); err != nil {
		writeErr(w, http.StatusInternalServerError, "写入配置失败: "+err.Error())
		return
	}
	go func() {
		time.Sleep(600 * time.Millisecond) // 让响应先送达客户端
		if Restart != nil {
			Restart()
		}
	}()
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      "1",
		"port":    req.Port,
		"message": fmt.Sprintf("服务将重启到端口 %d，旧端口 %d 会自动跳转；请用 http://NAS-IP:%d 访问", req.Port, cur, req.Port),
	})
}

// writeConfigFile 以当前配置重写配置文件（保留未知字段不保证——文件本就由本工具管理）。
func writeConfigFile(path string, cfg *config.Config) error {
	redirects := ""
	if len(cfg.RedirectPorts) > 0 {
		parts := make([]string, 0, len(cfg.RedirectPorts))
		for _, p := range cfg.RedirectPorts {
			parts = append(parts, fmt.Sprintf("%d", p))
		}
		redirects = "\nredirect_ports: [" + strings.Join(parts, ", ") + "]"
	}
	baseURL := strings.ReplaceAll(cfg.BaseURL, "\n", "")
	content := fmt.Sprintf(`listen: "%s"
data_dir: "%s"
base_url: "%s"
log_file: "%s"%s
`,
		cfg.Listen, cfg.DataDir, baseURL, cfg.LogFile, redirects)
	return os.WriteFile(path, []byte(content), 0o600)
}

// StartRedirects 在旧端口上启动 302 跳转器（尽力而为，失败只记日志）。
// 返回值仅为便于测试等待就绪；生产中由 main 在主服务启动后调用。
func StartRedirects(cfg config.Config, logf func(msg string, args ...any)) {
	main := cfg.Port()
	for _, p := range cfg.RedirectPorts {
		if p <= 0 || p == main || p > 65535 {
			continue
		}
		go func(port int) {
			mux := http.NewServeMux()
			mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
				host := r.Host
				if i := strings.LastIndex(host, ":"); i >= 0 {
					host = host[:i]
				}
				scheme := "http"
				if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
					scheme = "https"
				}
				target := fmt.Sprintf("%s://%s:%d%s", scheme, host, main, r.URL.Path)
				http.Redirect(w, r, target, http.StatusTemporaryRedirect)
			})
			srv := &http.Server{
				Addr:              net.JoinHostPort("", fmt.Sprintf("%d", port)),
				Handler:           mux,
				ReadHeaderTimeout: 5 * time.Second,
			}
			logf("旧端口跳转器启动", "port", port, "target", main)
			if err := srv.ListenAndServe(); err != nil {
				logf("旧端口跳转器未启动（可能被占用，忽略）", "port", port, "err", err.Error())
			}
		}(p)
	}
}

func containsInt(list []int, v int) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}
