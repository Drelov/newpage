package main

import (
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"sync"

	"qiaolian/internal/agent"
	"qiaolian/internal/preview"
	"qiaolian/internal/qlp"
)

func main() {
	port := flag.Int("port", qlp.DefaultTCPPort, "TCP 端口")
	code := flag.String("code", "123456", "配对码")
	name := flag.String("name", "模拟手机", "显示名称")
	root := flag.String("root", "", "共享目录，默认临时目录")
	flag.Parse()

	dir := *root
	if dir == "" {
		var err error
		dir, err = os.MkdirTemp("", "qiaolian-phone-")
		if err != nil {
			log.Fatal(err)
		}
		_ = os.WriteFile(dir+"/欢迎.txt", []byte("这是桥联模拟手机里的文件。\n"), 0o644)
	}
	screen := &preview.Screen{Name: "PHONE", W: 360, H: 780}
	var clip sync.Mutex
	clipText := "模拟剪贴板"
	ag := agent.New(agent.Config{
		Name:      *name,
		OS:        "fake",
		OSVersion: "1",
		Root:      dir,
		Width:     360,
		Height:    780,
		Code:      func() string { return *code },
		Hooks: agent.Hooks{
			OnInput: func(in qlp.Input) error {
				if in.Action == "toast" {
					fmt.Println("提示:", in.Text)
					return nil
				}
				screen.Note(in)
				return nil
			},
			ClipboardGet: func() string {
				clip.Lock()
				defer clip.Unlock()
				return clipText
			},
			ClipboardSet: func(text string) error {
				clip.Lock()
				clipText = text
				clip.Unlock()
				return nil
			},
			Capture: func(cfg qlp.ScreenStart) ([]byte, int, int, error) {
				return screen.JPEG(cfg.Quality)
			},
		},
	})
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", *port))
	if err != nil {
		log.Fatal(err)
	}
	tcpPort := ln.Addr().(*net.TCPAddr).Port
	if stop, err := ag.Announce(tcpPort); err != nil {
		log.Printf("UDP 发现未开启：%v", err)
	} else {
		defer stop()
	}
	fmt.Printf("模拟手机已启动  端口 %d  配对码 %s\n共享目录 %s\n", tcpPort, *code, dir)
	err = ag.Serve(ln)
	if err != nil && !errors.Is(err, net.ErrClosed) {
		log.Fatal(err)
	}
}
