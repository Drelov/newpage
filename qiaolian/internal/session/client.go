// Package session is the PC's connection to one phone.
package session

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"qiaolian/internal/qlp"
)

// Client talks to a phone agent.
type Client struct {
	conn net.Conn
	r    *bufio.Reader
	w    *bufio.Writer
	wmu  sync.Mutex

	hello   qlp.Hello
	helloCh chan struct{}

	dead chan struct{}
	once sync.Once

	rpcMu    sync.Mutex
	waitType byte
	waitCh   chan qlp.Frame

	transferMu sync.Mutex
	transferCh chan qlp.Frame

	frameMu   sync.Mutex
	frameJPEG []byte
	frameW    int
	frameH    int
	frameTS   int64
	frameSeq  uint64
	subs      map[int]chan struct{}
	nextSub   int
	screenErr string
}

// Frame is the newest mirrored JPEG.
type Frame struct {
	JPEG []byte
	W    int
	H    int
	TS   int64
	Seq  uint64
}

// Dial connects and waits for the phone hello.
func Dial(ctx context.Context, addr string) (*Client, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	c := &Client{
		conn:    conn,
		r:       bufio.NewReaderSize(conn, 64<<10),
		w:       bufio.NewWriterSize(conn, 64<<10),
		helloCh: make(chan struct{}),
		dead:    make(chan struct{}),
		subs:    map[int]chan struct{}{},
	}
	go c.readLoop()
	select {
	case <-c.helloCh:
		if c.hello.Proto != 0 && c.hello.Proto != qlp.ProtoVersion {
			c.Close()
			return nil, errors.New("手机协议版本不匹配")
		}
		if c.hello.Role != "" && c.hello.Role != "phone" {
			c.Close()
			return nil, errors.New("对端不是手机")
		}
		return c, nil
	case <-c.dead:
		return nil, errors.New("手机已断开")
	case <-ctx.Done():
		c.Close()
		return nil, ctx.Err()
	}
}

func (c *Client) Hello() qlp.Hello { return c.hello }

func (c *Client) Done() <-chan struct{} { return c.dead }

func (c *Client) Close() { c.shutdown() }

func (c *Client) fail() { c.shutdown() }

func (c *Client) shutdown() {
	c.once.Do(func() {
		close(c.dead)
		_ = c.conn.Close()
	})
}

func (c *Client) readLoop() {
	defer c.fail()
	seenHello := false
	for {
		f, err := qlp.ReadFrame(c.r)
		if err != nil {
			return
		}
		if c.deliverTransfer(f) {
			continue
		}
		if f.Type == qlp.TypeFrame {
			c.publish(f)
			continue
		}
		if f.Type == qlp.TypeHello && !seenHello {
			_ = f.Decode(&c.hello)
			seenHello = true
			close(c.helloCh)
			continue
		}
		if c.deliverRPC(f) {
			continue
		}
		if f.Type == qlp.TypeScreenStatus {
			var st qlp.ScreenStatus
			_ = f.Decode(&st)
			c.frameMu.Lock()
			if st.Error != "" {
				c.screenErr = st.Error
			}
			if !st.Active {
				c.screenErr = st.Error
			}
			c.frameMu.Unlock()
		}
	}
}

func (c *Client) deliverTransfer(f qlp.Frame) bool {
	if f.Type != qlp.TypeChunk && f.Type != qlp.TypeTransferStatus && f.Type != qlp.TypeError {
		return false
	}
	c.transferMu.Lock()
	ch := c.transferCh
	c.transferMu.Unlock()
	if ch == nil {
		return false
	}
	select {
	case ch <- f:
	case <-c.dead:
	}
	return true
}

func (c *Client) deliverRPC(f qlp.Frame) bool {
	c.rpcMu.Lock()
	ch := c.waitCh
	typ := c.waitType
	c.rpcMu.Unlock()
	if ch == nil || (f.Type != typ && f.Type != qlp.TypeError) {
		return false
	}
	select {
	case ch <- f:
	default:
	}
	return true
}

