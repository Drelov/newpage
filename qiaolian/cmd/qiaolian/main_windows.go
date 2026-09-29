//go:build windows

package main

import (
	"flag"
	"log"

	"qiaolian/internal/winui"
)

func main() {
	flag.Parse()
	if *webFlag {
		runWeb()
		return
	}
	if err := winui.Run(pcName()); err != nil {
		log.Fatal(err)
	}
}
