// Package pc is the desktop companion: discovery, pairing, files, mirror, and clipboard.
package pc

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"qiaolian/internal/discover"
	"qiaolian/internal/qlp"
	"qiaolian/internal/session"
)

//go:embed web/*
var webFS embed.FS

// App is the localhost API and the phone session.
type App struct {
	name string
	csrf string
	seen *discover.SeenCache

	mu      sync.Mutex
	client  *session.Client
	addr    string
	screen  bool
	store   *tokenStore
	discOn  bool
	discEnd func()
}

func New(name string) *App {
	if name == "" {
		name, _ = os.Hostname()
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = os.TempDir()
	}
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	return &App{
		name:  name,
		csrf:  hex.EncodeToString(buf),
		seen:  discover.NewSeen(),
		store: loadTokens(filepath.Join(dir, "qiaolian", "paired.json")),
	}
}

// UseTokenFile keeps pairing tokens in a chosen file. Tests use a temp file.
func (a *App) UseTokenFile(path string) {
	a.store = loadTokens(path)
}

// CSRF is the per-launch page token required by the API.
func (a *App) CSRF() string { return a.csrf }

// StartDiscovery listens for phone beacons until the process exits.
func (a *App) StartDiscovery() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.discOn {
		return nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	_, err := discover.Listen(ctx, qlp.DefaultUDPPort, func(b qlp.Beacon, ip string) {
		if ip == "" {
			return
		}
		a.seen.Upsert(b, ip)
	})
	if err != nil {
		cancel()
		return err
	}
	a.discOn = true
	a.discEnd = cancel
	return nil
}

func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.handleIndex)
	mux.HandleFunc("/assets/", a.handleAsset)
	mux.HandleFunc("/api/state", a.handleState)
	mux.HandleFunc("/api/connect", a.handleConnect)
	mux.HandleFunc("/api/disconnect", a.handleDisconnect)
	mux.HandleFunc("/api/files", a.handleFiles)
	mux.HandleFunc("/api/mkdir", a.handleMkdir)
	mux.HandleFunc("/api/delete", a.handleDelete)
	mux.HandleFunc("/api/rename", a.handleRename)
	mux.HandleFunc("/api/upload", a.handleUpload)
	mux.HandleFunc("/api/download", a.handleDownload)
	mux.HandleFunc("/api/screen/start", a.handleScreenStart)
	mux.HandleFunc("/api/screen/stop", a.handleScreenStop)
	mux.HandleFunc("/api/screen/mjpeg", a.handleMJPEG)
	mux.HandleFunc("/api/input", a.handleInput)
	mux.HandleFunc("/api/clipboard", a.handleClipboard)
	return mux
}

func (a *App) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	raw, err := webFS.ReadFile("web/index.html")
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	raw = bytes.ReplaceAll(raw, []byte("__CSRF__"), []byte(a.csrf))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(raw)
}

