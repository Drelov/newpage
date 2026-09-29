// Package agent is the phone side of the protocol. The Android and HarmonyOS
// apps follow the same sequence; tests and the fake phone run this one.
package agent

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"os"
	"sync"
	"time"

	"qiaolian/internal/discover"
	"qiaolian/internal/qlp"
)

var (
	errBadPath = errors.New("路径无效")
	errSymlink = errors.New("不支持符号链接")
	errIsDir   = errors.New("这是文件夹")
	errUnauth  = errors.New("未配对")
)

// Hooks let a real phone inject screen, input, and clipboard behavior.
type Hooks struct {
	OnInput      func(qlp.Input) error
	ClipboardGet func() string
	ClipboardSet func(string) error
	// Capture returns one JPEG. A nil Capture means this device cannot mirror.
	Capture func(qlp.ScreenStart) (jpeg []byte, w int, h int, err error)
}

// Config is static phone identity plus the shared folder.
type Config struct {
	Name      string
	OS        string
	OSVersion string
	ID        string
	Width     int
	Height    int
	Root      string
	Code      func() string
	Hooks     Hooks
}

// Agent serves one PC at a time.
type Agent struct {
	cfg Config

	mu        sync.Mutex
	token     string
	failCount int
	lockUntil time.Time
	conn      net.Conn
}

func New(cfg Config) *Agent {
	if cfg.Code == nil {
		cfg.Code = func() string { return "" }
	}
	if cfg.ID == "" {
		cfg.ID = newID()
	}
	if cfg.Width <= 0 {
		cfg.Width = 1080
	}
	if cfg.Height <= 0 {
		cfg.Height = 2400
	}
	return &Agent{cfg: cfg}
}

func (a *Agent) ID() string { return a.cfg.ID }

func (a *Agent) Token() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.token
}

// SetToken installs a previously saved pairing token.
func (a *Agent) SetToken(token string) {
	a.mu.Lock()
	a.token = token
	a.mu.Unlock()
}

// ClearPairing forgets the saved token and drops the current PC.
func (a *Agent) ClearPairing() {
	a.mu.Lock()
	a.token = ""
	conn := a.conn
	a.conn = nil
	a.mu.Unlock()
	if conn != nil {
		conn.Close()
	}
}

// Serve accepts PC connections until the listener closes.
func (a *Agent) Serve(ln net.Listener) error {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		if tc, ok := conn.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
		}
		a.mu.Lock()
		if a.conn != nil {
			a.conn.Close()
		}
		a.conn = conn
		a.mu.Unlock()
		go a.handle(conn)
	}
}

// Announce starts UDP discovery. The returned cancel stops it.
func (a *Agent) Announce(tcpPort int) (func(), error) {
	ctx, cancel := context.WithCancel(context.Background())
	_, err := discover.ServePhone(ctx, qlp.DefaultUDPPort, func() qlp.Beacon {
		return qlp.Beacon{
			Magic: qlp.BeaconMagic,
			Name:  a.cfg.Name,
			OS:    a.cfg.OS,
			ID:    a.cfg.ID,
			Port:  tcpPort,
			Proto: qlp.ProtoVersion,
		}
	})
	if err != nil {
		cancel()
		return nil, err
	}
	return cancel, nil
}

type wire struct {
	conn net.Conn
	r    *bufio.Reader
	w    *bufio.Writer
	mu   sync.Mutex
}

func newWire(conn net.Conn) *wire {
	return &wire{conn: conn, r: bufio.NewReaderSize(conn, 64<<10), w: bufio.NewWriterSize(conn, 64<<10)}
}

func (w *wire) Send(typ byte, obj any, bin []byte) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := qlp.WriteFrame(w.w, typ, obj, bin); err != nil {
		return err
	}
	return w.w.Flush()
}

