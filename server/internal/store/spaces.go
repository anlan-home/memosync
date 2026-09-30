package store

import (
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// 群组空间（工作组）：任何成员可创建，创建者为 owner；
// owner 按用户名拉人/移人；组内备忘录全员可见可写；可整体转换备忘录所属空间。

type SpaceMember struct {
	UserID   string `json:"user_id"`
	Username string `json:"username"`
	Nickname string `json:"nickname"`
	Role     string `json:"role"` // owner | member
}

// CreateGroupSpace 创建工作组，创建者为 owner。
func (s *Store) CreateGroupSpace(userID, name string) (*Space, error) {
	name = strings.TrimSpace(name)
	if name == "" || len([]rune(name)) > 30 {
		return nil, fmt.Errorf("%w: 组名不能为空且不超过 30 字", ErrBadRequest)
	}
	sp := &Space{ID: uid(), Name: name, Type: "group"}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`INSERT INTO spaces(id, name, type, owner_id) VALUES(?,?,?,?)`,
		sp.ID, sp.Name, "group", userID); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(`INSERT INTO space_members(space_id, user_id, role) VALUES(?,?, 'owner')`,
		sp.ID, userID); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return sp, nil
}

func (s *Store) isGroupOwner(spaceID, userID string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM spaces
		WHERE id=? AND type='group' AND owner_id=?`, spaceID, userID).Scan(&n)
	return n > 0, err
}

// SpaceMembers 组成员列表（组员可看）。
func (s *Store) SpaceMembers(spaceID, requesterID string) ([]SpaceMember, error) {
	ok, err := s.CanAccessSpace(requesterID, spaceID)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, ErrNoSpace
	}
	rows, err := s.db.Query(`
		SELECT u.id, u.username, u.nickname, COALESCE(sm.role,'member')
		FROM space_members sm JOIN users u ON u.id = sm.user_id
		WHERE sm.space_id=? ORDER BY CASE sm.role WHEN 'owner' THEN 0 ELSE 1 END, u.nickname`, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []SpaceMember{}
	for rows.Next() {
		var m SpaceMember
		if err := rows.Scan(&m.UserID, &m.Username, &m.Nickname, &m.Role); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// AddSpaceMember owner 按用户名拉人。
func (s *Store) AddSpaceMember(spaceID, requesterID, username string) (*SpaceMember, error) {
	owner, err := s.isGroupOwner(spaceID, requesterID)
	if err != nil {
		return nil, err
	}
	if !owner {
		return nil, fmt.Errorf("%w: 只有组长可以添加成员", ErrBadRequest)
	}
	var uid, nickname string
	err = s.db.QueryRow(`SELECT id, nickname FROM users WHERE username=?`, strings.TrimSpace(username)).
		Scan(&uid, &nickname)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, fmt.Errorf("%w: 用户不存在", ErrBadRequest)
	}
	if err != nil {
		return nil, err
	}
	if _, err := s.db.Exec(`INSERT OR IGNORE INTO space_members(space_id, user_id, role) VALUES(?,?, 'member')`,
		spaceID, uid); err != nil {
		return nil, err
	}
	return &SpaceMember{UserID: uid, Username: strings.TrimSpace(username), Nickname: nickname, Role: "member"}, nil
}

// RemoveSpaceMember owner 移人；owner 本身不可移除。
func (s *Store) RemoveSpaceMember(spaceID, requesterID, userID string) error {
	owner, err := s.isGroupOwner(spaceID, requesterID)
	if err != nil {
		return err
	}
	if !owner {
		return fmt.Errorf("%w: 只有组长可以移除成员", ErrBadRequest)
	}
	var role string
	err = s.db.QueryRow(`SELECT role FROM space_members WHERE space_id=? AND user_id=?`, spaceID, userID).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if role == "owner" {
		return fmt.Errorf("%w: 不能移除组长", ErrBadRequest)
	}
	_, err = s.db.Exec(`DELETE FROM space_members WHERE space_id=? AND user_id=?`, spaceID, userID)
	return err
}

// DeleteGroupSpace owner 删除空组（组内还有未删备忘录时拒绝）。
func (s *Store) DeleteGroupSpace(spaceID, requesterID string) error {
	owner, err := s.isGroupOwner(spaceID, requesterID)
	if err != nil {
		return err
	}
	if !owner {
		return fmt.Errorf("%w: 只有组长可以删除工作组", ErrBadRequest)
	}
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM memos WHERE space_id=? AND deleted_at IS NULL`, spaceID).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return fmt.Errorf("%w: 组内还有 %d 条备忘录，先移走或删除后再解散", ErrBadRequest, n)
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, q := range []string{
		`DELETE FROM space_members WHERE space_id=?`,
		`DELETE FROM spaces WHERE id=? AND type='group'`,
	} {
		if _, err := tx.Exec(q, spaceID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// UserByUsername 供按用户名拉人等场景。
func (s *Store) UserByUsername(username string) (*User, error) {
	var u User
	err := s.db.QueryRow(`SELECT id, username, nickname, role FROM users WHERE username=?`, username).
		Scan(&u.ID, &u.Username, &u.Nickname, &u.Role)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &u, err
}
