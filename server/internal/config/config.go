// Package config 加载服务端配置：默认值 <- 配置文件 <- 环境变量。
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

type Config struct {
	// Listen HTTP 监听地址，如 ":5231"
	Listen string `yaml:"listen"`
	// DataDir 数据目录：SQLite 库、附件、备份都放在这里。卸载/升级软件包时必须保留该目录。
	DataDir string `yaml:"data_dir"`
	// BaseURL 对外访问地址（如 http://192.168.1.10:5231 或 Tailscale 地址），
	// 用于配对码携带服务器地址；留空则用请求的 Host 推断。
	BaseURL string `yaml:"base_url"`
	// LogFile 日志文件路径，留空输出到 stdout
	LogFile string `yaml:"log_file"`
	// RedirectPorts 旧端口列表：在这些端口上启动 302 跳转器，指向当前端口。
	// 用于「改端口后桌面图标仍指向旧端口」的自愈；绑定失败静默忽略。
	RedirectPorts []int `yaml:"redirect_ports"`
}

// Port 从 Listen 解析主服务端口；解析失败返回 0。
func (c *Config) Port() int {
	idx := strings.LastIndex(c.Listen, ":")
	if idx < 0 {
		return 0
	}
	p, err := strconv.Atoi(c.Listen[idx+1:])
	if err != nil || p <= 0 || p > 65535 {
		return 0
	}
	return p
}

func defaults() Config {
	return Config{
		Listen:  ":5231",
		DataDir: "./data",
	}
}

// Load 按优先级加载配置：defaults <- 文件 <- 环境变量。
func Load(path string) (Config, error) {
	cfg := defaults()
	if path != "" {
		b, err := os.ReadFile(path)
		if err != nil {
			return cfg, fmt.Errorf("读取配置文件: %w", err)
		}
		if err := yaml.Unmarshal(b, &cfg); err != nil {
			return cfg, fmt.Errorf("解析配置文件: %w", err)
		}
	}
	if v := os.Getenv("MEMOSYNC_LISTEN"); v != "" {
		cfg.Listen = v
	}
	if v := os.Getenv("MEMOSYNC_DATA_DIR"); v != "" {
		cfg.DataDir = v
	}
	if v := os.Getenv("MEMOSYNC_BASE_URL"); v != "" {
		cfg.BaseURL = v
	}
	if cfg.Listen == "" || cfg.DataDir == "" {
		return cfg, fmt.Errorf("listen 与 data_dir 不能为空")
	}
	return cfg, nil
}

// EnvPort 环境变量里的端口覆盖（fpk 安装器可能注入固定端口）
func (c *Config) EnvPort() {
	if v := os.Getenv("MEMOSYNC_PORT"); v != "" {
		if p, err := strconv.Atoi(v); err == nil && p > 0 && p < 65536 {
			c.Listen = ":" + strconv.Itoa(p)
		}
	}
}
