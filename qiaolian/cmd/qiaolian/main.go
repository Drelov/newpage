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

func main() {
	addr := flag.String("addr", "127.0.0.1:8787", "本机页面地址")
	noBrowser := flag.Bool("no-browser", false, "不自动打开浏览器")
	nameFlag := flag.String("name", "", "电脑名称，默认用主机名")
	flag.Parse()

	name := *nameFlag
	if name == "" {
		name, _ = os.Hostname()
	}
	app := pc.New(name)
	if err := app.StartDiscovery(); err != nil {
		log.Printf("局域网发现暂不可用（仍可用 IP 手动连接）：%v", err)
	}
	url := "http://" + *addr + "/"
	if !*noBrowser {
		go func() {
			time.Sleep(400 * time.Millisecond)
			openBrowser(url)
		}()
	}
	fmt.Println("桥联已启动：" + url)
	fmt.Println("手机和电脑连接同一个 Wi-Fi，在手机上打开桥联。")
	log.Fatal(http.ListenAndServe(*addr, app.Handler()))
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
