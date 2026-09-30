package api

import (
	"net/http"

	"memosync/internal/store"
)

// 群组空间（工作组）管理接口。

type createSpaceReq struct {
	Name string `json:"name"`
}

// handleSpaceCreate 创建工作组（任何登录用户）。
func (s *Server) handleSpaceCreate(w http.ResponseWriter, r *http.Request, u *store.User) {
	var req createSpaceReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	sp, err := s.st.CreateGroupSpace(u.ID, req.Name)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, sp)
}

type addMemberReq struct {
	Username string `json:"username"`
}

func (s *Server) handleSpaceMembers(w http.ResponseWriter, r *http.Request, u *store.User) {
	switch r.Method {
	case http.MethodGet:
		members, err := s.st.SpaceMembers(r.PathValue("id"), u.ID)
		if err != nil {
			mapStoreErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"members": members})
	case http.MethodPost:
		var req addMemberReq
		if !readJSON(w, r, &req, 0) {
			return
		}
		m, err := s.st.AddSpaceMember(r.PathValue("id"), u.ID, req.Username)
		if err != nil {
			mapStoreErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, m)
	default:
		writeErr(w, http.StatusMethodNotAllowed, "不支持的方法")
	}
}

func (s *Server) handleSpaceMemberDelete(w http.ResponseWriter, r *http.Request, u *store.User) {
	if err := s.st.RemoveSpaceMember(r.PathValue("id"), u.ID, r.PathValue("uid")); err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"ok": "1"})
}

func (s *Server) handleSpaceDelete(w http.ResponseWriter, r *http.Request, u *store.User) {
	if err := s.st.DeleteGroupSpace(r.PathValue("id"), u.ID); err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"ok": "1"})
}
