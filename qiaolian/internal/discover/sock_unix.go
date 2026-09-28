//go:build unix

package discover

import "syscall"

func setBroadcastFD(fd uintptr) error {
	return syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_BROADCAST, 1)
}
