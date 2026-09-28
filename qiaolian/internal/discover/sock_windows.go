//go:build windows

package discover

import (
	"syscall"
	"unsafe"
)

var (
	modws2_32      = syscall.NewLazyDLL("ws2_32.dll")
	procsetsockopt = modws2_32.NewProc("setsockopt")
)

func setBroadcastFD(fd uintptr) error {
	v := int32(1)
	// SOL_SOCKET = 0xffff, SO_BROADCAST = 0x20 on Windows.
	r, _, err := procsetsockopt.Call(
		fd,
		uintptr(0xffff),
		uintptr(0x20),
		uintptr(unsafe.Pointer(&v)),
		unsafe.Sizeof(v),
	)
	if int32(r) != 0 {
		if err != syscall.Errno(0) {
			return err
		}
		return syscall.EINVAL
	}
	return nil
}