func (a *Agent) handle(conn net.Conn) {
	defer conn.Close()
	defer func() {
		a.mu.Lock()
		if a.conn == conn {
			a.conn = nil
		}
		a.mu.Unlock()
	}()

	link := newWire(conn)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var screenMu sync.Mutex
	screenCancel := func() {}
	stopScreen := func() {
		screenMu.Lock()
		screenCancel()
		screenCancel = func() {}
		screenMu.Unlock()
	}
	defer stopScreen()

	_ = link.Send(qlp.TypeHello, a.hello(true), nil)

	authed := false
	var push *pushState

	for {
		f, err := qlp.ReadFrame(link.r)
		if err != nil {
			if push != nil {
				push.abort()
			}
			return
		}
		if !authed && f.Type != qlp.TypeHello && f.Type != qlp.TypePair && f.Type != qlp.TypePing {
			_ = link.Send(qlp.TypeError, qlp.ErrorBody{Error: errUnauth.Error()}, nil)
			continue
		}
		switch f.Type {
		case qlp.TypePing:
			_ = link.Send(qlp.TypePong, nil, nil)
		case qlp.TypeHello:
			var h qlp.Hello
			_ = f.Decode(&h)
			if h.Token != "" && a.tokenMatches(h.Token) {
				authed = true
				_ = link.Send(qlp.TypePairResult, qlp.PairResult{OK: true, Token: a.tokenValue()}, nil)
			} else {
				_ = link.Send(qlp.TypePairResult, qlp.PairResult{OK: false, Error: "需要配对码"}, nil)
			}
		case qlp.TypePair:
			res := a.tryPair(f)
			if res.OK {
				authed = true
			}
			_ = link.Send(qlp.TypePairResult, res, nil)
		case qlp.TypeList:
			var req qlp.PathRequest
			_ = f.Decode(&req)
			result, err := a.list(req.Path)
			if err != nil {
				result.Path = req.Path
				result.Error = errString(err)
				result.Entries = []qlp.Entry{}
			}
			if result.Entries == nil {
				result.Entries = []qlp.Entry{}
			}
			_ = link.Send(qlp.TypeListResult, result, nil)
		case qlp.TypePull:
			var req qlp.PathRequest
			_ = f.Decode(&req)
			a.sendFile(link, req)
		case qlp.TypePushBegin:
			if push != nil {
				push.abort()
			}
			push = a.beginPush(link, f)
		case qlp.TypeChunk:
			if push == nil {
				_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{OK: false, Error: "没有正在接收的文件"}, nil)
				continue
			}
			done, err := push.write(f)
			if err != nil {
				id := push.id
				push.abort()
				push = nil
				_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{ID: id, OK: false, Error: errString(err)}, nil)
				continue
			}
			if done {
				size, err := a.finishPart(push.rel, push.part, time.Time{})
				id := push.id
				if err != nil {
					push.abort()
					push = nil
					_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{ID: id, OK: false, Error: errString(err)}, nil)
					continue
				}
				push = nil
				_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{ID: id, OK: true, Size: size}, nil)
			}
		case qlp.TypeMkdir:
			a.simpleOp(link, "mkdir", f, func(p string) error { return a.mkdir(p) })
		case qlp.TypeDelete:
			a.simpleOp(link, "delete", f, func(p string) error { return a.delete(p) })
		case qlp.TypeRename:
			var req qlp.RenameRequest
			_ = f.Decode(&req)
			err := a.rename(req.Path, req.To)
			res := qlp.OpResult{Op: "rename", OK: err == nil, Path: req.To}
			if err != nil {
				res.Error = errString(err)
			}
			_ = link.Send(qlp.TypeOpResult, res, nil)
		case qlp.TypeScreenStart:
			stopScreen()
			var cfg qlp.ScreenStart
			_ = f.Decode(&cfg)
			cfg = normalizeScreen(cfg)
			if a.cfg.Hooks.Capture == nil {
				_ = link.Send(qlp.TypeScreenStatus, qlp.ScreenStatus{OK: false, Error: "这台设备不能投屏"}, nil)
				continue
			}
			jpeg, w, h, err := a.cfg.Hooks.Capture(cfg)
			if err != nil {
				_ = link.Send(qlp.TypeScreenStatus, qlp.ScreenStatus{OK: false, Error: errString(err)}, nil)
				continue
			}
			_ = link.Send(qlp.TypeScreenStatus, qlp.ScreenStatus{OK: true, Active: true, Width: w, Height: h}, nil)
			sctx, scancel := context.WithCancel(ctx)
			screenMu.Lock()
			screenCancel = scancel
			screenMu.Unlock()
			go a.pumpScreen(sctx, link, cfg, jpeg, w, h)
		case qlp.TypeScreenStop:
			stopScreen()
			_ = link.Send(qlp.TypeScreenStatus, qlp.ScreenStatus{OK: true, Active: false}, nil)
		case qlp.TypeInput:
			var in qlp.Input
			_ = f.Decode(&in)
			in.X = qlp.Clamp01(in.X)
			in.Y = qlp.Clamp01(in.Y)
			in.X2 = qlp.Clamp01(in.X2)
			in.Y2 = qlp.Clamp01(in.Y2)
			if a.cfg.Hooks.OnInput == nil {
				_ = link.Send(qlp.TypeInputResult, qlp.InputResult{OK: false, Error: "这台设备不能远程控制"}, nil)
				continue
			}
			if in.Text != "" {
				text, err := qlp.ClampText(in.Text)
				if err != nil {
					_ = link.Send(qlp.TypeInputResult, qlp.InputResult{OK: false, Error: err.Error()}, nil)
					continue
				}
				in.Text = text
			}
			if err := a.cfg.Hooks.OnInput(in); err != nil {
				_ = link.Send(qlp.TypeInputResult, qlp.InputResult{OK: false, Error: errString(err)}, nil)
				continue
			}
			_ = link.Send(qlp.TypeInputResult, qlp.InputResult{OK: true}, nil)
		case qlp.TypeClipboardGet:
			text := ""
			if a.cfg.Hooks.ClipboardGet != nil {
				text = a.cfg.Hooks.ClipboardGet()
			}
			_ = link.Send(qlp.TypeClipboardData, qlp.Clipboard{Text: text}, nil)
		case qlp.TypeClipboardSet:
			var body qlp.Clipboard
			_ = f.Decode(&body)
			text, err := qlp.ClampClip(body.Text)
			if err != nil {
				_ = link.Send(qlp.TypeError, qlp.ErrorBody{Error: err.Error()}, nil)
				continue
			}
			if a.cfg.Hooks.ClipboardSet != nil {
				if err := a.cfg.Hooks.ClipboardSet(text); err != nil {
					_ = link.Send(qlp.TypeError, qlp.ErrorBody{Error: errString(err)}, nil)
					continue
				}
			}
			_ = link.Send(qlp.TypeClipboardData, qlp.Clipboard{Text: text}, nil)
		default:
			_ = link.Send(qlp.TypeError, qlp.ErrorBody{Error: "未知消息"}, nil)
		}
	}
}

