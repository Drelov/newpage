//go:build windows

package winui

import (
	"bytes"
	"image/jpeg"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"

	"qiaolian/internal/discover"
	"qiaolian/internal/pc"
	"qiaolian/internal/qlp"
)

type preset struct {
	Name    string
	Width   int
	FPS     int
	Quality int
}

var presets = []preset{
	{"流畅，延迟更低", 540, 30, 28},
	{"均衡", 800, 24, 48},
	{"高清", 1080, 15, 65},
}

type stringList struct {
	walk.ListModelBase
	items []string
}

func (m *stringList) ItemCount() int {
	if m == nil {
		return 0
	}
	return len(m.items)
}

func (m *stringList) Value(index int) interface{} { return m.items[index] }

type desk struct {
	app         *pc.App
	mw          *walk.MainWindow
	ip          *walk.LineEdit
	port        *walk.LineEdit
	code        *walk.LineEdit
	status      *walk.Label
	latency     *walk.Label
	devices     *stringList
	deviceBox   *walk.ListBox
	deviceItems []discover.Device
	lastGap     time.Duration
	lastRTT     time.Duration
	lastLatency time.Time
	files       *stringList
	fileBox     *walk.ListBox
	entries     []qlp.Entry
	dir         string
	screen      *walk.CustomWidget
	clip        *walk.TextEdit
	remoteText  *walk.LineEdit
	presetBox   *walk.ComboBox
	brush       *walk.SolidColorBrush

	mu        sync.Mutex
	bmp       *walk.Bitmap
	imgRect   walk.Rectangle
	downX     int
	downY     int
	downNorm  struct{ x, y float64 }
	pressed   bool
	dragging  bool
	gen       int
	inputRun  bool
	inputWait []qlp.Input
}

// Run opens the desktop window.
func Run(name string) error {
	if name == "" {
		name, _ = os.Hostname()
	}
	d := &desk{
		app:     pc.New(name),
		devices: &stringList{},
		files:   &stringList{},
	}
	_ = d.app.StartDiscovery()
	brush, err := walk.NewSolidColorBrush(walk.RGB(16, 20, 27))
	if err != nil {
		return err
	}
	d.brush = brush
	if err := d.window(); err != nil {
		return err
	}
	go d.pollDevices()
	d.mw.Run()
	return nil
}

func (d *desk) window() error {
	return MainWindow{
		AssignTo: &d.mw,
		Title:    "桥联",
		MinSize:  Size{980, 680},
		Size:     Size{1180, 760},
		Layout:   HBox{Margins: Margins{8, 8, 8, 8}},
		Children: []Widget{
			Composite{
				MinSize: Size{280, 0},
				MaxSize: Size{320, 0},
				Layout:  VBox{},
				Children: []Widget{
					Label{Text: "桥联"},
					Label{Text: "附近的手机"},
					ListBox{AssignTo: &d.deviceBox, Model: d.devices, OnCurrentIndexChanged: d.pickDevice},
					Label{Text: "手机 IP"},
					LineEdit{AssignTo: &d.ip},
					Composite{
						Layout: HBox{MarginsZero: true},
						Children: []Widget{
							Label{Text: "端口"},
							LineEdit{AssignTo: &d.port, Text: "37622"},
							Label{Text: "配对码"},
							LineEdit{AssignTo: &d.code},
						},
					},
					PushButton{Text: "连接", OnClicked: d.connect},
					PushButton{Text: "断开", OnClicked: d.disconnect},
					Label{AssignTo: &d.status, Text: "手机打开桥联，点一次「一键连接」。"},
					Label{AssignTo: &d.latency, Text: "延迟：未连接"},
					VSpacer{},
				},
			},
			TabWidget{
				StretchFactor: 1,
				Pages: []TabPage{
					d.screenPage(),
					d.filePage(),
					d.clipPage(),
				},
			},
		},
	}.Create()
}

