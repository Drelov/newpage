package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"time"

	"qiaolian/internal/pc"
)

var (
	addrFlag  = flag.String("addr", "127.0.0.1:8787", "浏览器界面地址，仅在 -web 时使用")
	webFlag   = flag.Bool("web", false, "使用浏览器界面。Windows 上默认打开桌面窗口")
	nameFlag  = flag.String("name", "", "电脑名称，默认用主机名")
	noBrowser = flag.Bool("no-browser", false, "使用浏览器界面时不自动打开")
)

func pcName() string {
	if *nameFlag != "" {
		return *nameFlag
	}
	name, _ := os.Hostname()
	return name
}

func runWeb() {
	app := pc.New(pcName())
	if err := app.StartDiscovery(); err != nil {
		log.Printf("局域网发现暂不可用（仍可用 IP 手动连接）：%v", err)
	}
	url := "http://" + *addrFlag + "/"
	if !*noBrowser {
		go func() {
			time.Sleep(400 * time.Millisecond)
			openBrowser(url)
		}()
	}
	fmt.Println("桥联已启动：" + url)
	log.Fatal(http.ListenAndServe(*addrFlag, app.Handler()))
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}