func (a *Agent) hello(needPair bool) qlp.Hello {
	return qlp.Hello{
		Role:      "phone",
		Name:      a.cfg.Name,
		OS:        a.cfg.OS,
		OSVersion: a.cfg.OSVersion,
		Proto:     qlp.ProtoVersion,
		ID:        a.cfg.ID,
		Width:     a.cfg.Width,
		Height:    a.cfg.Height,
		NeedPair:  needPair,
	}
}

func (a *Agent) tokenMatches(token string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.token == "" || len(a.token) != len(token) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a.token), []byte(token)) == 1
}

func (a *Agent) tokenValue() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.token
}

func (a *Agent) tryPair(f qlp.Frame) qlp.PairResult {
	var req qlp.PairRequest
	_ = f.Decode(&req)
	a.mu.Lock()
	defer a.mu.Unlock()
	if time.Now().Before(a.lockUntil) {
		return qlp.PairResult{OK: false, Error: "尝试过多，请稍后再试"}
	}
	if req.Code == "" || req.Code != a.cfg.Code() {
		a.failCount++
		if a.failCount >= 5 {
			a.lockUntil = time.Now().Add(30 * time.Second)
			a.failCount = 0
		}
		return qlp.PairResult{OK: false, Error: "配对码错误"}
	}
	a.failCount = 0
	if a.token == "" {
		a.token = newToken()
	}
	return qlp.PairResult{OK: true, Token: a.token}
}

func (a *Agent) sendFile(link *wire, req qlp.PathRequest) {
	f, size, err := a.openRead(req.Path)
	if err != nil {
		_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{ID: req.ID, OK: false, Error: errString(err)}, nil)
		return
	}
	defer f.Close()
	buf := make([]byte, qlp.ChunkSize)
	seq := 0
	var sent int64
	for {
		n, rerr := f.Read(buf)
		if n > 0 {
			sent += int64(n)
			eof := errors.Is(rerr, io.EOF) || sent == size
			if err := link.Send(qlp.TypeChunk, qlp.ChunkMeta{ID: req.ID, Seq: seq, EOF: eof}, buf[:n]); err != nil {
				return
			}
			seq++
			if eof {
				break
			}
		}
		if errors.Is(rerr, io.EOF) {
			if sent == 0 {
				_ = link.Send(qlp.TypeChunk, qlp.ChunkMeta{ID: req.ID, Seq: seq, EOF: true}, nil)
			}
			break
		}
		if rerr != nil {
			_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{ID: req.ID, OK: false, Error: errString(rerr)}, nil)
			return
		}
	}
	_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{ID: req.ID, OK: true, Size: size}, nil)
}

