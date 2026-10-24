package api

import (
	"net/http"
	"os"

	"memosync/internal/store"
)

// 运行环境诊断：排查真机问题时快速定位（端口、配置可写性、是否运行在 fnOS 容器策略下）。

type diagnosticsResp struct {
	Version         string `json:"version"`
	Listen          string `json:"listen"`
	Port            int    `json:"port"`
	DataDir         string `json:"data_dir"`
	ConfigPath      string `json:"config_path"`
	ConfigWritable  bool   `json:"config_writable"`
	RedirectPorts   []int  `json:"redirect_ports"`
	FnOSPackageMode bool   `json:"fnos_package_mode"`
	FnOSAppDest     string `json:"fnos_app_dest,omitempty"`
	FnOSPkgVar      string `json:"fnos_pkg_var,omitempty"`
	PlatformHint    string `json:"platform_hint"`
	ExePath         string `json:"exe_path"`
}

var appVersion = "dev"

// SetVersion 由 main 注入构建版本。
func SetVersion(v string) { appVersion = v }

func (s *Server) handleDiagnostics(w http.ResponseWriter, _ *http.Request, _ *store.User) {
	appDest := os.Getenv("TRIM_APPDEST")
	pkgVar := os.Getenv("TRIM_PKGVAR")
	resp := diagnosticsResp{
		Version:         appVersion,
		Listen:          s.cfg.Listen,
		Port:            s.cfg.Port(),
		DataDir:         s.st.DataDir(),
		ConfigPath:      ConfigFilePath,
		RedirectPorts:   s.cfg.RedirectPorts,
		FnOSPackageMode: appDest != "",
		FnOSAppDest:     appDest,
		FnOSPkgVar:      pkgVar,
	}
	if exe, err := os.Executable(); err == nil {
		resp.ExePath = exe
	}
	if ConfigFilePath != "" {
		// 可写性探测：不修改内容，仅尝试以追加方式打开
		if f, err := os.OpenFile(ConfigFilePath, os.O_WRONLY|os.O_APPEND, 0); err == nil {
			resp.ConfigWritable = true
			f.Close()
		} else {
			resp.ConfigPath += "（不可写: " + err.Error() + "）"
		}
	}
	switch {
	case resp.FnOSPackageMode:
		resp.PlatformHint = "运行在飞牛应用包内：端口由应用中心管理，请到 fnOS 应用「设置」页修改端口并重启应用；管理页直接改端口在飞牛上不生效。"
	case ConfigFilePath == "":
		resp.PlatformHint = "未以配置文件启动：管理页改端口不可用（请用 -config 指定配置文件或设置环境变量）。"
	default:
		resp.PlatformHint = "以配置文件启动：管理页可直接改端口，服务会原地重启。"
	}
	if resp.RedirectPorts == nil {
		resp.RedirectPorts = []int{}
	}
	writeJSON(w, http.StatusOK, resp)
}
