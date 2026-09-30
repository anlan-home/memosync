// Package store：SQLite 存储层。所有 SQL 集中在这里，API 层不直接碰 SQL。
package store

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"memosync/internal/uuid"

	_ "modernc.org/sqlite"
)

// 领域错误：API 层据此映射 HTTP 状态码。
var (
	ErrNotFound   = errors.New("not found")
	ErrAuth       = errors.New("用户名或密码错误，或账号已被停用")
	ErrConflict   = errors.New("conflict")
	ErrBadRequest = errors.New("bad request")
	ErrNoSpace    = errors.New("无权访问该空间")
)

type Store struct {
	db           *sql.DB
	dataDir      string
	changeNotify func() // 变更提交后的回调（SSE 推送用），由 api 层注入
}

// SetChangeNotify 注入变更回调；fn 在每次成功写入后同步调用，必须非阻塞。
func (s *Store) SetChangeNotify(fn func()) { s.changeNotify = fn }

func (s *Store) notifyChange() {
	if s.changeNotify != nil {
		s.changeNotify()
	}
}

func Open(dataDir string) (*Store, error) {
	if err := os.MkdirAll(filepath.Join(dataDir, "attachments"), 0o755); err != nil {
		return nil, fmt.Errorf("创建数据目录: %w", err)
	}
	if err := os.MkdirAll(filepath.Join(dataDir, "backup"), 0o755); err != nil {
		return nil, fmt.Errorf("创建备份目录: %w", err)
	}
	if err := os.MkdirAll(filepath.Join(dataDir, "tmp"), 0o755); err != nil {
		return nil, fmt.Errorf("创建临时目录: %w", err)
	}
	dbPath := filepath.ToSlash(filepath.Join(dataDir, "memo.db"))
	// modernc 驱动 DSN：busy_timeout 兜底锁等待；WAL 提升并发读；外键开启。
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)&_pragma=synchronous(NORMAL)", dbPath)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// 单连接串行化：家庭规模足够，彻底规避 SQLITE_BUSY 与写竞争。
	db.SetMaxOpenConns(1)
	s := &Store{db: db, dataDir: dataDir}
	if err := s.migrate(); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) DB() *sql.DB { return s.db }

func (s *Store) DataDir() string { return s.dataDir }

func (s *Store) migrate() error {
	const schema = `
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  username      TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  nickname      TEXT NOT NULL,
  role          TEXT NOT NULL DEFAULT 'member',
  disabled      INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  name       TEXT NOT NULL DEFAULT '',
  token_hash TEXT UNIQUE NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
CREATE TABLE IF NOT EXISTS spaces (
  id       TEXT PRIMARY KEY,
  name     TEXT NOT NULL,
  type     TEXT NOT NULL,
  owner_id TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS memos (
  id           TEXT PRIMARY KEY,
  creator_id   TEXT NOT NULL,
  space_id     TEXT NOT NULL,
  type         TEXT NOT NULL DEFAULT 'note',
  title        TEXT NOT NULL DEFAULT '',
  content      TEXT NOT NULL DEFAULT '{}',
  color        TEXT NOT NULL DEFAULT 'default',
  pinned       INTEGER NOT NULL DEFAULT 0,
  archived     INTEGER NOT NULL DEFAULT 0,
  remind_at    INTEGER,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  client_mtime INTEGER NOT NULL,
  deleted_at   INTEGER,
  version      INTEGER NOT NULL DEFAULT 1,
  attachments  TEXT NOT NULL DEFAULT '[]'
);
CREATE INDEX IF NOT EXISTS idx_memos_space ON memos(space_id, deleted_at);
CREATE TABLE IF NOT EXISTS changes (
  seq      INTEGER PRIMARY KEY AUTOINCREMENT,
  memo_id  TEXT NOT NULL,
  space_id TEXT NOT NULL DEFAULT '',
  deleted  INTEGER NOT NULL DEFAULT 0,
  at       INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pair_codes (
  code       TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS reminders (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  memo_id     TEXT NOT NULL,
  at          INTEGER NOT NULL,
  reminded_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_reminders_due ON reminders(at, reminded_at);
CREATE TABLE IF NOT EXISTS space_members (
  space_id TEXT NOT NULL,
  user_id  TEXT NOT NULL,
  role     TEXT NOT NULL DEFAULT 'member',
  PRIMARY KEY(space_id, user_id)
);
CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
`
	_, err := s.db.Exec(schema)
	if err != nil {
		return err
	}
	// 存量库平滑迁移：缺列则补（幂等）
	if err := s.ensureColumn("memos", "tags",
		`ALTER TABLE memos ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'`); err != nil {
		return err
	}
	return nil
}

