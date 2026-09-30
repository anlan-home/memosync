package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// 备份恢复采用「两步走」，避免对正在运行的 SQLite 做热替换：
//  1) PrepareRestore(name)：校验备份存在，把备份库复制为 memo.db.restore-pending，
//     并写入 restore-pending.json（记录备份名），同时把备份里的附件 blob 拷回附件目录
//  2) 服务重启时 RestoreIfPending(dataDir)：用 pending 库替换 memo.db（清掉旧 WAL/SHM）
//
// 因此管理页执行恢复后需要重启服务生效（fnOS 重启应用 / docker restart）。

const pendingRestoreDB = "memo.db.restore-pending"
const pendingRestoreMeta = "restore-pending.json"

// PrepareRestore 校验并准备恢复，返回是否就绪等待重启。
func (s *Store) PrepareRestore(name string) error {
	if !strings.HasPrefix(name, "backup-") || !strings.HasSuffix(name, ".db") || strings.Contains(name, "/") || strings.Contains(name, "\\") || strings.Contains(name, "..") {
		return fmt.Errorf("%w: 备份名非法", ErrBadRequest)
	}
	backupDB := filepath.Join(s.dataDir, "backup", name)
	if _, err := os.Stat(backupDB); err != nil {
		return fmt.Errorf("%w: 备份不存在", ErrBadRequest)
	}
	// 1) 复制备份库 → pending
	dst := filepath.Join(s.dataDir, pendingRestoreDB)
	if err := copyFile(backupDB, dst); err != nil {
		return fmt.Errorf("复制备份库失败: %w", err)
	}
	// 2) 记录元信息
	meta := map[string]string{"name": name}
	raw, _ := json.Marshal(meta)
	if err := os.WriteFile(filepath.Join(s.dataDir, pendingRestoreMeta), raw, 0o600); err != nil {
		return fmt.Errorf("写入恢复标记失败: %w", err)
	}
	// 3) 备份里的附件 blob 立即拷回（幂等：只补缺失的）
	attBackup := filepath.Join(s.dataDir, "backup", strings.TrimSuffix(name, ".db")+".attachments")
	restoreBlobs(attBackup, filepath.Join(s.dataDir, "attachments"))
	return nil
}

func restoreBlobs(srcRoot, dstRoot string) {
	filepath.WalkDir(srcRoot, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil //nolint
		}
		rel, err := filepath.Rel(srcRoot, path)
		if err != nil {
			return nil //nolint
		}
		dst := filepath.Join(dstRoot, rel)
		if _, err := os.Stat(dst); err == nil {
			return nil // 已存在，跳过
		}
		os.MkdirAll(filepath.Dir(dst), 0o755)
		copyFile(path, dst)
		return nil
	})
}

// RestoreIfPending 在服务启动、打开数据库之前调用。
// 返回是否执行了恢复（用于日志）。
func RestoreIfPending(dataDir string) (bool, error) {
	metaPath := filepath.Join(dataDir, pendingRestoreMeta)
	raw, err := os.ReadFile(metaPath)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	var meta struct {
		Name string `json:"name"`
	}
	if err := json.Unmarshal(raw, &meta); err != nil {
		return false, fmt.Errorf("解析恢复标记失败: %w", err)
	}
	pending := filepath.Join(dataDir, pendingRestoreDB)
	if _, err := os.Stat(pending); err != nil {
		// 没有等待恢复的库文件，清理标记
		os.Remove(metaPath)
		return false, nil
	}
	// 关闭语义下的替换：删除主库与 WAL/SHM，用 pending 顶替
	dbPath := filepath.Join(dataDir, "memo.db")
	for _, suffix := range []string{"-wal", "-shm"} {
		os.Remove(dbPath + suffix)
	}
	if err := os.Remove(dbPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return false, fmt.Errorf("移除旧库失败: %w", err)
	}
	if err := os.Rename(pending, dbPath); err != nil {
		return false, fmt.Errorf("恢复库文件失败: %w", err)
	}
	os.Remove(metaPath)
	return true, nil
}
