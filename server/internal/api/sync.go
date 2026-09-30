package api

import (
	"net/http"
	"strconv"

	"memosync/internal/store"
)

type pushReq struct {
	Memos []store.PushInput `json:"memos"`
}

type pushResp struct {
	Results   []store.PushResult `json:"results"`
	LatestSeq int64              `json:"latest_seq"`
}

// handlePush 上传本地变更。整体 200，逐条 status（ok/conflict/error）。
func (s *Server) handlePush(w http.ResponseWriter, r *http.Request, u *store.User) {
	var req pushReq
	if !readJSON(w, r, &req, 32<<20) {
		return
	}
	results, err := s.st.Push(u.ID, req.Memos)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	latest, _ := s.st.LatestSeq()
	writeJSON(w, http.StatusOK, pushResp{Results: results, LatestSeq: latest})
}

type pullResp struct {
	Changes    []store.ChangeEntry `json:"changes"`
	NextCursor int64               `json:"next_cursor"`
	LatestSeq  int64               `json:"latest_seq"`
}

func (s *Server) handlePull(w http.ResponseWriter, r *http.Request, u *store.User) {
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	limit, _ := strconv.ParseInt(r.URL.Query().Get("limit"), 10, 64)
	entries, next, latest, err := s.st.Pull(u.ID, since, limit)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	if entries == nil {
		entries = []store.ChangeEntry{}
	}
	writeJSON(w, http.StatusOK, pullResp{Changes: entries, NextCursor: next, LatestSeq: latest})
}

type spacesResp struct {
	Spaces []store.Space `json:"spaces"`
}

func (s *Server) handleSpaces(w http.ResponseWriter, _ *http.Request, u *store.User) {
	spaces, err := s.st.SpacesForUser(u.ID)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	if spaces == nil {
		spaces = []store.Space{}
	}
	writeJSON(w, http.StatusOK, spacesResp{Spaces: spaces})
}