func (d *desk) screenPage() TabPage {
	return TabPage{
		Title:  "投屏与控制",
		Layout: VBox{},
		Children: []Widget{
			Composite{
				Layout: HBox{MarginsZero: true},
				Children: []Widget{
					PushButton{Text: "开始投屏", OnClicked: d.startScreen},
					PushButton{Text: "停止", OnClicked: d.stopScreen},
					PushButton{Text: "返回", OnClicked: func() { d.key("back") }},
					PushButton{Text: "主页", OnClicked: func() { d.key("home") }},
					PushButton{Text: "最近任务", OnClicked: func() { d.key("recents") }},
					PushButton{Text: "上滑", OnClicked: func() { d.scroll(-0.25) }},
					PushButton{Text: "下滑", OnClicked: func() { d.scroll(0.25) }},
					ComboBox{
						AssignTo:     &d.presetBox,
						Model:        presetNames(),
						CurrentIndex: 0,
						OnCurrentIndexChanged: func() {
							if d.app.Client() != nil {
								d.startScreen()
							}
						},
					},
				},
			},
			CustomWidget{
				AssignTo:            &d.screen,
				StretchFactor:       1,
				ClearsBackground:    false,
				InvalidatesOnResize: true,
				PaintMode:           PaintBuffered,
				PaintPixels:         d.paint,
				OnMouseDown:         d.mouseDown,
				OnMouseMove:         d.mouseMove,
				OnMouseUp:           d.mouseUp,
			},
			Composite{
				Layout: HBox{MarginsZero: true},
				Children: []Widget{
					LineEdit{AssignTo: &d.remoteText},
					PushButton{Text: "发送文字", OnClicked: d.sendText},
				},
			},
			Label{Text: "按住拖动时手机跟着动，松开是点击。画面应显示完整手机屏幕。"},
		},
	}
}

func (d *desk) filePage() TabPage {
	return TabPage{
		Title:  "文件",
		Layout: VBox{},
		Children: []Widget{
			Composite{
				Layout: HBox{MarginsZero: true},
				Children: []Widget{
					PushButton{Text: "上一级", OnClicked: d.upDir},
					PushButton{Text: "刷新", OnClicked: d.reloadFiles},
					PushButton{Text: "下载", OnClicked: d.download},
					PushButton{Text: "上传", OnClicked: d.upload},
					PushButton{Text: "新建文件夹", OnClicked: d.mkdir},
					PushButton{Text: "删除", OnClicked: d.delete},
				},
			},
			ListBox{AssignTo: &d.fileBox, Model: d.files, OnItemActivated: d.openEntry},
		},
	}
}

func (d *desk) clipPage() TabPage {
	return TabPage{
		Title:  "剪贴板",
		Layout: VBox{},
		Children: []Widget{
			Composite{
				Layout: HBox{MarginsZero: true},
				Children: []Widget{
					PushButton{Text: "读取手机", OnClicked: d.clipGet},
					PushButton{Text: "写入手机", OnClicked: d.clipSet},
				},
			},
			TextEdit{AssignTo: &d.clip},
		},
	}
}

func presetNames() []string {
	out := make([]string, len(presets))
	for i, p := range presets {
		out[i] = p.Name
	}
	return out
}

func (d *desk) currentPreset() preset {
	idx := 0
	if d.presetBox != nil {
		idx = d.presetBox.CurrentIndex()
	}
	if idx < 0 || idx >= len(presets) {
		idx = 0
	}
	return presets[idx]
}

func (d *desk) pollDevices() {
	for {
		time.Sleep(2 * time.Second)
		if d.mw == nil {
			continue
		}
		list := d.app.Devices()
		names := make([]string, len(list))
		for i, item := range list {
			names[i] = item.Name + "  " + item.IP + ":" + strconv.Itoa(item.Port)
		}
		d.mw.Synchronize(func() {
			d.deviceItems = list
			d.devices.items = names
			d.devices.PublishItemsReset()
		})
	}
}

func (d *desk) pickDevice() {
	idx := d.deviceBox.CurrentIndex()
	if idx < 0 || idx >= len(d.deviceItems) {
		return
	}
	_ = d.ip.SetText(d.deviceItems[idx].IP)
	_ = d.port.SetText(strconv.Itoa(d.deviceItems[idx].Port))
}

func (d *desk) connect() {
	ip := d.ip.Text()
	port, _ := strconv.Atoi(d.port.Text())
	code := d.code.Text()
	d.setStatus("正在连接…")
	d.mu.Lock()
	d.gen++
	gen := d.gen
	d.mu.Unlock()
	go func() {
		err := d.app.Connect(ip, port, code)
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
				return
			}
			hello := d.app.Client().Hello()
			d.setStatus("已连接 " + hello.Name)
			d.reloadFiles()
			d.startScreen()
		})
		if err == nil {
			d.watch(gen)
		}
	}()
}

func (d *desk) disconnect() {
	d.mu.Lock()
	d.gen++
	d.mu.Unlock()
	d.app.Disconnect()
	d.setStatus("已断开")
	d.clearBitmap()
}

