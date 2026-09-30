package store

import (
	"archive/zip"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"memosync/internal/uuid"
)

// Memo 领域模型，JSON 命名与客户端 API 一致（snake_case）。
type Memo struct {
	ID          string       `json:"id"`
	CreatorID   string       `json:"creator_id"`
	SpaceID     string       `json:"space_id"`
	Type        string       `json:"type"` // note | checklist | asset
	Title       string       `json:"title"`
	Content     string       `json:"content"` // JSON 文本：{"type":"note","text":...} 等，结构由客户端定义
	Color       string       `json:"color"`
	Pinned      bool         `json:"pinned"`
	Archived    bool         `json:"archived"`
	RemindAt    *int64       `json:"remind_at"`
	RemindAts   []int64      `json:"remind_ats"`
	Tags        []string     `json:"tags"`
	CreatedAt   int64        `json:"created_at"`
	UpdatedAt   int64        `json:"updated_at"`
	ClientMtime int64        `json:"client_mtime"`
	DeletedAt   *int64       `json:"deleted_at"`
	Version     int64        `json:"version"`
	Attachments []Attachment `json:"attachments"`
}

type Attachment struct {
	ID       string `json:"id"` // = sha256 hex（内容寻址）
	Sha256   string `json:"sha256"`
	Filename string `json:"filename"`
	Mime     string `json:"mime"`
	Size     int64  `json:"size"`
}

// PushInput 客户端推送的单条变更。
type PushInput struct {
	Memo        Memo  `json:"memo"`
	BaseVersion int64 `json:"base_version"`
}

// PushResult 单条推送结果。status: ok | conflict | error
type PushResult struct {
	ID      string `json:"id"`
	Status  string `json:"status"`
	Message string `json:"message,omitempty"`
	Version int64  `json:"version,omitempty"`
	Memo    *Memo  `json:"memo,omitempty"` // conflict 时返回服务端当前版本（nil 表示服务端已不存在）
}

var validTypes = map[string]bool{"note": true, "checklist": true, "asset": true}

// Push 处理一批推送，逐条返回结果。冲突语义见 docs/方案设计.md §4：
// base_version 与服务端当前一致才接受；否则 conflict（客户端把落败内容另存冲突副本）。
func (s *Store) Push(userID string, inputs []PushInput) ([]PushResult, error) {
	results := make([]PushResult, 0, len(inputs))
	for i := range inputs {
		results = append(results, s.pushOne(userID, &inputs[i]))
	}
	return results, nil
}

