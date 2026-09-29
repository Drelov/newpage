package agent

import (
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"qiaolian/internal/qlp"
)

const maxEntries = 5000

func (a *Agent) list(rel string) (qlp.ListResult, error) {
	clean, err := qlp.CleanRel(rel)
	if err != nil {
		return qlp.ListResult{}, err
	}
	dir, err := a.resolve(clean)
	if err != nil {
		return qlp.ListResult{}, err
	}
	info, err := os.Lstat(dir)
	if err != nil {
		return qlp.ListResult{}, err
	}
	if !info.IsDir() {
		return qlp.ListResult{}, errors.New("不是文件夹")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return qlp.ListResult{}, err
	}
	out := make([]qlp.Entry, 0, len(entries))
	truncated := false
	for _, ent := range entries {
		if len(out) >= maxEntries {
			truncated = true
			break
		}
		info, err := ent.Info()
		if err != nil {
			continue
		}
		if info.Mode()&os.ModeSymlink != 0 {
			continue
		}
		out = append(out, qlp.Entry{
			Name:  ent.Name(),
			Dir:   ent.IsDir(),
			Size:  info.Size(),
			Mtime: info.ModTime().Unix(),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Dir != out[j].Dir {
			return out[i].Dir
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	return qlp.ListResult{Path: clean, Entries: out, Truncated: truncated}, nil
}

func (a *Agent) openRead(rel string) (*os.File, int64, error) {
	clean, err := qlp.CleanRel(rel)
	if err != nil {
		return nil, 0, err
	}
	full, err := a.resolve(clean)
	if err != nil {
		return nil, 0, err
	}
	info, err := os.Lstat(full)
	if err != nil {
		return nil, 0, err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return nil, 0, errSymlink
	}
	if info.IsDir() {
		return nil, 0, errIsDir
	}
	f, err := os.Open(full)
	if err != nil {
		return nil, 0, err
	}
	return f, info.Size(), nil
}

func (a *Agent) createPart(rel string) (clean string, part string, f *os.File, err error) {
	clean, err = qlp.CleanRel(rel)
	if err != nil {
		return "", "", nil, err
	}
	if clean == "" {
		return "", "", nil, errBadPath
	}
	full, err := a.resolve(clean)
	if err != nil {
		return "", "", nil, err
	}
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		return "", "", nil, err
	}
	part = full + ".qiaolian.part"
	f, err = os.OpenFile(part, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return "", "", nil, err
	}
	return clean, part, f, nil
}

func (a *Agent) finishPart(rel, part string, mod time.Time) (int64, error) {
	clean, err := qlp.CleanRel(rel)
	if err != nil {
		return 0, err
	}
	full, err := a.resolve(clean)
	if err != nil {
		return 0, err
	}
	if err := os.Rename(part, full); err != nil {
		return 0, err
	}
	if !mod.IsZero() {
		_ = os.Chtimes(full, mod, mod)
	}
	info, err := os.Stat(full)
	if err != nil {
		return 0, err
	}
	return info.Size(), nil
}

func (a *Agent) mkdir(rel string) error {
	clean, err := qlp.CleanRel(rel)
	if err != nil {
		return err
	}
	if clean == "" {
		return errBadPath
	}
	full, err := a.resolve(clean)
	if err != nil {
		return err
	}
	return os.MkdirAll(full, 0o755)
}

func (a *Agent) delete(rel string) error {
	clean, err := qlp.CleanRel(rel)
	if err != nil {
		return err
	}
	if clean == "" {
		return errBadPath
	}
	full, err := a.resolve(clean)
	if err != nil {
		return err
	}
	info, err := os.Lstat(full)
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return errSymlink
	}
	return os.RemoveAll(full)
}

func (a *Agent) rename(from, to string) error {
	srcRel, err := qlp.CleanRel(from)
	if err != nil {
		return err
	}
	dstRel, err := qlp.CleanRel(to)
	if err != nil {
		return err
	}
	if srcRel == "" || dstRel == "" {
		return errBadPath
	}
	src, err := a.resolve(srcRel)
	if err != nil {
		return err
	}
	dst, err := a.resolve(dstRel)
	if err != nil {
		return err
	}
	info, err := os.Lstat(src)
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return errSymlink
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	return os.Rename(src, dst)
}

func (a *Agent) resolve(clean string) (string, error) {
	root, err := filepath.Abs(a.cfg.Root)
	if err != nil {
		return "", err
	}
	full := root
	if clean != "" {
		full = filepath.Join(root, filepath.FromSlash(clean))
	}
	full, err = filepath.Abs(full)
	if err != nil {
		return "", err
	}
	if full != root && !strings.HasPrefix(full, root+string(os.PathSeparator)) {
		return "", errBadPath
	}
	return full, nil
}
