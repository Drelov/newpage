package qlp

import (
	"errors"
	"strings"
	"unicode/utf8"
)

// Hello is the first message from the phone, and the PC's token presentation.
type Hello struct {
	Role      string `json:"role"`
	Name      string `json:"name,omitempty"`
	OS        string `json:"os,omitempty"`
	OSVersion string `json:"osVersion,omitempty"`
	Proto     int    `json:"proto,omitempty"`
	ID        string `json:"id,omitempty"`
	Width     int    `json:"w,omitempty"`
	Height    int    `json:"h,omitempty"`
	NeedPair  bool   `json:"needPair,omitempty"`
	Token     string `json:"token,omitempty"`
}

type PairRequest struct {
	Code string `json:"code"`
}

type PairResult struct {
	OK    bool   `json:"ok"`
	Token string `json:"token,omitempty"`
	Error string `json:"error,omitempty"`
}

type PathRequest struct {
	Path string `json:"path"`
	ID   string `json:"id,omitempty"`
}

type Entry struct {
	Name  string `json:"name"`
	Dir   bool   `json:"dir"`
	Size  int64  `json:"size"`
	Mtime int64  `json:"mtime"`
}

type ListResult struct {
	Path      string  `json:"path"`
	Entries   []Entry `json:"entries"`
	Truncated bool    `json:"truncated,omitempty"`
	Error     string  `json:"error,omitempty"`
}

type PushBegin struct {
	Path string `json:"path"`
	ID   string `json:"id"`
	Size int64  `json:"size"`
}

type ChunkMeta struct {
	ID  string `json:"id"`
	Seq int    `json:"seq"`
	EOF bool   `json:"eof"`
}

type TransferStatus struct {
	ID    string `json:"id"`
	OK    bool   `json:"ok"`
	Ready bool   `json:"ready,omitempty"`
	Size  int64  `json:"size,omitempty"`
	Error string `json:"error,omitempty"`
}

type RenameRequest struct {
	Path string `json:"path"`
	To   string `json:"to"`
}

type OpResult struct {
	Op    string `json:"op"`
	OK    bool   `json:"ok"`
	Path  string `json:"path,omitempty"`
	Error string `json:"error,omitempty"`
}

type ScreenStart struct {
	MaxWidth int `json:"maxWidth"`
	Quality  int `json:"quality"`
	FPS      int `json:"fps"`
}

type ScreenStatus struct {
	OK     bool   `json:"ok"`
	Active bool   `json:"active"`
	Width  int    `json:"width,omitempty"`
	Height int    `json:"height,omitempty"`
	Error  string `json:"error,omitempty"`
}

type FrameMeta struct {
	W  int   `json:"w"`
	H  int   `json:"h"`
	TS int64 `json:"ts"`
}

// Input uses normalized coordinates in the range 0..1.
type Input struct {
	Action   string  `json:"action"`
	X        float64 `json:"x,omitempty"`
	Y        float64 `json:"y,omitempty"`
	X2       float64 `json:"x2,omitempty"`
	Y2       float64 `json:"y2,omitempty"`
	DY       float64 `json:"dy,omitempty"`
	Duration int     `json:"duration,omitempty"`
	Key      string  `json:"key,omitempty"`
	Text     string  `json:"text,omitempty"`
}

type InputResult struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

type Clipboard struct {
	Text string `json:"text"`
}

type ErrorBody struct {
	Error string `json:"error"`
}

// Beacon is the UDP discovery payload.
type Beacon struct {
	Magic string `json:"magic"`
	Op    string `json:"op,omitempty"`
	Name  string `json:"name,omitempty"`
	OS    string `json:"os,omitempty"`
	ID    string `json:"id,omitempty"`
	Port  int    `json:"port,omitempty"`
	Proto int    `json:"proto,omitempty"`
}

const (
	maxPathLen = 1024
	maxTextLen = 4000
	maxClipLen = 1 << 20
)

// CleanRel normalizes a phone-relative path and rejects traversal.
func CleanRel(p string) (string, error) {
	p = strings.TrimSpace(p)
	p = strings.ReplaceAll(p, "\\", "/")
	if strings.ContainsRune(p, 0) || len(p) > maxPathLen {
		return "", errBadPath
	}
	parts := strings.Split(p, "/")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if part == "" || part == "." {
			continue
		}
		if part == ".." || !utf8.ValidString(part) {
			return "", errBadPath
		}
		out = append(out, part)
	}
	return strings.Join(out, "/"), nil
}

var errBadPath = errors.New("路径无效")

type pathError string

func (e pathError) Error() string { return string(e) }

// Clamp01 limits a normalized coordinate.
func Clamp01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

// ClampText trims remote text to the protocol limit.
func ClampText(s string) (string, error) {
	if len(s) > maxTextLen {
		return "", pathError("文字过长")
	}
	if !utf8.ValidString(s) {
		return "", pathError("文字无效")
	}
	return s, nil
}

// ClampClip trims clipboard text to the protocol limit.
func ClampClip(s string) (string, error) {
	if len(s) > maxClipLen {
		return "", pathError("剪贴板内容过长")
	}
	if !utf8.ValidString(s) {
		return "", pathError("剪贴板内容无效")
	}
	return s, nil
}