func (a *App) handleAsset(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/assets/")
	if name == "" || strings.Contains(name, "..") {
		http.NotFound(w, r)
		return
	}
	raw, err := webFS.ReadFile("web/" + name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	switch path.Ext(name) {
	case ".css":
		w.Header().Set("Content-Type", "text/css; charset=utf-8")
	case ".js":
		w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
	default:
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	_, _ = w.Write(raw)
}

func (a *App) authorized(r *http.Request) bool {
	got := r.Header.Get("X-Qiaolian-Token")
	if got == "" {
		got = r.URL.Query().Get("t")
	}
	if len(got) != len(a.csrf) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(a.csrf)) == 1
}

func (a *App) handleState(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(r) {
		writeErr(w, http.StatusUnauthorized, errors.New("页面已失效，请刷新"))
		return
	}
	devices := a.seen.List(time.Now(), 8*time.Second)
	sort.Slice(devices, func(i, j int) bool {
		if devices[i].Name == devices[j].Name {
			return devices[i].IP < devices[j].IP
		}
		return devices[i].Name < devices[j].Name
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"pcName":  a.name,
		"devices": devices,
		"session": a.sessionView(),
	})
}

type connectReq struct {
	IP   string `json:"ip"`
	Port int    `json:"port"`
	Code string `json:"code"`
}

func (a *App) handleConnect(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || !a.authorized(r) {
		writeErr(w, http.StatusUnauthorized, errors.New("页面已失效，请刷新"))
		return
	}
	var req connectReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if err := a.Connect(req.IP, req.Port, strings.TrimSpace(req.Code)); err != nil {
		code := http.StatusBadRequest
		if strings.Contains(err.Error(), "配对") {
			code = http.StatusUnauthorized
		}
		writeErr(w, code, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"session": a.sessionView()})
}

func (a *App) handleDisconnect(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || !a.authorized(r) {
		writeErr(w, http.StatusUnauthorized, errors.New("页面已失效，请刷新"))
		return
	}
	a.Disconnect()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// Connect dials a phone, reuses a saved token, or pairs with a code.
func (a *App) Connect(ip string, port int, code string) error {
	ip = strings.TrimSpace(ip)
	if ip == "" || strings.ContainsAny(ip, " /\t") {
		return errors.New("请填写手机的 IP")
	}
	if port <= 0 {
		port = qlp.DefaultTCPPort
	}
	if port > 65535 {
		return errors.New("端口无效")
	}
	addr := net.JoinHostPort(ip, fmt.Sprintf("%d", port))
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	client, err := session.Dial(ctx, addr)
	if err != nil {
		return errors.New("连不上手机：" + err.Error())
	}
	hello := client.Hello()
	if hello.ID == "" {
		client.Close()
		return errors.New("手机没有设备编号")
	}
	token := a.store.Get(hello.ID)
	if token != "" {
		if err := client.Auth(token); err == nil {
			a.swap(client, addr)
			return nil
		}
	}
	if code == "" {
		client.Close()
		return errors.New("请输入手机上的配对码")
	}
	tok, err := client.Pair(code)
	if err != nil {
		client.Close()
		return err
	}
	a.store.Put(hello.ID, tok)
	a.swap(client, addr)
	return nil
}

func (a *App) swap(client *session.Client, addr string) {
	a.mu.Lock()
	old := a.client
	a.client = client
	a.addr = addr
	a.screen = false
	a.mu.Unlock()
	if old != nil {
		old.Close()
	}
}

// Disconnect closes the phone connection.
func (a *App) Disconnect() {
	a.mu.Lock()
	old := a.client
	a.client = nil
	a.addr = ""
	a.screen = false
	a.mu.Unlock()
	if old != nil {
		old.Close()
	}
}

func (a *App) current() *session.Client {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.client
}

// Client is the active phone connection.
func (a *App) Client() *session.Client { return a.current() }

// Devices returns phones seen on the LAN in the last few seconds.
func (a *App) Devices() []discover.Device {
	devices := a.seen.List(time.Now(), 8*time.Second)
	sort.Slice(devices, func(i, j int) bool {
		if devices[i].Name == devices[j].Name {
			return devices[i].IP < devices[j].IP
		}
		return devices[i].Name < devices[j].Name
	})
	return devices
}

func (a *App) sessionView() any {
	a.mu.Lock()
	client := a.client
	addr := a.addr
	screen := a.screen
	a.mu.Unlock()
	if client == nil {
		return nil
	}
	select {
	case <-client.Done():
		a.Disconnect()
		return nil
	default:
	}
	h := client.Hello()
	return map[string]any{
		"id":          h.ID,
		"name":        h.Name,
		"os":          h.OS,
		"osVersion":   h.OSVersion,
		"addr":        addr,
		"w":           h.Width,
		"h":           h.Height,
		"screen":      screen,
		"screenError": client.ScreenError(),
	}
}

func (a *App) handleFiles(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	res, err := client.List(r.URL.Query().Get("path"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (a *App) handleMkdir(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	var req struct {
		Path string `json:"path"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if err := client.Mkdir(req.Path); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *App) handleDelete(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	var req struct {
		Path string `json:"path"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if err := client.Delete(req.Path); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *App) handleRename(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	var req struct {
		Path string `json:"path"`
		To   string `json:"to"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if err := client.Rename(req.Path, req.To); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *App) handleUpload(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeErr(w, http.StatusBadRequest, errors.New("上传内容无效"))
		return
	}
	file, hdr, err := r.FormFile("file")
	if err != nil {
		writeErr(w, http.StatusBadRequest, errors.New("请选择文件"))
		return
	}
	defer file.Close()
	rel, err := joinName(r.URL.Query().Get("path"), hdr.Filename)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if err := client.Push(rel, file, hdr.Size); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "path": rel})
}

func (a *App) handleDownload(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	rel := r.URL.Query().Get("path")
	clean, err := qlp.CleanRel(rel)
	if err != nil || clean == "" {
		writeErr(w, http.StatusBadRequest, errors.New("路径无效"))
		return
	}
	name := path.Base(clean)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", contentDisposition(name))
	if err := client.Pull(clean, w); err != nil {
		return
	}
}

func (a *App) handleScreenStart(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	var cfg qlp.ScreenStart
	if err := readJSON(r, &cfg); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	st, err := client.StartScreen(cfg)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	a.mu.Lock()
	a.screen = true
	a.mu.Unlock()
	writeJSON(w, http.StatusOK, st)
}

func (a *App) handleScreenStop(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	if err := client.StopScreen(); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	a.mu.Lock()
	a.screen = false
	a.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *App) handleMJPEG(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	a.mu.Lock()
	on := a.screen
	a.mu.Unlock()
	if !on {
		writeErr(w, http.StatusConflict, errors.New("投屏还没开始"))
		return
	}
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=qlframe")
	w.Header().Set("Cache-Control", "no-store")
	flusher, _ := w.(http.Flusher)
	id, ch := client.Subscribe()
	defer client.Unsubscribe(id)
	var seq uint64
	for {
		view := client.Frame()
		if view.Seq > seq && len(view.JPEG) > 0 {
			seq = view.Seq
			if _, err := fmt.Fprintf(w, "--qlframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", len(view.JPEG)); err != nil {
				return
			}
			if _, err := w.Write(view.JPEG); err != nil {
				return
			}
			if _, err := w.Write([]byte("\r\n")); err != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		select {
		case <-r.Context().Done():
			return
		case <-client.Done():
			return
		case <-ch:
		case <-time.After(2 * time.Second):
		}
	}
}

func (a *App) handleInput(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	var in qlp.Input
	if err := readJSON(r, &in); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if err := client.Input(in); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *App) handleClipboard(w http.ResponseWriter, r *http.Request) {
	client, ok := a.need(w, r)
	if !ok {
		return
	}
	switch r.Method {
	case http.MethodGet:
		text, err := client.Clipboard()
		if err != nil {
			writeErr(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"text": text})
	case http.MethodPost:
		var body qlp.Clipboard
		if err := readJSON(r, &body); err != nil {
			writeErr(w, http.StatusBadRequest, err)
			return
		}
		if err := client.SetClipboard(body.Text); err != nil {
			writeErr(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	default:
		writeErr(w, http.StatusMethodNotAllowed, errors.New("方法不支持"))
	}
}

func (a *App) need(w http.ResponseWriter, r *http.Request) (*session.Client, bool) {
	if !a.authorized(r) {
		writeErr(w, http.StatusUnauthorized, errors.New("页面已失效，请刷新"))
		return nil, false
	}
	if r.Method == http.MethodPost && r.URL.Path != "/api/upload" {
		// uploads use multipart; other posts were already checked by callers when needed
	}
	client := a.current()
	if client == nil {
		writeErr(w, http.StatusConflict, errors.New("还没有连接手机"))
		return nil, false
	}
	select {
	case <-client.Done():
		a.Disconnect()
		writeErr(w, http.StatusConflict, errors.New("手机已断开"))
		return nil, false
	default:
	}
	return client, true
}

func joinName(dir, name string) (string, error) {
	name = strings.ReplaceAll(name, "\\", "/")
	name = path.Base(name)
	if name == "." || name == "/" || name == "" {
		return "", errors.New("文件名无效")
	}
	return qlp.CleanRel(strings.Trim(dir, "/") + "/" + name)
}

func contentDisposition(name string) string {
	return "attachment; filename*=UTF-8''" + url.PathEscape(name)
}

func readJSON(r *http.Request, dst any) error {
	defer r.Body.Close()
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := dec.Decode(dst); err != nil {
		return errors.New("请求内容无效")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

func writeErr(w http.ResponseWriter, code int, err error) {
	writeJSON(w, code, map[string]string{"error": err.Error()})
}
