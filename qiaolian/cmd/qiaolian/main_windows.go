//go:build windows

package main

import (
	"flag"
	"fmt"

	"qiaolian/internal/winui"
)

func main() {
	flag.Parse()
	logBesideExe("桥联启动")
	if *webFlag {
		runWeb()
		return
	}
	defer func() {
		if r := recover(); r != nil {
			msg := fmt.Sprint(r)
			logBesideExe("窗口异常: " + msg)
			alert("桥联", "窗口没有打开："+msg+"\n将改用浏览器。")
			runWeb()
		}
	}()
	if err := winui.Run(pcName()); err != nil {
		logBesideExe("窗口创建失败: " + err.Error())
		alert("桥联", "窗口没有打开："+err.Error()+"\n将改用浏览器。")
		runWeb()
		return
	}
	logBesideExe("桥联退出")
}