func (s *Store) pushOne(userID string, in *PushInput) PushResult {
	m := &in.Memo
	if !uuid.Valid(m.ID) {
		return PushResult{ID: m.ID, Status: "error", Message: "备忘录 id 非法"}
	}
	if !validTypes[m.Type] {
		return PushResult{ID: m.ID, Status: "error", Message: "type 只能是 note/checklist"}
	}
	if m.Content == "" {
		m.Content = "{}"
	}
	if !json.Valid([]byte(m.Content)) {
		return PushResult{ID: m.ID, Status: "error", Message: "content 不是合法 JSON"}
	}
	if m.ClientMtime <= 0 {
		m.ClientMtime = now()
	}
	// 附件引用校验：必须全部是已上传的 blob（客户端先传附件再推备忘录）。
	seen := map[string]bool{}
	for i := range m.Attachments {
		a := &m.Attachments[i]
		if a.Sha256 == "" {
			a.Sha256 = a.ID
		}
		if !isSha256Hex(a.Sha256) {
			return PushResult{ID: m.ID, Status: "error", Message: "附件 sha256 非法"}
		}
		if seen[a.Sha256] {
			return PushResult{ID: m.ID, Status: "error", Message: "附件重复引用"}
		}
		seen[a.Sha256] = true
		if !s.blobExists(a.Sha256) {
			return PushResult{ID: m.ID, Status: "error", Message: "附件尚未上传: " + a.Filename}
		}
		a.ID = a.Sha256
	}
	if m.Attachments == nil {
		m.Attachments = []Attachment{}
	}
	attJSON, err := json.Marshal(m.Attachments)
	if err != nil {
		return PushResult{ID: m.ID, Status: "error", Message: "附件序列化失败"}
	}
	m.Tags = sanitizeTags(m.Tags)
	tagsJSON, err := json.Marshal(m.Tags)
	if err != nil {
		return PushResult{ID: m.ID, Status: "error", Message: "标签序列化失败"}
	}
	// 多条提醒：remind_ats 优先；旧客户端只带 remind_at 时退化单条。
	// remind_at 列冗余存最早一条（排序/兼容用）。
	ats := dedupeSorted(m.RemindAts)
	if len(ats) == 0 && m.RemindAt != nil {
		ats = []int64{*m.RemindAt}
	}
	m.RemindAts = ats
	if len(ats) > 0 {
		first := ats[0]
		m.RemindAt = &first
	} else {
		m.RemindAt = nil
	}

	ok, err := s.CanAccessSpace(userID, m.SpaceID)
	if err != nil {
		return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
	}
	if !ok {
		return PushResult{ID: m.ID, Status: "error", Message: "无权访问该空间"}
	}

	tx, err := s.db.Begin()
	if err != nil {
		return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
	}
	defer tx.Rollback()

	var curVersion int64
	var curCreator string
	err = tx.QueryRow(`SELECT version, creator_id FROM memos WHERE id=?`, m.ID).Scan(&curVersion, &curCreator)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if in.BaseVersion != 0 {
			// 客户端引用了不存在的基线：服务端从未有过该 id（或已彻底清理）。
			return PushResult{ID: m.ID, Status: "conflict", Memo: nil}
		}
		ts := now()
		if _, err := tx.Exec(`INSERT INTO memos
			(id, creator_id, space_id, type, title, content, color, pinned, archived, remind_at,
			 created_at, updated_at, client_mtime, deleted_at, version, attachments, tags)
			VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)`,
			m.ID, userID, m.SpaceID, m.Type, m.Title, m.Content, m.Color, b2i(m.Pinned), b2i(m.Archived),
			m.RemindAt, ts, ts, m.ClientMtime, m.DeletedAt, string(attJSON), string(tagsJSON)); err != nil {
			return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
		}
		if err := recordChange(tx, m.ID, m.SpaceID, m.DeletedAt != nil); err != nil {
			return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
		}
		if err := syncReminders(tx, m.ID, ats); err != nil {
			return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
		}
		if err := tx.Commit(); err != nil {
			return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
		}
		out := *m
		out.CreatorID = userID
		out.Version = 1
		out.CreatedAt, out.UpdatedAt = ts, ts
		s.notifyChange()
		return PushResult{ID: m.ID, Status: "ok", Version: 1, Memo: &out}

	case err != nil:
		return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
	}

	// 已存在：乐观锁校验；双方都是清单时先尝试条目级合并（家人同时改购物清单不产生冲突副本）
	if in.BaseVersion != curVersion {
		serverRow := s.scanMemoFull(tx.QueryRow(`SELECT `+memoCols+` FROM memos WHERE id=?`, m.ID))
		merged := false
		if serverRow != nil && serverRow.Type == "checklist" && m.Type == "checklist" {
			if mc, ok := mergeChecklist(serverRow.Content, m.Content); ok {
				m.Content = mc
				merged = true
			}
		}
		if !merged {
			if serverRow != nil {
				// 事务内用同一连接查询提醒（单连接池上不允许嵌套新查询）
				rrows, qerr := tx.Query(`SELECT at FROM reminders WHERE memo_id=? ORDER BY at`, serverRow.ID)
				if qerr == nil {
					list := []int64{}
					for rrows.Next() {
						var at int64
						if rrows.Scan(&at) == nil {
							list = append(list, at)
						}
					}
					rrows.Close()
					serverRow.RemindAts = list
				}
			}
			return PushResult{ID: m.ID, Status: "conflict", Memo: serverRow}
		}
	}
	ts := now()
	newVersion := curVersion + 1
	if _, err := tx.Exec(`UPDATE memos SET
		type=?, title=?, content=?, color=?, pinned=?, archived=?, remind_at=?,
		updated_at=?, client_mtime=?, deleted_at=?, version=?, attachments=?, tags=?
		WHERE id=?`,
		m.Type, m.Title, m.Content, m.Color, b2i(m.Pinned), b2i(m.Archived), m.RemindAt,
		ts, m.ClientMtime, m.DeletedAt, newVersion, string(attJSON), string(tagsJSON), m.ID); err != nil {
		return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
	}
	if err := recordChange(tx, m.ID, m.SpaceID, m.DeletedAt != nil); err != nil {
		return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
	}
	if err := syncReminders(tx, m.ID, ats); err != nil {
		return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
	}
	if err := tx.Commit(); err != nil {
		return PushResult{ID: m.ID, Status: "error", Message: err.Error()}
	}
	out := *m
	out.Version = newVersion
	out.UpdatedAt = ts
	out.CreatorID = curCreator
	s.notifyChange()
	return PushResult{ID: m.ID, Status: "ok", Version: newVersion, Memo: &out}
}

