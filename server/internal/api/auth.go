package api

import (
	"net/http"
	"strings"

	"memosync/internal/store"
)

type loginReq struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceName string `json:"device_name"`
}

type loginResp struct {
	Token string      `json:"token"`
	User  *store.User `json:"user"`
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	device := req.DeviceName
	if device == "" {
		device = deviceFromUA(r.UserAgent())
	}
	token, u, err := s.st.Login(req.Username, req.Password, device)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, loginResp{Token: token, User: u})
}

func (s *Server) handleMe(w http.ResponseWriter, _ *http.Request, u *store.User) {
	writeJSON(w, http.StatusOK, u)
}

type pairReq struct {
	Code       string `json:"code"`
	DeviceName string `json:"device_name"`
}

// handlePairRedeem 家人手机凭配对码免密接入。
func (s *Server) handlePairRedeem(w http.ResponseWriter, r *http.Request) {
	var req pairReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	device := req.DeviceName
	if device == "" {
		device = deviceFromUA(r.UserAgent())
	}
	token, u, err := s.st.RedeemPairCode(strings.ToUpper(strings.TrimSpace(req.Code)), device)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, loginResp{Token: token, User: u})
}

type changePwReq struct {
	OldPassword string `json:"old_password"`
	NewPassword string `json:"new_password"`
}

func (s *Server) handleChangePassword(w http.ResponseWriter, r *http.Request, u *store.User) {
	var req changePwReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	if err := s.st.ChangeOwnPassword(u.ID, req.OldPassword, req.NewPassword); err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"ok": "1"})
}

func deviceFromUA(ua string) string {
	ua = strings.TrimSpace(ua)
	if ua == "" {
		return "未知设备"
	}
	if len(ua) > 60 {
		ua = ua[:60]
	}
	return ua
}
