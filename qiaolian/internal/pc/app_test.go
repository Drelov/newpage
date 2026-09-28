package pc

import (
	"bytes"
	"context"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"qiaolian/internal/agent"
	"qiaolian/internal/preview"
	"qiaolian/internal/qlp"
)

func TestPhoneSession(t *testing.T) {
	app, srv := startStack(t)

	if err := app.Connect("127.0.0.1", srv.port, "000000"); err == nil {
		t.Fatal("wrong code should fail")
	}
	if err := app.Connect("127.0.0.1", srv.port, "135790"); err != nil {
		t.Fatal(err)
	}
	client := app.current()
	if client.Hello().OS != "android" {
		t.Fatalf("hello %+v", client.Hello())
	}

	list, err := client.List("")
	if err != nil {
		t.Fatal(err)
	}
	if len(list.Entries) != 1 || list.Entries[0].Name != "欢迎.txt" {
		t.Fatalf("%+v", list.Entries)
	}
	if _, err := client.List("../etc"); err == nil {
		t.Fatal("traversal should fail")
	}
	if err := client.Mkdir("照片"); err != nil {
		t.Fatal(err)
	}
	payload := bytes.Repeat([]byte("桥联"), 100000)
	if err := client.Push("照片/大文件.bin", bytes.NewReader(payload), int64(len(payload))); err != nil {
		t.Fatal(err)
	}
	var got bytes.Buffer
	if err := client.Pull("照片/大文件.bin", &got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.Bytes(), payload) {
		t.Fatalf("len %d vs %d", got.Len(), len(payload))
	}
	if err := client.Rename("照片/大文件.bin", "照片/改名.bin"); err != nil {
		t.Fatal(err)
	}
	if err := client.Delete("照片/改名.bin"); err != nil {
		t.Fatal(err)
	}

	if err := client.SetClipboard("你好，电脑"); err != nil {
		t.Fatal(err)
	}
	text, err := client.Clipboard()
	if err != nil || text != "你好，电脑" {
		t.Fatalf("%q %v", text, err)
	}
	if err := client.Input(qlp.Input{Action: "tap", X: 0.4, Y: 0.6}); err != nil {
		t.Fatal(err)
	}
	st, err := client.StartScreen(qlp.ScreenStart{MaxWidth: 360, Quality: 60, FPS: 8})
	if err != nil || !st.Active {
		t.Fatalf("%+v %v", st, err)
	}
	deadline := time.Now().Add(2 * time.Second)
	var frameOK bool
	for time.Now().Before(deadline) {
		f := client.Frame()
		if len(f.JPEG) > 100 && f.JPEG[0] == 0xff && f.JPEG[1] == 0xd8 {
			frameOK = true
			break
		}
		time.Sleep(30 * time.Millisecond)
	}
	if !frameOK {
		t.Fatal("no jpeg frame")
	}
	if err := client.StopScreen(); err != nil {
		t.Fatal(err)
	}

	id := client.Hello().ID
	app.Disconnect()
	if err := app.Connect("127.0.0.1", srv.port, ""); err != nil {
		t.Fatal(err)
	}
	if app.current().Hello().ID != id {
		t.Fatal("token reconnect lost device id")
	}
}

func TestHTTPFilesAndMirror(t *testing.T) {
	app, phone := startStack(t)
	httpSrv := httptest.NewServer(app.Handler())
	defer httpSrv.Close()
	token := app.CSRF()
	if err := app.Connect("127.0.0.1", phone.port, "135790"); err != nil {
		t.Fatal(err)
	}

	res, err := http.Get(httpSrv.URL + "/api/state")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("state without token %d", res.StatusCode)
	}

	req, _ := http.NewRequest(http.MethodGet, httpSrv.URL+"/api/files?path=", nil)
	req.Header.Set("X-Qiaolian-Token", token)
	res, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !bytes.Contains(body, []byte("欢迎.txt")) {
		t.Fatalf("%d %s", res.StatusCode, body)
	}

	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	part, err := w.CreateFormFile("file", "笔记.txt")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write([]byte("from-pc"))
	_ = w.Close()
	req, _ = http.NewRequest(http.MethodPost, httpSrv.URL+"/api/upload?path=", &buf)
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("X-Qiaolian-Token", token)
	res, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("upload %d", res.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodPost, httpSrv.URL+"/api/screen/start", strings.NewReader(`{"maxWidth":320,"quality":50,"fps":8}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Qiaolian-Token", token)
	res, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("screen %d", res.StatusCode)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, _ = http.NewRequestWithContext(ctx, http.MethodGet, httpSrv.URL+"/api/screen/mjpeg?t="+token, nil)
	res, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	chunk := make([]byte, 64<<10)
	n, _ := res.Body.Read(chunk)
	if !bytes.Contains(chunk[:n], []byte{0xff, 0xd8}) {
		t.Fatalf("mjpeg missing jpeg, n=%d", n)
	}

	index, err := http.Get(httpSrv.URL + "/")
	if err != nil {
		t.Fatal(err)
	}
	page, _ := io.ReadAll(index.Body)
	index.Body.Close()
	if !bytes.Contains(page, []byte(token)) || !bytes.Contains(page, []byte("桥联")) {
		t.Fatal("index missing token or title")
	}
}

func TestPairLockout(t *testing.T) {
	app, phone := startStack(t)
	for i := 0; i < 5; i++ {
		err := app.Connect("127.0.0.1", phone.port, "000000")
		if err == nil || !strings.Contains(err.Error(), "配对码错误") {
			t.Fatalf("try %d: %v", i, err)
		}
	}
	err := app.Connect("127.0.0.1", phone.port, "000000")
	if err == nil || !strings.Contains(err.Error(), "尝试过多") {
		t.Fatal(err)
	}
}

type phoneSrv struct {
	port int
	ln   net.Listener
}

func startStack(t *testing.T) (*App, *phoneSrv) {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "欢迎.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	screen := &preview.Screen{Name: "PHONE", W: 200, H: 400}
	var clip sync.Mutex
	clipText := ""
	ag := agent.New(agent.Config{
		Name:      "测试机",
		OS:        "android",
		OSVersion: "14",
		ID:        "phone-test",
		Root:      dir,
		Width:     200,
		Height:    400,
		Code:      func() string { return "135790" },
		Hooks: agent.Hooks{
			OnInput: func(in qlp.Input) error {
				screen.Note(in)
				return nil
			},
			ClipboardGet: func() string {
				clip.Lock()
				defer clip.Unlock()
				return clipText
			},
			ClipboardSet: func(s string) error {
				clip.Lock()
				clipText = s
				clip.Unlock()
				return nil
			},
			Capture: func(cfg qlp.ScreenStart) ([]byte, int, int, error) {
				return screen.JPEG(cfg.Quality)
			},
		},
	})
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go ag.Serve(ln)
	t.Cleanup(func() { ln.Close() })
	app := New("测试电脑")
	app.UseTokenFile(filepath.Join(t.TempDir(), "paired.json"))
	return app, &phoneSrv{port: ln.Addr().(*net.TCPAddr).Port, ln: ln}
}