type pushState struct {
	id   string
	rel  string
	part string
	f    *os.File
	got  int64
	seq  int
}

func (p *pushState) abort() {
	if p == nil {
		return
	}
	if p.f != nil {
		p.f.Close()
		p.f = nil
	}
	if p.part != "" {
		_ = os.Remove(p.part)
	}
}

func (p *pushState) write(frame qlp.Frame) (done bool, err error) {
	var meta qlp.ChunkMeta
	if err := frame.Decode(&meta); err != nil {
		return false, err
	}
	if meta.ID != p.id {
		return false, errors.New("文件传输编号不一致")
	}
	if len(frame.Bin) > 0 {
		if _, err := p.f.Write(frame.Bin); err != nil {
			return false, err
		}
		p.got += int64(len(frame.Bin))
	}
	p.seq++
	if meta.EOF {
		if err := p.f.Close(); err != nil {
			return false, err
		}
		p.f = nil
		return true, nil
	}
	return false, nil
}

func (a *Agent) beginPush(link *wire, frame qlp.Frame) *pushState {
	var req qlp.PushBegin
	if err := frame.Decode(&req); err != nil {
		_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{OK: false, Error: err.Error()}, nil)
		return nil
	}
	clean, part, f, err := a.createPart(req.Path)
	if err != nil {
		_ = link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{ID: req.ID, OK: false, Error: errString(err)}, nil)
		return nil
	}
	if err := link.Send(qlp.TypeTransferStatus, qlp.TransferStatus{ID: req.ID, OK: true, Ready: true}, nil); err != nil {
		f.Close()
		_ = os.Remove(part)
		return nil
	}
	return &pushState{id: req.ID, rel: clean, part: part, f: f}
}

func (a *Agent) simpleOp(link *wire, op string, frame qlp.Frame, fn func(string) error) {
	var req qlp.PathRequest
	_ = frame.Decode(&req)
	err := fn(req.Path)
	res := qlp.OpResult{Op: op, OK: err == nil, Path: req.Path}
	if err != nil {
		res.Error = errString(err)
	}
	_ = link.Send(qlp.TypeOpResult, res, nil)
}

func (a *Agent) pumpScreen(ctx context.Context, link *wire, cfg qlp.ScreenStart, first []byte, w, h int) {
	send := func(jpeg []byte, w, h int) bool {
		err := link.Send(qlp.TypeFrame, qlp.FrameMeta{W: w, H: h, TS: time.Now().UnixMilli()}, jpeg)
		return err == nil
	}
	if !send(first, w, h) {
		return
	}
	interval := time.Second / time.Duration(cfg.FPS)
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(interval):
		}
		jpeg, w, h, err := a.cfg.Hooks.Capture(cfg)
		if err != nil {
			_ = link.Send(qlp.TypeScreenStatus, qlp.ScreenStatus{OK: false, Active: false, Error: errString(err)}, nil)
			return
		}
		if !send(jpeg, w, h) {
			return
		}
	}
}

func normalizeScreen(cfg qlp.ScreenStart) qlp.ScreenStart {
	if cfg.MaxWidth <= 0 {
		cfg.MaxWidth = 1280
	}
	if cfg.MaxWidth > 1920 {
		cfg.MaxWidth = 1920
	}
	if cfg.Quality <= 0 {
		cfg.Quality = 70
	}
	if cfg.Quality > 95 {
		cfg.Quality = 95
	}
	if cfg.FPS <= 0 {
		cfg.FPS = 8
	}
	if cfg.FPS > 20 {
		cfg.FPS = 20
	}
	return cfg
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	if os.IsNotExist(err) {
		return "文件不存在"
	}
	if os.IsPermission(err) {
		return "没有权限"
	}
	return err.Error()
}

func newToken() string {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format(time.RFC3339Nano)))
	}
	return hex.EncodeToString(b[:])
}

func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}