func (c *Client) publish(f qlp.Frame) {
	var meta qlp.FrameMeta
	_ = f.Decode(&meta)
	c.frameMu.Lock()
	c.frameJPEG = append([]byte(nil), f.Bin...)
	c.frameW = meta.W
	c.frameH = meta.H
	c.frameTS = meta.TS
	c.frameSeq++
	for _, ch := range c.subs {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
	c.frameMu.Unlock()
}

// Subscribe wakes when a new frame arrives.
func (c *Client) Subscribe() (int, <-chan struct{}) {
	ch := make(chan struct{}, 1)
	c.frameMu.Lock()
	c.nextSub++
	id := c.nextSub
	c.subs[id] = ch
	if c.frameSeq > 0 {
		ch <- struct{}{}
	}
	c.frameMu.Unlock()
	return id, ch
}

func (c *Client) Unsubscribe(id int) {
	c.frameMu.Lock()
	delete(c.subs, id)
	c.frameMu.Unlock()
}

func (c *Client) Frame() Frame {
	c.frameMu.Lock()
	defer c.frameMu.Unlock()
	return Frame{
		JPEG: append([]byte(nil), c.frameJPEG...),
		W:    c.frameW,
		H:    c.frameH,
		TS:   c.frameTS,
		Seq:  c.frameSeq,
	}
}

func (c *Client) ScreenError() string {
	c.frameMu.Lock()
	defer c.frameMu.Unlock()
	return c.screenErr
}

func (c *Client) send(typ byte, obj any, bin []byte) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	if err := qlp.WriteFrame(c.w, typ, obj, bin); err != nil {
		return err
	}
	return c.w.Flush()
}

func (c *Client) roundTrip(typ byte, obj any, bin []byte, resp byte, timeout time.Duration) (qlp.Frame, error) {
	c.rpcMu.Lock()
	if c.waitCh != nil {
		c.rpcMu.Unlock()
		return qlp.Frame{}, errors.New("正忙")
	}
	ch := make(chan qlp.Frame, 1)
	c.waitCh = ch
	c.waitType = resp
	c.rpcMu.Unlock()
	defer func() {
		c.rpcMu.Lock()
		if c.waitCh == ch {
			c.waitCh = nil
		}
		c.rpcMu.Unlock()
	}()
	if err := c.send(typ, obj, bin); err != nil {
		return qlp.Frame{}, err
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case f := <-ch:
		if f.Type == qlp.TypeError {
			var body qlp.ErrorBody
			_ = f.Decode(&body)
			if body.Error == "" {
				body.Error = "手机返回错误"
			}
			return qlp.Frame{}, errors.New(body.Error)
		}
		return f, nil
	case <-timer.C:
		return qlp.Frame{}, errors.New("手机响应超时")
	case <-c.dead:
		return qlp.Frame{}, errors.New("连接已断开")
	}
}

func (c *Client) beginTransfer() (chan qlp.Frame, func()) {
	ch := make(chan qlp.Frame, 8)
	c.transferMu.Lock()
	c.transferCh = ch
	c.transferMu.Unlock()
	return ch, func() {
		c.transferMu.Lock()
		if c.transferCh == ch {
			c.transferCh = nil
		}
		c.transferMu.Unlock()
	}
}

func (c *Client) Auth(token string) error {
	f, err := c.roundTrip(qlp.TypeHello, qlp.Hello{Role: "pc", Proto: qlp.ProtoVersion, Token: token}, nil, qlp.TypePairResult, 8*time.Second)
	if err != nil {
		return err
	}
	var res qlp.PairResult
	if err := f.Decode(&res); err != nil {
		return err
	}
	if !res.OK {
		if res.Error == "" {
			res.Error = "需要配对码"
		}
		return errors.New(res.Error)
	}
	return nil
}