// ensureColumn 检查表是否含有某列，缺失则执行 DDL。
func (s *Store) ensureColumn(table, column, ddl string) error {
	rows, err := s.db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var cid int
		var name, ctype string
		var notNull int
		var dflt sql.NullString
		var pk int
		if err := rows.Scan(&cid, &name, &ctype, &notNull, &dflt, &pk); err != nil {
			return err
		}
		if name == column {
			return nil
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	_, err = s.db.Exec(ddl)
	return err
}

// ---------- 用户 ----------

type User struct {
	ID        string `json:"id"`
	Username  string `json:"username"`
	Nickname  string `json:"nickname"`
	Role      string `json:"role"`
	Disabled  bool   `json:"disabled"`
	CreatedAt int64  `json:"created_at"`
}

func (s *Store) HasUsers() (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n)
	return n > 0, err
}

// BootstrapAdmin 创建首个管理员，返回其明文初始密码。
func (s *Store) BootstrapAdmin(username, password string) (*User, error) {
	if username == "" {
		username = "admin"
	}
	u, err := s.CreateUser(username, password, "管理员", "admin")
	if err != nil {
		return nil, err
	}
	if err := s.ensureFamilySpace(); err != nil {
		return nil, err
	}
	return u, nil
}

func (s *Store) CreateUser(username, password, nickname, role string) (*User, error) {
	if username == "" || nickname == "" {
		return nil, fmt.Errorf("%w: 用户名和昵称不能为空", ErrBadRequest)
	}
	if len(password) < 4 {
		return nil, fmt.Errorf("%w: 密码至少 4 位", ErrBadRequest)
	}
	if role != "admin" && role != "member" {
		return nil, fmt.Errorf("%w: 角色只能是 admin/member", ErrBadRequest)
	}
	u := &User{ID: uid(), Username: username, Nickname: nickname, Role: role, CreatedAt: now()}
	hash := HashPassword(password)
	_, err := s.db.Exec(`INSERT INTO users(id, username, password_hash, nickname, role, disabled, created_at) VALUES(?,?,?,?,?,0,?)`,
		u.ID, username, hash, nickname, role, u.CreatedAt)
	if err != nil {
		if isUniqueErr(err) {
			return nil, fmt.Errorf("%w: 用户名已存在", ErrBadRequest)
		}
		return nil, err
	}
	if err := s.ensurePersonalSpace(u.ID, nickname); err != nil {
		return nil, err
	}
	return u, nil
}