func (d *desk) watch(gen int) {
	client := d.app.Client()
	if client == nil {
		return
	}
	id, ch := client.Subscribe()
	defer client.Unsubscribe(id)
	var seq uint64
	var last time.Time
	go d.pingLoop(gen)
	for {
		d.mu.Lock()
		alive := gen == d.gen
		d.mu.Unlock()
		if !alive {
			return
		}
		select {
		case <-client.Done():
			d.mw.Synchronize(func() {
				d.setStatus("手机已断开")
				d.clearBitmap()
			})
			return
		case <-ch:
			frame := client.Frame()
			if frame.Seq == seq || len(frame.JPEG) < 4 {
				continue
			}
			gap := time.Duration(0)
			now := time.Now()
			if !last.IsZero() {
				gap = now.Sub(last)
			}
			last = now
			seq = frame.Seq
			img, err := jpeg.Decode(bytes.NewReader(frame.JPEG))
			if err != nil {
				continue
			}
			d.mw.Synchronize(func() {
				bmp, err := walk.NewBitmapFromImage(img)
				if err != nil {
					return
				}
				old := d.bmp
				d.bmp = bmp
				if old != nil {
					old.Dispose()
				}
				d.screen.Invalidate()
				if gap > 0 {
					d.lastGap = gap
					d.showLatency()
				}
			})
		}
	}
}

func (d *desk) pingLoop(gen int) {
	for {
		time.Sleep(2 * time.Second)
		d.mu.Lock()
		alive := gen == d.gen
		d.mu.Unlock()
		if !alive {
			return
		}
		client := d.app.Client()
		if client == nil {
			return
		}
		rtt, err := client.Ping()
		if err != nil {
			continue
		}
		d.mw.Synchronize(func() {
			d.lastRTT = rtt
			d.showLatency()
		})
	}
}

func (d *desk) startScreen() {
	client := d.app.Client()
	if client == nil {
		d.setStatus("还没有连接手机")
		return
	}
	cfg := d.currentPreset()
	go func() {
		_, err := client.StartScreen(qlp.ScreenStart{MaxWidth: cfg.Width, Quality: cfg.Quality, FPS: cfg.FPS})
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
				return
			}
			d.setStatus("正在投屏：" + cfg.Name)
		})
	}()
}

func (d *desk) stopScreen() {
	client := d.app.Client()
	if client == nil {
		return
	}
	go func() {
		_ = client.StopScreen()
	}()
}

func (d *desk) showLatency() {
	if d.latency == nil {
		return
	}
	if time.Since(d.lastLatency) < 500*time.Millisecond {
		return
	}
	d.lastLatency = time.Now()
	_ = d.latency.SetText("往返 " + strconv.Itoa(int(d.lastRTT.Milliseconds())) + " ms · 画面间隔 " + strconv.Itoa(int(d.lastGap.Milliseconds())) + " ms")
}

func (d *desk) paint(canvas *walk.Canvas, _ walk.Rectangle) error {
	cb := d.screen.ClientBoundsPixels()
	bounds := walk.Rectangle{Width: cb.Width, Height: cb.Height}
	_ = canvas.FillRectanglePixels(d.brush, bounds)
	if d.bmp == nil || bounds.Width < 2 || bounds.Height < 2 {
		d.imgRect = walk.Rectangle{}
		return nil
	}
	dst := letterbox(bounds, d.bmp.Size())
	d.imgRect = dst
	return canvas.DrawImageStretchedPixels(d.bmp, dst)
}

func letterbox(bounds walk.Rectangle, src walk.Size) walk.Rectangle {
	if src.Width <= 0 || src.Height <= 0 || bounds.Width <= 0 || bounds.Height <= 0 {
		return bounds
	}
	scaleW := float64(bounds.Width) / float64(src.Width)
	scaleH := float64(bounds.Height) / float64(src.Height)
	scale := scaleW
	if scaleH < scaleW {
		scale = scaleH
	}
	w := int(float64(src.Width) * scale)
	h := int(float64(src.Height) * scale)
	return walk.Rectangle{
		X:      bounds.X + (bounds.Width-w)/2,
		Y:      bounds.Y + (bounds.Height-h)/2,
		Width:  w,
		Height: h,
	}
}

func (d *desk) mouseDown(x, y int, _ walk.MouseButton) {
	nx, ny, ok := d.normalize(x, y)
	if !ok {
		d.pressed = false
		d.dragging = false
		return
	}
	d.pressed = true
	d.dragging = false
	d.downX, d.downY = x, y
	d.downNorm.x, d.downNorm.y = nx, ny
}

