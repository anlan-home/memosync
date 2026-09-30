package api

import (
	"errors"
	"io"
	"net/http"

	"memosync/internal/store"
)

type attachCheckReq struct {
	Sha256s []string `json:"sha256s"`
}

// handleAttachmentCheck 秒传检查：返回服务端已有的 sha → size。
func (s *Server) handleAttachmentCheck(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req attachCheckReq
	if !readJSON(w, r, &req, 0) {
		return
	}
	existing, err := s.st.BlobsExist(req.Sha256s)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"existing": existing})
}

// handleAttachmentUpload multipart 直传（field: file），内容寻址去重。上限 64MB。
func (s *Server) handleAttachmentUpload(w http.ResponseWriter, r *http.Request, _ *store.User) {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<20)
	mr, err := r.MultipartReader()
	if err != nil {
		writeErr(w, http.StatusBadRequest, "需要 multipart/form-data 请求: "+err.Error())
		return
	}
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			writeErr(w, http.StatusBadRequest, "缺少 file 字段")
			return
		}
		if err != nil {
			writeErr(w, http.StatusBadRequest, "解析 multipart 失败: "+err.Error())
			return
		}
		if part.FormName() == "file" {
			sha, size, err := s.st.SaveBlob(part)
			if err != nil {
				var maxErr *http.MaxBytesError
				if errors.As(err, &maxErr) {
					writeErr(w, http.StatusRequestEntityTooLarge, "文件过大")
					return
				}
				writeErr(w, http.StatusInternalServerError, "保存附件失败: "+err.Error())
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{"id": sha, "sha256": sha, "size": size})
			return
		}
	}
}

// handleAttachmentDownload 按 sha256 下载附件，支持 ETag/304。
func (s *Server) handleAttachmentDownload(w http.ResponseWriter, r *http.Request, _ *store.User) {
	id := r.PathValue("id")
	f, err := s.st.OpenBlob(id)
	if err != nil {
		mapStoreErr(w, err)
		return
	}
	defer f.Close()
	etag := `"` + id + `"`
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	st, err := f.Stat()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// 客户端本地有元数据（文件名/mime），统一按二进制流返回
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, "", st.ModTime(), f)
}