func (s *Store) ListUsers() ([]User, error) {
	rows, err := s.db.Query(`SELECT id, username, nickname, role, disabled, created_at FROM users ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		var u User
		var dis int
		if err := rows.Scan(&u.ID, &u.Username, &u.Nickname, &u.Role, &dis, &u.CreatedAt); err != nil {
			return nil, err
		}
		u.Disabled = dis == 1
		out = append(out, u)
	}
	return out, rows.Err()
}

// DeleteUser 删除成员：清其设备与个人空间；家庭空间里他创建的备忘录保留（creator 保留原值）。
func (s *Store) DeleteUser(id string) error {
	var role string
	err := s.db.QueryRow(`SELECT role FROM users WHERE id=?`, id).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM users WHERE role='admin' AND id<>?`, id).Scan(&n); err != nil {
		return err
	}
	if role == "admin" && n == 0 {
		return fmt.Errorf("%w: 不能删除最后一位管理员", ErrBadRequest)
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, q := range []string{
		`DELETE FROM devices WHERE user_id=?`,
		`DELETE FROM spaces WHERE type='personal' AND owner_id=?`,
		`DELETE FROM users WHERE id=?`,
	} {
		if _, err := tx.Exec(q, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ResetPassword 管理员重置密码；同时清掉该用户全部设备 token（强制重新登录）。
func (s *Store) ResetPassword(id, newPassword string) error {
	if len(newPassword) < 4 {
		return fmt.Errorf("%w: 密码至少 4 位", ErrBadRequest)
	}
	hash := HashPassword(newPassword)
	res, err := s.db.Exec(`UPDATE users SET password_hash=? WHERE id=?`, hash, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	_, err = s.db.Exec(`DELETE FROM devices WHERE user_id=?`, id)
	return err
}

// ChangeOwnPassword 用户本人修改密码，需验证旧密码。
func (s *Store) ChangeOwnPassword(id, oldPassword, newPassword string) error {
	if len(newPassword) < 4 {
		return fmt.Errorf("%w: 新密码至少 4 位", ErrBadRequest)
	}
	var hash string
	err := s.db.QueryRow(`SELECT password_hash FROM users WHERE id=?`, id).Scan(&hash)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if !VerifyPassword(oldPassword, hash) {
		return fmt.Errorf("%w: 旧密码不正确", ErrBadRequest)
	}
	_, err = s.db.Exec(`UPDATE users SET password_hash=? WHERE id=?`, HashPassword(newPassword), id)
	return err
}

func (s *Store) Login(username, password, deviceName string) (token string, u *User, err error) {
	var (
		id   string
		hash string
		nick string
		role string
		dis  int
	)
	row := s.db.QueryRow(`SELECT id, password_hash, nickname, role, disabled FROM users WHERE username=?`, username)
	if err := row.Scan(&id, &hash, &nick, &role, &dis); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", nil, ErrAuth
		}
		return "", nil, err
	}
	if dis == 1 || !VerifyPassword(password, hash) {
		return "", nil, ErrAuth
	}
	u = &User{ID: id, Username: username, Nickname: nick, Role: role}
	token, tokenHash := NewToken()
	_, err = s.db.Exec(`INSERT INTO devices(id, user_id, name, token_hash, created_at, last_seen) VALUES(?,?,?,?,?,?)`,
		uid(), id, deviceName, tokenHash, now(), now())
	if err != nil {
		return "", nil, err
	}
	return token, u, nil
}

func HashToken(token string) string {
	h := sha256.Sum256([]byte(token))
	return hex.EncodeToString(h[:])
}

type Device struct {
	ID       string `json:"id"`
	UserID   string `json:"user_id"`
	UserName string `json:"user_name"`
	Name     string `json:"name"`
	LastSeen int64  `json:"last_seen"`
}

// Auth 按 token 找设备与用户；同时刷新 last_seen（每次都写，单用户规模可接受）。
func (s *Store) Auth(token string) (*Device, *User, error) {
	th := HashToken(token)
	var d Device
	var dis int
	err := s.db.QueryRow(`
		SELECT d.id, d.user_id, COALESCE(u.username,''), d.name, d.last_seen, COALESCE(u.disabled,0)
		FROM devices d LEFT JOIN users u ON u.id=d.user_id WHERE d.token_hash=?`, th).
		Scan(&d.ID, &d.UserID, &d.UserName, &d.Name, &d.LastSeen, &dis)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil, ErrAuth
	}
	if err != nil {
		return nil, nil, err
	}
	if dis == 1 {
		return nil, nil, ErrAuth
	}
	u := &User{ID: d.UserID, Username: d.UserName}
	// 补全昵称角色
	_ = s.db.QueryRow(`SELECT nickname, role FROM users WHERE id=?`, d.UserID).Scan(&u.Nickname, &u.Role)
	_, _ = s.db.Exec(`UPDATE devices SET last_seen=? WHERE id=?`, now(), d.ID)
	return &d, u, nil
}

func (s *Store) ListDevices() ([]Device, error) {
	rows, err := s.db.Query(`
		SELECT d.id, d.user_id, COALESCE(u.username,''), d.name, d.last_seen
		FROM devices d LEFT JOIN users u ON u.id=d.user_id ORDER BY d.last_seen DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.ID, &d.UserID, &d.UserName, &d.Name, &d.LastSeen); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (s *Store) DeleteDevice(id, userID string, isAdmin bool) error {
	if isAdmin {
		_, err := s.db.Exec(`DELETE FROM devices WHERE id=?`, id)
		return err
	}
	res, err := s.db.Exec(`DELETE FROM devices WHERE id=? AND user_id=?`, id, userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ---------- 空间 ----------

type Space struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Type string `json:"type"`
}

func (s *Store) ensureFamilySpace() error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM spaces WHERE type='family'`).Scan(&n); err != nil {
		return err
	}
	if n == 0 {
		_, err := s.db.Exec(`INSERT INTO spaces(id, name, type, owner_id) VALUES(?,?,?,?)`, uid(), "家庭共享", "family", "")
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) ensurePersonalSpace(userID, nickname string) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM spaces WHERE type='personal' AND owner_id=?`, userID).Scan(&n); err != nil {
		return err
	}
	if n == 0 {
		_, err := s.db.Exec(`INSERT INTO spaces(id, name, type, owner_id) VALUES(?,?,?,?)`,
			"p-"+userID, nickname+"的私人空间", "personal", userID)
		if err != nil {
			return err
		}
	}
	return nil
}

// SpacesForUser 家庭空间（全员共享）+ 自己的个人空间 + 参与的工作组。
func (s *Store) SpacesForUser(userID string) ([]Space, error) {
	rows, err := s.db.Query(`
		SELECT id, name, type FROM spaces
		WHERE type='family'
		   OR (type='personal' AND owner_id=?)
		   OR (type='group' AND id IN (SELECT space_id FROM space_members WHERE user_id=?))
		ORDER BY type`, userID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Space
	for rows.Next() {
		var sp Space
		if err := rows.Scan(&sp.ID, &sp.Name, &sp.Type); err != nil {
			return nil, err
		}
		out = append(out, sp)
	}
	return out, rows.Err()
}

// CanAccessSpace 校验 userID 是否可写/可读该空间。
func (s *Store) CanAccessSpace(userID, spaceID string) (bool, error) {
	var n int
	err := s.db.QueryRow(`
		SELECT COUNT(*) FROM spaces
		WHERE id=? AND (
			type='family'
			OR (type='personal' AND owner_id=?)
			OR (type='group' AND id IN (SELECT space_id FROM space_members WHERE user_id=?))
		)`, spaceID, userID, userID).Scan(&n)
	return n > 0, err
}

// ---------- 配对码 ----------

// CreatePairCode 为指定成员生成一次性配对码。
func (s *Store) CreatePairCode(userID string, ttl time.Duration) (code string, expiresAt int64, err error) {
	var nickname string
	if err := s.db.QueryRow(`SELECT nickname FROM users WHERE id=?`, userID).Scan(&nickname); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", 0, ErrNotFound
		}
		return "", 0, err
	}
	code = PairCode()
	exp := time.Now().Add(ttl)
	_, err = s.db.Exec(`INSERT INTO pair_codes(code, user_id, created_at, expires_at) VALUES(?,?,?,?)`,
		code, userID, now(), exp.UnixMilli())
	return code, exp.UnixMilli(), err
}

// RedeemPairCode 核销配对码（一次性），返回绑定用户并颁发新设备 token。
func (s *Store) RedeemPairCode(code, deviceName string) (token string, u *User, err error) {
	tx, err := s.db.Begin()
	if err != nil {
		return "", nil, err
	}
	defer tx.Rollback()
	var userID string
	var exp int64
	err = tx.QueryRow(`SELECT user_id, expires_at FROM pair_codes WHERE code=?`, code).Scan(&userID, &exp)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil, fmt.Errorf("%w: 配对码无效", ErrBadRequest)
	}
	if err != nil {
		return "", nil, err
	}
	if _, err := tx.Exec(`DELETE FROM pair_codes WHERE code=?`, code); err != nil {
		return "", nil, err
	}
	if err := tx.Commit(); err != nil {
		return "", nil, err
	}
	if time.Now().UnixMilli() > exp {
		return "", nil, fmt.Errorf("%w: 配对码已过期", ErrBadRequest)
	}
	var username, nickname, role string
	if err := s.db.QueryRow(`SELECT username, nickname, role FROM users WHERE id=?`, userID).Scan(&username, &nickname, &role); err != nil {
		return "", nil, err
	}
	token, tokenHash := NewToken()
	_, err = s.db.Exec(`INSERT INTO devices(id, user_id, name, token_hash, created_at, last_seen) VALUES(?,?,?,?,?,?)`,
		uid(), userID, deviceName, tokenHash, now(), now())
	if err != nil {
		return "", nil, err
	}
	return token, &User{ID: userID, Username: username, Nickname: nickname, Role: role}, nil
}

// ---------- 小工具 ----------

func uid() string { return uuid.New() }

func now() int64 { return time.Now().UnixMilli() }

func isUniqueErr(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return contains(msg, "UNIQUE constraint failed") || contains(msg, "constraint failed: users.username")
}

func contains(s, sub string) bool {
	return len(s) >= len(sub) && (s == sub || indexOf(s, sub) >= 0)
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}