func (c *Client) Pair(code string) (string, error) {
	f, err := c.roundTrip(qlp.TypePair, qlp.PairRequest{Code: code}, nil, qlp.TypePairResult, 8*time.Second)
	if err != nil {
		return "", err
	}
	var res qlp.PairResult
	if err := f.Decode(&res); err != nil {
		return "", err
	}
	if !res.OK {
		if res.Error == "" {
			res.Error = "配对失败"
		}
		return "", errors.New(res.Error)
	}
	return res.Token, nil
}

func (c *Client) List(path string) (qlp.ListResult, error) {
	f, err := c.roundTrip(qlp.TypeList, qlp.PathRequest{Path: path}, nil, qlp.TypeListResult, 20*time.Second)
	if err != nil {
		return qlp.ListResult{}, err
	}
	var res qlp.ListResult
	if err := f.Decode(&res); err != nil {
		return qlp.ListResult{}, err
	}
	if res.Error != "" {
		return res, errors.New(res.Error)
	}
	if res.Entries == nil {
		res.Entries = []qlp.Entry{}
	}
	return res, nil
}

func (c *Client) Pull(path string, w io.Writer) error {
	ch, done := c.beginTransfer()
	defer done()
	id := newID()
	if err := c.send(qlp.TypePull, qlp.PathRequest{Path: path, ID: id}, nil); err != nil {
		return err
	}
	timer := time.NewTimer(60 * time.Second)
	defer timer.Stop()
	for {
		select {
		case f := <-ch:
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			timer.Reset(60 * time.Second)
			switch f.Type {
			case qlp.TypeChunk:
				if len(f.Bin) > 0 {
					if _, err := w.Write(f.Bin); err != nil {
						return err
					}
				}
			case qlp.TypeTransferStatus:
				var st qlp.TransferStatus
				_ = f.Decode(&st)
				if !st.OK {
					if st.Error == "" {
						st.Error = "下载失败"
					}
					return errors.New(st.Error)
				}
				return nil
			case qlp.TypeError:
				var body qlp.ErrorBody
				_ = f.Decode(&body)
				if body.Error == "" {
					body.Error = "下载失败"
				}
				return errors.New(body.Error)
			}
		case <-timer.C:
			return errors.New("下载超时")
		case <-c.dead:
			return errors.New("连接已断开")
		}
	}
}

func (c *Client) Push(path string, r io.Reader, size int64) error {
	ch, done := c.beginTransfer()
	defer done()
	id := newID()
	if err := c.send(qlp.TypePushBegin, qlp.PushBegin{Path: path, ID: id, Size: size}, nil); err != nil {
		return err
	}
	ready, err := c.readStatus(ch, 20*time.Second)
	if err != nil {
		return err
	}
	if !ready.OK || !ready.Ready {
		if ready.Error == "" {
			ready.Error = "手机拒绝接收"
		}
		return errors.New(ready.Error)
	}
	buf := make([]byte, qlp.ChunkSize)
	seq := 0
	for {
		n, rerr := r.Read(buf)
		if n > 0 {
			eof := errors.Is(rerr, io.EOF)
			if err := c.send(qlp.TypeChunk, qlp.ChunkMeta{ID: id, Seq: seq, EOF: eof}, buf[:n]); err != nil {
				return err
			}
			seq++
			if eof {
				break
			}
		}
		if errors.Is(rerr, io.EOF) {
			if err := c.send(qlp.TypeChunk, qlp.ChunkMeta{ID: id, Seq: seq, EOF: true}, nil); err != nil {
				return err
			}
			break
		}
		if rerr != nil {
			return rerr
		}
	}
	final, err := c.readStatus(ch, 60*time.Second)
	if err != nil {
		return err
	}
	if !final.OK {
		if final.Error == "" {
			final.Error = "上传失败"
		}
		return errors.New(final.Error)
	}
	return nil
}