func recordChange(tx *sql.Tx, memoID, spaceID string, deleted bool) error {
	_, err := tx.Exec(`INSERT INTO changes(memo_id, space_id, deleted, at) VALUES(?,?,?,?)`,
		memoID, spaceID, b2i(deleted), now())
	return err
}

const memoCols = `id, creator_id, space_id, type, title, content, color, pinned, archived,
	remind_at, created_at, updated_at, client_mtime, deleted_at, version, attachments, tags`

// ChangeEntry pull 返回的一条变更。Memo 为 nil 表示该条目已物理清理，
// 客户端按 MemoID 墓碑处理（删除本地对应条目）。
type ChangeEntry struct {
	Seq     int64  `json:"seq"`
	MemoID  string `json:"memo_id"`
	Deleted bool   `json:"deleted"`
	Memo    *Memo  `json:"memo"`
}

// Pull 拉取增量。since=客户端游标（changes.seq），只返回该用户可见空间的变更。
func (s *Store) Pull(userID string, since int64, limit int64) (entries []ChangeEntry, nextCursor int64, latestSeq int64, err error) {
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	spaces, err := s.SpacesForUser(userID)
	if err != nil {
		return nil, since, 0, err
	}
	latest, err := s.LatestSeq()
	if err != nil {
		return nil, since, 0, err
	}
	if len(spaces) == 0 {
		return nil, since, latest, nil
	}
	ph := strings.TrimRight(strings.Repeat("?,", len(spaces)), ",")
	args := make([]any, 0, len(spaces)+2)
	args = append(args, since)
	for _, sp := range spaces {
		args = append(args, sp.ID)
	}
	args = append(args, limit)
	rows, err := s.db.Query(`SELECT c.seq, c.memo_id, c.deleted, `+withPrefix(memoCols, "m.")+`
		FROM changes c LEFT JOIN memos m ON m.id = c.memo_id
		WHERE c.seq > ? AND c.space_id IN (`+ph+`)
		ORDER BY c.seq ASC LIMIT ?`, args...)
	if err != nil {
		return nil, since, latest, err
	}
	defer rows.Close()
	var out []ChangeEntry
	var last int64
	for rows.Next() {
		var e ChangeEntry
		var deleted int
		var (
			memoID                                              sql.NullString
			id, creator, space, typ, title, content, color, att sql.NullString
			tags                                                sql.NullString
			pinned, archived, remindAt, createdAt, updatedAt    sql.NullInt64
			clientMtime, deletedAt, version                     sql.NullInt64
		)
		if err := rows.Scan(&e.Seq, &memoID, &deleted, &id, &creator, &space, &typ, &title, &content, &color,
			&pinned, &archived, &remindAt, &createdAt, &updatedAt, &clientMtime, &deletedAt, &version, &att, &tags); err != nil {
			return nil, since, latest, err
		}
		e.Deleted = deleted == 1
		e.MemoID = memoID.String
		if id.Valid && id.String != "" {
			m := &Memo{
				ID: id.String, CreatorID: creator.String, SpaceID: space.String, Type: typ.String,
				Title: title.String, Content: content.String, Color: color.String,
				Pinned: pinned.Int64 == 1, Archived: archived.Int64 == 1,
				CreatedAt: createdAt.Int64, UpdatedAt: updatedAt.Int64, ClientMtime: clientMtime.Int64,
				Version: version.Int64, Attachments: []Attachment{}, Tags: []string{}, RemindAts: []int64{},
			}
			if remindAt.Valid {
				v := remindAt.Int64
				m.RemindAt = &v
			}
			if deletedAt.Valid {
				v := deletedAt.Int64
				m.DeletedAt = &v
			}
			if att.Valid && json.Unmarshal([]byte(att.String), &m.Attachments) != nil {
				m.Attachments = []Attachment{}
			}
			if tags.Valid && json.Unmarshal([]byte(tags.String), &m.Tags) != nil {
				m.Tags = []string{}
			}
			e.Memo = m
		}
		out = append(out, e)
		last = e.Seq
	}
	if err := rows.Err(); err != nil {
		return nil, since, latest, err
	}
	// 单连接池：显式关闭结果集，fillRemindAts 的新查询才能拿到连接
	rows.Close()
	// 批量带出每条备忘录的提醒时间列表
	s.fillRemindAts(out)

	nextCursor = since
	if last > 0 {
		nextCursor = last
	}
	return out, nextCursor, latest, nil
}