func (d *desk) mouseMove(x, y int, _ walk.MouseButton) {
	if !d.pressed {
		return
	}
	if !d.dragging {
		if abs(x-d.downX)+abs(y-d.downY) < 8 {
			return
		}
		d.dragging = true
		d.input(qlp.Input{Action: "down", X: d.downNorm.x, Y: d.downNorm.y})
	}
	nx, ny := d.clampNorm(x, y)
	d.input(qlp.Input{Action: "move", X: nx, Y: ny})
}

func (d *desk) mouseUp(x, y int, _ walk.MouseButton) {
	if !d.pressed {
		return
	}
	d.pressed = false
	if !d.dragging {
		d.input(qlp.Input{Action: "tap", X: d.downNorm.x, Y: d.downNorm.y})
		return
	}
	d.dragging = false
	nx, ny := d.clampNorm(x, y)
	d.input(qlp.Input{Action: "up", X: nx, Y: ny})
}

func (d *desk) normalize(x, y int) (float64, float64, bool) {
	r := d.imgRect
	if r.Width <= 1 || r.Height <= 1 {
		return 0, 0, false
	}
	if x < r.X || y < r.Y || x >= r.X+r.Width || y >= r.Y+r.Height {
		return 0, 0, false
	}
	return float64(x-r.X) / float64(r.Width-1), float64(y-r.Y) / float64(r.Height-1), true
}

func (d *desk) clampNorm(x, y int) (float64, float64) {
	r := d.imgRect
	if r.Width <= 1 || r.Height <= 1 {
		return d.downNorm.x, d.downNorm.y
	}
	if x < r.X {
		x = r.X
	}
	if y < r.Y {
		y = r.Y
	}
	if x >= r.X+r.Width {
		x = r.X + r.Width - 1
	}
	if y >= r.Y+r.Height {
		y = r.Y + r.Height - 1
	}
	return float64(x-r.X) / float64(r.Width-1), float64(y-r.Y) / float64(r.Height-1)
}

func (d *desk) scroll(dy float64) {
	d.input(qlp.Input{Action: "scroll", X: 0.5, Y: 0.5, DY: dy})
}

func (d *desk) key(name string) {
	d.input(qlp.Input{Action: "key", Key: name})
}

func (d *desk) sendText() {
	text := d.remoteText.Text()
	if text == "" {
		return
	}
	d.input(qlp.Input{Action: "text", Text: text})
	_ = d.remoteText.SetText("")
}

func (d *desk) input(in qlp.Input) {
	if d.app.Client() == nil {
		return
	}
	d.mu.Lock()
	if in.Action == "move" && len(d.inputWait) > 0 && d.inputWait[len(d.inputWait)-1].Action == "move" {
		d.inputWait[len(d.inputWait)-1] = in
	} else {
		d.inputWait = append(d.inputWait, in)
	}
	start := !d.inputRun
	d.inputRun = true
	d.mu.Unlock()
	if start {
		go d.drainInput()
	}
}

func (d *desk) drainInput() {
	for {
		d.mu.Lock()
		if len(d.inputWait) == 0 {
			d.inputRun = false
			d.mu.Unlock()
			return
		}
		in := d.inputWait[0]
		d.inputWait = d.inputWait[1:]
		d.mu.Unlock()
		client := d.app.Client()
		if client == nil {
			continue
		}
		if err := client.Input(in); err != nil {
			d.mw.Synchronize(func() { d.setStatus(err.Error()) })
		}
	}
}

func (d *desk) reloadFiles() {
	client := d.app.Client()
	if client == nil {
		return
	}
	dir := d.dir
	go func() {
		res, err := client.List(dir)
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
				return
			}
			d.entries = res.Entries
			names := make([]string, len(res.Entries))
			for i, ent := range res.Entries {
				if ent.Dir {
					names[i] = "[文件夹] " + ent.Name
				} else {
					names[i] = ent.Name
				}
			}
			d.files.items = names
			d.files.PublishItemsReset()
			d.setStatus("/" + dir)
		})
	}()
}

func (d *desk) selected() (qlp.Entry, string, bool) {
	idx := d.fileBox.CurrentIndex()
	if idx < 0 || idx >= len(d.entries) {
		return qlp.Entry{}, "", false
	}
	ent := d.entries[idx]
	child := ent.Name
	if d.dir != "" {
		child = d.dir + "/" + ent.Name
	}
	return ent, child, true
}