func (c *Client) readStatus(ch chan qlp.Frame, timeout time.Duration) (qlp.TransferStatus, error) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case f := <-ch:
		if f.Type == qlp.TypeError {
			var body qlp.ErrorBody
			_ = f.Decode(&body)
			return qlp.TransferStatus{}, errors.New(body.Error)
		}
		var st qlp.TransferStatus
		if err := f.Decode(&st); err != nil {
			return qlp.TransferStatus{}, err
		}
		return st, nil
	case <-timer.C:
		return qlp.TransferStatus{}, errors.New("传输超时")
	case <-c.dead:
		return qlp.TransferStatus{}, errors.New("连接已断开")
	}
}

func (c *Client) Mkdir(path string) error {
	return c.op(qlp.TypeMkdir, qlp.PathRequest{Path: path})
}

func (c *Client) Delete(path string) error {
	return c.op(qlp.TypeDelete, qlp.PathRequest{Path: path})
}

func (c *Client) Rename(from, to string) error {
	f, err := c.roundTrip(qlp.TypeRename, qlp.RenameRequest{Path: from, To: to}, nil, qlp.TypeOpResult, 20*time.Second)
	if err != nil {
		return err
	}
	var res qlp.OpResult
	if err := f.Decode(&res); err != nil {
		return err
	}
	if !res.OK {
		if res.Error == "" {
			res.Error = "操作失败"
		}
		return errors.New(res.Error)
	}
	return nil
}

func (c *Client) op(typ byte, req qlp.PathRequest) error {
	f, err := c.roundTrip(typ, req, nil, qlp.TypeOpResult, 20*time.Second)
	if err != nil {
		return err
	}
	var res qlp.OpResult
	if err := f.Decode(&res); err != nil {
		return err
	}
	if !res.OK {
		if res.Error == "" {
			res.Error = "操作失败"
		}
		return errors.New(res.Error)
	}
	return nil
}

func (c *Client) StartScreen(cfg qlp.ScreenStart) (qlp.ScreenStatus, error) {
	c.frameMu.Lock()
	c.screenErr = ""
	c.frameMu.Unlock()
	f, err := c.roundTrip(qlp.TypeScreenStart, cfg, nil, qlp.TypeScreenStatus, 15*time.Second)
	if err != nil {
		return qlp.ScreenStatus{}, err
	}
	var st qlp.ScreenStatus
	if err := f.Decode(&st); err != nil {
		return qlp.ScreenStatus{}, err
	}
	if !st.OK {
		if st.Error == "" {
			st.Error = "投屏失败"
		}
		return st, errors.New(st.Error)
	}
	return st, nil
}

func (c *Client) StopScreen() error {
	f, err := c.roundTrip(qlp.TypeScreenStop, nil, nil, qlp.TypeScreenStatus, 8*time.Second)
	if err != nil {
		return err
	}
	var st qlp.ScreenStatus
	if err := f.Decode(&st); err != nil {
		return err
	}
	if !st.OK {
		if st.Error == "" {
			st.Error = "停止投屏失败"
		}
		return errors.New(st.Error)
	}
	return nil
}

func (c *Client) Input(in qlp.Input) error {
	f, err := c.roundTrip(qlp.TypeInput, in, nil, qlp.TypeInputResult, 8*time.Second)
	if err != nil {
		return err
	}
	var res qlp.InputResult
	if err := f.Decode(&res); err != nil {
		return err
	}
	if !res.OK {
		if res.Error == "" {
			res.Error = "操作失败"
		}
		return errors.New(res.Error)
	}
	return nil
}

func (c *Client) Clipboard() (string, error) {
	f, err := c.roundTrip(qlp.TypeClipboardGet, nil, nil, qlp.TypeClipboardData, 8*time.Second)
	if err != nil {
		return "", err
	}
	var body qlp.Clipboard
	if err := f.Decode(&body); err != nil {
		return "", err
	}
	return body.Text, nil
}

func (c *Client) SetClipboard(text string) error {
	f, err := c.roundTrip(qlp.TypeClipboardSet, qlp.Clipboard{Text: text}, nil, qlp.TypeClipboardData, 8*time.Second)
	if err != nil {
		return err
	}
	var body qlp.Clipboard
	return f.Decode(&body)
}

func newID() string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}