// LatestSeq 当前变更序号最大值（客户端全量对齐用）。
func (s *Store) LatestSeq() (int64, error) {
	var v sql.NullInt64
	err := s.db.QueryRow(`SELECT MAX(seq) FROM changes`).Scan(&v)
	if err != nil {
		return 0, err
	}
	if !v.Valid {
		return 0, nil
	}
	return v.Int64, nil
}

// withPrefix 给列名清单加表前缀：withPrefix("id, title", "m.") => "m.id, m.title"
func withPrefix(cols, p string) string {
	parts := strings.Split(cols, ",")
	for i := range parts {
		parts[i] = p + strings.TrimSpace(parts[i])
	}
	return strings.Join(parts, ", ")
}

// ---------- 附件 blob（内容寻址：attachments/ab/cd/<sha256hex>） ----------

func isSha256Hex(s string) bool {
	if len(s) != 64 {
		return false
	}
	for i := 0; i < 64; i++ {
		c := s[i]
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

func (s *Store) blobPath(sha string) string {
	return filepath.Join(s.dataDir, "attachments", sha[0:2], sha[2:4], sha)
}

func (s *Store) blobExists(sha string) bool {
	st, err := os.Stat(s.blobPath(sha))
	return err == nil && !st.IsDir()
}

// SaveBlob 流式落盘，返回 sha256 与大小。同内容 blob 已存在则直接复用（秒传）。
func (s *Store) SaveBlob(r io.Reader) (sha string, size int64, err error) {
	tmp, err := os.CreateTemp(filepath.Join(s.dataDir, "tmp"), "upload-*")
	if err != nil {
		return "", 0, err
	}
	tmpName := tmp.Name()
	defer func() {
		tmp.Close()
		os.Remove(tmpName)
	}()
	h := sha256.New()
	size, err = io.Copy(io.MultiWriter(tmp, h), r)
	if err != nil {
		return "", 0, err
	}
	if err := tmp.Sync(); err != nil {
		return "", 0, err
	}
	if err := tmp.Close(); err != nil {
		return "", 0, err
	}
	sha = hex.EncodeToString(h.Sum(nil))
	dst := s.blobPath(sha)
	if s.blobExists(sha) {
		return sha, size, nil // 秒传
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", 0, err
	}
	if err := os.Rename(tmpName, dst); err != nil {
		return "", 0, err
	}
	return sha, size, nil
}

func (s *Store) OpenBlob(sha string) (*os.File, error) {
	if !isSha256Hex(sha) {
		return nil, ErrBadRequest
	}
	f, err := os.Open(s.blobPath(sha))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	return f, err
}

func (s *Store) BlobSize(sha string) (int64, error) {
	if !isSha256Hex(sha) {
		return 0, ErrBadRequest
	}
	st, err := os.Stat(s.blobPath(sha))
	if errors.Is(err, os.ErrNotExist) {
		return 0, ErrNotFound
	}
	return st.Size(), err
}

// BlobsExist 批量检查（秒传 check 接口）。
func (s *Store) BlobsExist(shas []string) (map[string]int64, error) {
	out := map[string]int64{}
	for _, sha := range shas {
		if !isSha256Hex(sha) {
			return nil, fmt.Errorf("%w: sha256 非法: %s", ErrBadRequest, sha)
		}
		if st, err := os.Stat(s.blobPath(sha)); err == nil && !st.IsDir() {
			out[sha] = st.Size()
		}
	}
	return out, nil
}

// ---------- 备份 ----------

// RunBackup：SQLite VACUUM INTO 快照 + 附件目录复制（先试硬链接，失败则复制）。保留最近 keep 份。
func (s *Store) RunBackup(keep int) (string, error) {
	if keep <= 0 {
		keep = 7
	}
	// 名字统一带 .db 后缀（与 ListBackups/恢复接口一致）
	name := "backup-" + time.Now().Format("20060102-150405") + ".db"
	backupDir := filepath.Join(s.dataDir, "backup")
	dbPath := filepath.Join(backupDir, name)
	if err := os.MkdirAll(backupDir, 0o755); err != nil {
		return "", err
	}
	// VACUUM INTO 不允许目标已存在
	os.Remove(dbPath)
	q := "VACUUM INTO '" + strings.ReplaceAll(filepath.ToSlash(dbPath), "'", "''") + "'"
	if _, err := s.db.Exec(q); err != nil {
		return "", fmt.Errorf("VACUUM INTO: %w", err)
	}
	attDir := filepath.Join(backupDir, strings.TrimSuffix(name, ".db")+".attachments")
	if err := os.MkdirAll(attDir, 0o755); err != nil {
		return "", err
	}
	srcRoot := filepath.Join(s.dataDir, "attachments")
	_ = filepath.WalkDir(srcRoot, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		rel, _ := filepath.Rel(srcRoot, path)
		dst := filepath.Join(attDir, rel)
		os.MkdirAll(filepath.Dir(dst), 0o755)
		if os.Link(path, dst) != nil {
			return copyFile(path, dst)
		}
		return nil
	})
	s.pruneBackups(keep)
	return name, nil
}

func (s *Store) pruneBackups(keep int) {
	entries, err := os.ReadDir(filepath.Join(s.dataDir, "backup"))
	if err != nil {
		return
	}
	var names []string
	for _, e := range entries {
		n := e.Name()
		if strings.HasPrefix(n, "backup-") && strings.HasSuffix(n, ".db") {
			names = append(names, strings.TrimSuffix(strings.TrimPrefix(n, "backup-"), ".db"))
		}
	}
	sort.Strings(names)
	for i := 0; i < len(names)-keep; i++ {
		stamp := names[i]
		os.Remove(filepath.Join(s.dataDir, "backup", "backup-"+stamp+".db"))
		os.RemoveAll(filepath.Join(s.dataDir, "backup", "backup-"+stamp+".attachments"))
	}
}

type BackupInfo struct {
	Name string    `json:"name"`
	Size int64     `json:"size"`
	At   time.Time `json:"at"`
}

func (s *Store) ListBackups() ([]BackupInfo, error) {
	entries, err := os.ReadDir(filepath.Join(s.dataDir, "backup"))
	if err != nil {
		return nil, err
	}
	var out []BackupInfo
	for _, e := range entries {
		n := e.Name()
		if !strings.HasPrefix(n, "backup-") || !strings.HasSuffix(n, ".db") {
			continue
		}
		st, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, BackupInfo{Name: n, Size: st.Size(), At: st.ModTime()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].At.After(out[j].At) })
	return out, nil
}