func (d *desk) openEntry() {
	ent, child, ok := d.selected()
	if !ok || !ent.Dir {
		return
	}
	d.dir = child
	d.reloadFiles()
}

func (d *desk) upDir() {
	if d.dir == "" {
		return
	}
	parent := path.Dir(d.dir)
	if parent == "." {
		parent = ""
	}
	d.dir = parent
	d.reloadFiles()
}

func (d *desk) download() {
	ent, child, ok := d.selected()
	if !ok || ent.Dir {
		d.setStatus("请选择一个文件")
		return
	}
	client := d.app.Client()
	if client == nil {
		return
	}
	dlg := new(walk.FileDialog)
	dlg.Title = "保存到电脑"
	dlg.FilePath = ent.Name
	accepted, err := dlg.ShowSave(d.mw)
	if err != nil || !accepted {
		return
	}
	go func() {
		f, err := os.Create(dlg.FilePath)
		if err != nil {
			d.mw.Synchronize(func() { d.setStatus(err.Error()) })
			return
		}
		defer f.Close()
		err = client.Pull(child, f)
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
				return
			}
			d.setStatus("已保存 " + dlg.FilePath)
		})
	}()
}

func (d *desk) upload() {
	client := d.app.Client()
	if client == nil {
		return
	}
	dlg := new(walk.FileDialog)
	dlg.Title = "选择要传到手机的文件"
	accepted, err := dlg.ShowOpen(d.mw)
	if err != nil || !accepted {
		return
	}
	dir := d.dir
	go func() {
		f, err := os.Open(dlg.FilePath)
		if err != nil {
			d.mw.Synchronize(func() { d.setStatus(err.Error()) })
			return
		}
		defer f.Close()
		info, err := f.Stat()
		if err != nil {
			d.mw.Synchronize(func() { d.setStatus(err.Error()) })
			return
		}
		name := filepath.Base(dlg.FilePath)
		rel := name
		if dir != "" {
			rel = dir + "/" + name
		}
		err = client.Push(rel, f, info.Size())
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
				return
			}
			d.reloadFiles()
		})
	}()
}

func (d *desk) mkdir() {
	client := d.app.Client()
	if client == nil {
		return
	}
	var dlg *walk.Dialog
	var nameEdit *walk.LineEdit
	var accepted bool
	_, err := Dialog{
		AssignTo: &dlg,
		Title:    "新建文件夹",
		MinSize:  Size{360, 120},
		Layout:   VBox{},
		Children: []Widget{
			LineEdit{AssignTo: &nameEdit},
			Composite{
				Layout: HBox{MarginsZero: true},
				Children: []Widget{
					PushButton{Text: "创建", OnClicked: func() { accepted = true; dlg.Accept() }},
					PushButton{Text: "取消", OnClicked: func() { dlg.Cancel() }},
				},
			},
		},
	}.Run(d.mw)
	if err != nil || !accepted || nameEdit == nil || nameEdit.Text() == "" {
		return
	}
	dir := d.dir
	name := nameEdit.Text()
	go func() {
		rel := name
		if dir != "" {
			rel = dir + "/" + name
		}
		err := client.Mkdir(rel)
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
				return
			}
			d.reloadFiles()
		})
	}()
}

func (d *desk) delete() {
	_, child, ok := d.selected()
	if !ok {
		return
	}
	client := d.app.Client()
	if client == nil {
		return
	}
	if walk.MsgBox(d.mw, "桥联", "删除 "+child+" ？", walk.MsgBoxYesNo|walk.MsgBoxIconQuestion) != walk.DlgCmdYes {
		return
	}
	go func() {
		err := client.Delete(child)
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
				return
			}
			d.reloadFiles()
		})
	}()
}

func (d *desk) clipGet() {
	client := d.app.Client()
	if client == nil {
		return
	}
	go func() {
		text, err := client.Clipboard()
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
				return
			}
			_ = d.clip.SetText(text)
		})
	}()
}

func (d *desk) clipSet() {
	client := d.app.Client()
	if client == nil {
		return
	}
	text := d.clip.Text()
	go func() {
		err := client.SetClipboard(text)
		d.mw.Synchronize(func() {
			if err != nil {
				d.setStatus(err.Error())
			}
		})
	}()
}

func (d *desk) setStatus(text string) {
	if d.status != nil {
		_ = d.status.SetText(text)
	}
}

func (d *desk) clearBitmap() {
	if d.bmp != nil {
		d.bmp.Dispose()
		d.bmp = nil
	}
	if d.screen != nil {
		d.screen.Invalidate()
	}
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}
