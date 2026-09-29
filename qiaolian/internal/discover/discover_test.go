package discover

import (
	"context"
	"net"
	"strings"
	"testing"
	"time"

	"qiaolian/internal/qlp"
)

func TestListenReceivesBeacon(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	got := make(chan Device, 1)
	cache := NewSeen()
	port, err := Listen(ctx, 0, func(b qlp.Beacon, ip string) {
		cache.Upsert(b, ip)
		got <- Device{ID: b.ID, IP: ip, Port: b.Port}
	})
	if err != nil {
		t.Fatal(err)
	}
	err = SendTo(net.JoinHostPort("127.0.0.1", itoa(port)), qlp.Beacon{
		Magic: qlp.BeaconMagic,
		Name:  "手机",
		OS:    "android",
		ID:    "device-1",
		Port:  qlp.DefaultTCPPort,
		Proto: qlp.ProtoVersion,
	})
	if err != nil {
		t.Fatal(err)
	}
	select {
	case d := <-got:
		if d.ID != "device-1" || d.IP == "" || d.Port != qlp.DefaultTCPPort {
			t.Fatalf("%+v", d)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("beacon not received")
	}
	list := cache.List(time.Now(), time.Second)
	if len(list) != 1 || list[0].Name != "手机" {
		t.Fatalf("%+v", list)
	}
}

func TestPhoneAnswersProbe(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	port, err := ServePhone(ctx, 0, func() qlp.Beacon {
		return qlp.Beacon{Magic: qlp.BeaconMagic, Name: "鸿蒙", OS: "harmony", ID: "h1", Port: 40000, Proto: 1}
	})
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.Dial("udp4", net.JoinHostPort("127.0.0.1", itoa(port)))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte(`{"magic":"QLP1","op":"discover"}`)); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 2048)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	if n == 0 || !strings.Contains(string(buf[:n]), `"id":"h1"`) {
		t.Fatalf("reply %s", buf[:n])
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [8]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}