// ---------- 统计 ----------

type Stats struct {
	Users        int   `json:"users"`
	Memos        int   `json:"memos"`
	DeletedMemos int   `json:"deleted_memos"`
	Attachments  int64 `json:"attachments"` // 附件文件数
	BlobBytes    int64 `json:"blob_bytes"`
	BackupCount  int   `json:"backup_count"`
}

func (s *Store) Stats() (Stats, error) {
	var st Stats
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&st.Users); err != nil {
		return st, err
	}
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM memos WHERE deleted_at IS NULL`).Scan(&st.Memos); err != nil {
		return st, err
	}
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM memos WHERE deleted_at IS NOT NULL`).Scan(&st.DeletedMemos); err != nil {
		return st, err
	}
	root := filepath.Join(s.dataDir, "attachments")
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if info, e := d.Info(); e == nil {
			st.Attachments++
			st.BlobBytes += info.Size()
		}
		return nil
	})
	bk, _ := s.ListBackups()
	st.BackupCount = len(bk)
	return st, nil
}

// ---------- 维护任务（每日由 main 的调度器调用） ----------

// Cleanup：
//  1) 物理删除 deleted_at 超过 30 天的备忘录（changes 行保留，pull 按墓碑下发）
//  2) 删除 90 天前的 changes 行（对齐过的设备不受影响）
//  3) 清理 7 天以上未被任何备忘录引用的附件 blob
//  4) 清理过期配对码与 1 小时前的临时文件
func (s *Store) Cleanup() error {
	cutoff := time.Now().AddDate(0, 0, -30).UnixMilli()
	if _, err := s.db.Exec(`DELETE FROM memos WHERE deleted_at IS NOT NULL AND deleted_at < ?`, cutoff); err != nil {
		return err
	}
	// 已物理清理的备忘录，其提醒一并清理
	if _, err := s.db.Exec(`DELETE FROM reminders WHERE memo_id NOT IN (SELECT id FROM memos)`); err != nil {
		return err
	}
	changeCutoff := time.Now().AddDate(0, 0, -90).UnixMilli()
	if _, err := s.db.Exec(`DELETE FROM changes WHERE at < ?`, changeCutoff); err != nil {
		return err
	}
	if _, err := s.db.Exec(`DELETE FROM pair_codes WHERE expires_at < ?`, now()); err != nil {
		return err
	}
	// 附件引用集合
	ref := map[string]bool{}
	rows, err := s.db.Query(`SELECT attachments FROM memos`)
	if err != nil {
		return err
	}
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			rows.Close()
			return err
		}
		var list []Attachment
		if json.Unmarshal([]byte(raw), &list) == nil {
			for _, a := range list {
				sha := a.Sha256
				if sha == "" {
					sha = a.ID
				}
				if isSha256Hex(sha) {
					ref[sha] = true
				}
			}
		}
	}
	rows.Close()
	blobCutoff := time.Now().AddDate(0, 0, -7)
	root := filepath.Join(s.dataDir, "attachments")
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		sha := filepath.Base(path)
		if !isSha256Hex(sha) || ref[sha] {
			return nil
		}
		if info, e := d.Info(); e == nil && info.ModTime().Before(blobCutoff) {
			os.Remove(path)
		}
		return nil
	})
	tmpDir := filepath.Join(s.dataDir, "tmp")
	if entries, err := os.ReadDir(tmpDir); err == nil {
		for _, e := range entries {
			if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > time.Hour {
				os.Remove(filepath.Join(tmpDir, e.Name()))
			}
		}
	}
	return nil
}

