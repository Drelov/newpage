// Package discover finds phones on the same Wi-Fi with a small UDP beacon.
package discover

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"syscall"
	"time"

	"qiaolian/internal/qlp"
)

// ServePhone listens for discovery probes and broadcasts a beacon until ctx ends.
// port 0 asks the OS for a free port. The bound port is returned.
func ServePhone(ctx context.Context, port int, beacon func() qlp.Beacon) (int, error) {
	conn, err := listenUDP(port)
	if err != nil {
		return 0, err
	}
	bound := conn.LocalAddr().(*net.UDPAddr).Port
	go func() {
		<-ctx.Done()
		conn.Close()
	}()
	go readProbes(conn, beacon)
	go broadcastLoop(ctx, conn, beacon)
	return bound, nil
}

// Listen reports beacons received on port. Probes are ignored.
// The bound port is returned so tests can use port 0.
func Listen(ctx context.Context, port int, found func(b qlp.Beacon, ip string)) (int, error) {
	conn, err := listenUDP(port)
	if err != nil {
		return 0, err
	}
	go func() {
		<-ctx.Done()
		conn.Close()
	}()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			var b qlp.Beacon
			if json.Unmarshal(buf[:n], &b) != nil || b.Magic != qlp.BeaconMagic {
				continue
			}
			if b.Op == "discover" {
				continue
			}
			if b.Port <= 0 || b.Port > 65535 || b.ID == "" {
				continue
			}
			ip := ""
			if addr != nil && addr.IP != nil {
				ip = addr.IP.String()
			}
			found(b, ip)
		}
	}()
	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()
		probe := qlp.Beacon{Magic: qlp.BeaconMagic, Op: "discover"}
		raw, _ := json.Marshal(probe)
		for {
			sendBroadcast(conn, qlp.DefaultUDPPort, raw)
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
	return conn.LocalAddr().(*net.UDPAddr).Port, nil
}

// SendTo delivers one beacon to a specific UDP address. Tests use it, and the
// phone uses it to answer a probe.
func SendTo(dst string, b qlp.Beacon) error {
	conn, err := net.Dial("udp4", dst)
	if err != nil {
		return err
	}
	defer conn.Close()
	raw, err := json.Marshal(b)
	if err != nil {
		return err
	}
	_, err = conn.Write(raw)
	return err
}

func readProbes(conn *net.UDPConn, beacon func() qlp.Beacon) {
	buf := make([]byte, 2048)
	for {
		n, addr, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		var b qlp.Beacon
		if json.Unmarshal(buf[:n], &b) != nil || b.Magic != qlp.BeaconMagic || b.Op != "discover" {
			continue
		}
		if addr == nil {
			continue
		}
		raw, err := json.Marshal(beacon())
		if err != nil {
			continue
		}
		_, _ = conn.WriteToUDP(raw, addr)
	}
}

func broadcastLoop(ctx context.Context, conn *net.UDPConn, beacon func() qlp.Beacon) {
	send := func() {
		raw, err := json.Marshal(beacon())
		if err != nil {
			return
		}
		sendBroadcast(conn, qlp.DefaultUDPPort, raw)
	}
	send()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			send()
		}
	}
}

func sendBroadcast(conn *net.UDPConn, port int, raw []byte) {
	dst := &net.UDPAddr{IP: net.IPv4bcast, Port: port}
	_, _ = conn.WriteToUDP(raw, dst)
}

func listenUDP(port int) (*net.UDPConn, error) {
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			var sockErr error
			if err := c.Control(func(fd uintptr) {
				sockErr = setBroadcastFD(fd)
			}); err != nil {
				return err
			}
			return sockErr
		},
	}
	pc, err := lc.ListenPacket(context.Background(), "udp4", fmt.Sprintf(":%d", port))
	if err != nil {
		return nil, err
	}
	conn, ok := pc.(*net.UDPConn)
	if !ok {
		pc.Close()
		return nil, fmt.Errorf("unexpected packet conn %T", pc)
	}
	return conn, nil
}

// SeenCache collapses duplicate beacons for the PC device list.
type SeenCache struct {
	mu    sync.Mutex
	items map[string]seen
}

type seen struct {
	Beacon qlp.Beacon
	IP     string
	At     time.Time
}

func NewSeen() *SeenCache {
	return &SeenCache{items: map[string]seen{}}
}

func (c *SeenCache) Upsert(b qlp.Beacon, ip string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items[b.ID] = seen{Beacon: b, IP: ip, At: time.Now()}
}

// Device is one phone visible on the LAN.
type Device struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	OS       string `json:"os"`
	IP       string `json:"ip"`
	Port     int    `json:"port"`
	LastSeen int64  `json:"lastSeen"`
}

func (c *SeenCache) List(now time.Time, ttl time.Duration) []Device {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]Device, 0, len(c.items))
	for id, item := range c.items {
		if now.Sub(item.At) > ttl {
			delete(c.items, id)
			continue
		}
		out = append(out, Device{
			ID:       item.Beacon.ID,
			Name:     item.Beacon.Name,
			OS:       item.Beacon.OS,
			IP:       item.IP,
			Port:     item.Beacon.Port,
			LastSeen: item.At.UnixMilli(),
		})
	}
	return out
}
