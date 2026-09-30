// memosync：家庭备忘录 NAS 服务端。
// 单二进制 + SQLite；数据目录含 memo.db / attachments/ / backup/。
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"memosync/internal/api"
	"memosync/internal/config"
	"memosync/internal/store"
)

var version = "1.0.0"

func main() {
	configPath := flag.String("config", "config.yaml", "配置文件路径")
	showVersion := flag.Bool("version", false, "打印版本")
	flag.Parse()
	if *showVersion {
		fmt.Println("memosync", version)
		return
	}

	logger := slog.New(slog.NewTextHandler(os.Stdout, nil))

	cfg, err := config.Load(*configPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) || filepath.Base(*configPath) == *configPath {
			// 没有配置文件也能跑：内置默认值（局域网小工具哲学）
			cfg, _ = config.Load("")
			logger.Warn("未找到配置文件，使用默认配置", "listen", cfg.Listen, "data_dir", cfg.DataDir)
		} else {
			logger.Error("配置加载失败", "err", err)
			os.Exit(1)
		}
	}
	cfg.EnvPort()

	// 配置了 log_file 时同时写文件（fpk 场景便于真机排查）
	if cfg.LogFile != "" {
		if f, ferr := os.OpenFile(cfg.LogFile, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); ferr == nil {
			logger = slog.New(slog.NewTextHandler(io.MultiWriter(os.Stdout, f), nil))
		}
	}

	// 启动前检查是否有等待恢复的备份（管理页「恢复」在重启后生效）
	if restored, err := store.RestoreIfPending(cfg.DataDir); err != nil {
		logger.Error("恢复备份失败", "err", err)
		os.Exit(1)
	} else if restored {
		logger.Info("已从备份恢复数据库（附件缺失部分已在准备阶段补齐）")
	}

	st, err := store.Open(cfg.DataDir)
	if err != nil {
		logger.Error("打开存储失败", "err", err)
		os.Exit(1)
	}
	defer st.Close()

	bootstrapAdmin(st, logger)

	go maintenanceLoop(st, logger)

	// 提醒扫描器：每分钟检查到点的提醒（笔记/资产），向飞书/钉钉群机器人推送
	go func() {
		tick := time.NewTicker(time.Minute)
		defer tick.Stop()
		for range tick.C {
			if fired, err := st.FireDueReminders(time.Now().UnixMilli()); err == nil && len(fired) > 0 {
				for _, f := range fired {
					if f.Errors != "" {
						logger.Warn("提醒已触发（部分渠道失败）", "title", f.Title, "errs", f.Errors)
					} else {
						logger.Info("提醒已推送", "title", f.Title)
					}
				}
			}
		}
	}()

	// 管理页改端口：写配置后原地重启（exec 自身，pid 不变，appcenter 无感知）
	api.ConfigFilePath = *configPath
	api.Restart = func() {
		logger.Info("正在切换端口，原地重启服务…")
		_ = st.Close()
		if err := restartSelf(); err != nil {
			logger.Error("原地重启失败，请手动重启应用使新端口生效", "err", err)
			os.Exit(1)
		}
	}

	handler := api.New(st, cfg, logger)
	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		logger.Info("memosync 已启动", "version", version, "listen", cfg.Listen, "data_dir", cfg.DataDir)
		logger.Info("管理页", "url", "http://<nas-ip>"+cfg.Listen)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("端口监听失败，服务退出", "listen", cfg.Listen, "err", err,
				"hint", "多半是端口被占用：请在应用的「设置」页修改端口，或停用占用该端口的服务后重启应用")
			os.Exit(1)
		}
	}()

	// 旧端口 302 跳转器：桌面图标仍指旧端口时自动跳到当前端口
	api.StartRedirects(cfg, func(msg string, args ...any) {
		logger.Info(msg, args...)
	})

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit
	logger.Info("正在停机…")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
	logger.Info("已退出")
}

// bootstrapAdmin 首次启动创建管理员：密码取环境变量，否则随机生成
// 写入数据目录 initial_admin_password.txt 并打印到日志。
func bootstrapAdmin(st *store.Store, logger *slog.Logger) {
	has, err := st.HasUsers()
	if err != nil {
		logger.Error("检查用户失败", "err", err)
		os.Exit(1)
	}
	if has {
		return
	}
	username := os.Getenv("MEMOSYNC_ADMIN_USER")
	if username == "" {
		username = "admin"
	}
	password := os.Getenv("MEMOSYNC_ADMIN_PASSWORD")
	random := false
	if password == "" {
		password = store.PairCode() + store.PairCode()[:2] // 10 位随机
		random = true
	}
	if _, err := st.BootstrapAdmin(username, password); err != nil {
		logger.Error("创建管理员失败", "err", err)
		os.Exit(1)
	}
	logger.Info("首次启动：已创建管理员", "username", username)
	if random {
		pwdFile := filepath.Join(st.DataDir(), "initial_admin_password.txt")
		content := fmt.Sprintf("管理员账号: %s\n初始密码: %s\n（登录后请尽快修改；本文件可删除）\n", username, password)
		if err := os.WriteFile(pwdFile, []byte(content), 0o600); err != nil {
			logger.Error("写入初始密码文件失败", "err", err)
		}
		logger.Info("初始密码（请尽快登录修改）", "password", password, "also_at", pwdFile)
	} else {
		logger.Info("管理员密码来自 MEMOSYNC_ADMIN_PASSWORD 环境变量")
	}
}

// maintenanceLoop 每 10 分钟醒来一次：
// 本地时间 02:00-02:59 做每日备份，03:00-03:59 做清理（各每天至多一次）。
func maintenanceLoop(st *store.Store, logger *slog.Logger) {
	lastBackupDay, lastCleanupDay := "", ""
	tick := time.NewTicker(10 * time.Minute)
	defer tick.Stop()
	// 启动 1 分钟后先跑一次清理，避免长期不重启积累垃圾
	boot := time.Now()
	for {
		select {
		case <-tick.C:
			now := time.Now()
			day := now.Format("2006-01-02")
			if now.Hour() == 2 && lastBackupDay != day {
				if name, err := st.RunBackup(7); err != nil {
					logger.Error("每日备份失败", "err", err)
				} else {
					logger.Info("每日备份完成", "name", name)
				}
				lastBackupDay = day
			}
			if (now.Hour() == 3 || (lastCleanupDay == "" && time.Since(boot) > time.Minute)) && lastCleanupDay != day {
				if err := st.Cleanup(); err != nil {
					logger.Error("清理任务失败", "err", err)
				} else {
					logger.Info("清理任务完成")
				}
				lastCleanupDay = day
			}
		}
	}
}