// ---------- 导出 ----------

// ExportZip 把全部备忘录（含回收站）+ 附件打成 zip，写入 w。
func (s *Store) ExportZip(w io.Writer) error {
	zw := zip.NewWriter(w)
	memos, err := s.exportMemos()
	if err != nil {
		return err
	}
	f, err := zw.Create("memos.json")
	if err != nil {
		return err
	}
	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	if err := enc.Encode(memos); err != nil {
		return err
	}
	for _, m := range memos {
		for _, a := range m.Attachments {
			if err := s.zipBlob(zw, a.Sha256); err != nil {
				return err
			}
		}
	}
	return zw.Close()
}

func (s *Store) zipBlob(zw *zip.Writer, sha string) error {
	src, err := s.OpenBlob(sha)
	if err != nil {
		if errors.Is(err, ErrNotFound) {
			return nil // 缺失的 blob 跳过，不阻塞导出
		}
		return err
	}
	defer src.Close()
	dst, err := zw.Create("attachments/" + sha)
	if err != nil {
		return err
	}
	_, err = io.Copy(dst, src)
	return err
}

func (s *Store) exportMemos() ([]Memo, error) {
	rows, err := s.db.Query(`SELECT ` + memoCols + ` FROM memos`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Memo
	for rows.Next() {
		if m := s.scanMemoFull(rows); m != nil {
			out = append(out, *m)
		}
	}
	return out, rows.Err()
}

// ---------- 扫描辅助 ----------

// scanner：*sql.Rows 与 *sql.Row 都满足。
type scanner interface {
	Scan(dest ...any) error
}

// scanMemoFull 扫描 memoCols 顺序的 17 列；已物理删除的行扫出 nil。
func (s *Store) scanMemoFull(scan scanner) *Memo {
	var (
		m           Memo
		pinned      int
		archived    int
		remindAt    sql.NullInt64
		deletedAt   sql.NullInt64
		attJSON     sql.NullString
		tagsJSON    sql.NullString
	)
	if err := scan.Scan(&m.ID, &m.CreatorID, &m.SpaceID, &m.Type, &m.Title, &m.Content, &m.Color,
		&pinned, &archived, &remindAt, &m.CreatedAt, &m.UpdatedAt, &m.ClientMtime, &deletedAt,
		&m.Version, &attJSON, &tagsJSON); err != nil {
		return nil
	}
	m.Pinned = pinned == 1
	m.Archived = archived == 1
	if remindAt.Valid {
		v := remindAt.Int64
		m.RemindAt = &v
	}
	if deletedAt.Valid {
		v := deletedAt.Int64
		m.DeletedAt = &v
	}
	m.Attachments = []Attachment{}
	if attJSON.Valid && json.Unmarshal([]byte(attJSON.String), &m.Attachments) != nil {
		m.Attachments = []Attachment{}
	}
	m.Tags = []string{}
	if tagsJSON.Valid && json.Unmarshal([]byte(tagsJSON.String), &m.Tags) != nil {
		m.Tags = []string{}
	}
	return &m
}

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}

// ---------- 多条提醒 ----------

// dedupeSorted 去重升序（同一时刻只留一条），上限 20 条防滥用。
func dedupeSorted(in []int64) []int64 {
	if len(in) == 0 {
		return nil
	}
	seen := make(map[int64]struct{}, len(in))
	out := make([]int64, 0, len(in))
	for _, v := range in {
		if v <= 0 {
			continue
		}
		if _, dup := seen[v]; dup {
			continue
		}
		seen[v] = struct{}{}
		out = append(out, v)
	}
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	if len(out) > 20 {
		out = out[:20]
	}
	return out
}

// syncReminders 重建备忘录的提醒时间表（提醒状态随之清零，改时间=重新提醒）。
func syncReminders(tx *sql.Tx, memoID string, ats []int64) error {
	if _, err := tx.Exec(`DELETE FROM reminders WHERE memo_id=?`, memoID); err != nil {
		return err
	}
	for _, at := range ats {
		if _, err := tx.Exec(`INSERT INTO reminders(memo_id, at) VALUES(?,?)`, memoID, at); err != nil {
			return err
		}
	}
	return nil
}

// fillRemindAts 批量填充 pull 结果的 remind_ats。
func (s *Store) fillRemindAts(entries []ChangeEntry) {
	ids := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.Memo != nil {
			ids = append(ids, e.Memo.ID)
		}
	}
	if len(ids) == 0 {
		return
	}
	ph := strings.TrimRight(strings.Repeat("?,", len(ids)), ",")
	args := make([]any, 0, len(ids))
	for _, id := range ids {
		args = append(args, id)
	}
	rows, err := s.db.Query(`SELECT memo_id, at FROM reminders WHERE memo_id IN (`+ph+`) ORDER BY at`, args...)
	if err != nil {
		return // 提醒列表拿不到不阻塞同步主流程
	}
	defer rows.Close()
	byMemo := map[string][]int64{}
	for rows.Next() {
		var memoID string
		var at int64
		if err := rows.Scan(&memoID, &at); err == nil {
			byMemo[memoID] = append(byMemo[memoID], at)
		}
	}
	rows.Close()
	for i := range entries {
		if entries[i].Memo != nil {
			if list, ok := byMemo[entries[i].Memo.ID]; ok {
				entries[i].Memo.RemindAts = list
			}
		}
	}
}

// fillRemindAtsOne 单条填充（冲突返回用）。
func (s *Store) fillRemindAtsOne(m *Memo) {
	if m == nil {
		return
	}
	rows, err := s.db.Query(`SELECT at FROM reminders WHERE memo_id=? ORDER BY at`, m.ID)
	if err != nil {
		return
	}
	defer rows.Close()
	list := []int64{}
	for rows.Next() {
		var at int64
		if rows.Scan(&at) == nil {
			list = append(list, at)
		}
	}
	m.RemindAts = list
}

// ---------- 清单条目级合并 ----------

type chkItem struct {
	Text string `json:"text"`
	Done bool   `json:"done"`
}

type chkContent struct {
	Type  string    `json:"type"`
	Text  string    `json:"text"`
	Items []chkItem `json:"items"`
}

// mergeChecklist 双方都是清单时的条目级合并：
//   - 条目取并集：以服务端顺序为骨架，客户端独有条目按序追加
//   - 勾选状态取"或"（任何一端勾选即视为完成）
//   - 单端删除不可识别（无基线内容），条目不会被合并丢弃——数据只增不减
//
// 无法安全合并（任一方不是合法清单）返回 ok=false，走原冲突流程。
func mergeChecklist(serverContent, clientContent string) (string, bool) {
	var sc, cc chkContent
	if json.Unmarshal([]byte(serverContent), &sc) != nil || json.Unmarshal([]byte(clientContent), &cc) != nil {
		return "", false
	}
	if sc.Type != "checklist" || cc.Type != "checklist" {
		return "", false
	}
	result := make([]chkItem, 0, len(sc.Items)+len(cc.Items))
	index := make(map[string]int, len(sc.Items)+len(cc.Items))
	for _, it := range sc.Items {
		key := strings.TrimSpace(it.Text)
		if _, dup := index[key]; dup {
			continue
		}
		index[key] = len(result)
		result = append(result, it)
	}
	for _, it := range cc.Items {
		key := strings.TrimSpace(it.Text)
		if idx, exists := index[key]; exists {
			result[idx].Done = result[idx].Done || it.Done
		} else {
			index[key] = len(result)
			result = append(result, it)
		}
	}
	out := chkContent{Type: "checklist", Text: sc.Text, Items: result}
	b, err := json.Marshal(out)
	if err != nil {
		return "", false
	}
	return string(b), true
}

// sanitizeTags 清洗标签：去空格、去空、去重、限量（≤10 个，每个 ≤24 字符）。
func sanitizeTags(in []string) []string {
	if len(in) == 0 {
		return []string{}
	}
	out := make([]string, 0, len(in))
	seen := make(map[string]struct{}, len(in))
	for _, t := range in {
		t = strings.TrimSpace(t)
		if t == "" {
			continue
		}
		if len([]rune(t)) > 24 {
			r := []rune(t)
			t = string(r[:24])
		}
		if _, dup := seen[t]; dup {
			continue
		}
		seen[t] = struct{}{}
		out = append(out, t)
		if len(out) >= 10 {
			break
		}
	}
	return out
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}
