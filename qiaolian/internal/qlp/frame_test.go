package qlp

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	var buf bytes.Buffer
	body := PairRequest{Code: "123456"}
	bin := []byte{1, 2, 3, 4}
	if err := WriteFrame(&buf, TypePair, body, bin); err != nil {
		t.Fatal(err)
	}
	f, err := ReadFrame(&buf)
	if err != nil {
		t.Fatal(err)
	}
	if f.Type != TypePair {
		t.Fatalf("type %d", f.Type)
	}
	var got PairRequest
	if err := f.Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.Code != "123456" {
		t.Fatalf("code %s", got.Code)
	}
	if !bytes.Equal(f.Bin, bin) {
		t.Fatalf("bin %v", f.Bin)
	}
	if buf.Len() != 0 {
		t.Fatal("trailing bytes")
	}
}

func TestFrameEmptyJSON(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteFrame(&buf, TypePing, nil, nil); err != nil {
		t.Fatal(err)
	}
	f, err := ReadFrame(&buf)
	if err != nil {
		t.Fatal(err)
	}
	if f.Type != TypePing || len(f.JSON) != 0 || len(f.Bin) != 0 {
		t.Fatalf("%+v", f)
	}
}

func TestFrameBadMagic(t *testing.T) {
	_, err := ReadFrame(bytes.NewReader(make([]byte, HeaderSize)))
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestFrameTooLarge(t *testing.T) {
	hdr := []byte{'Q', 'L', 1, TypePing, 0x7f, 0, 0, 0, 0, 0, 0, 0}
	_, err := ReadFrame(bytes.NewReader(hdr))
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestFrameShortBody(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteFrame(&buf, TypeClipboardSet, Clipboard{Text: "hi"}, nil); err != nil {
		t.Fatal(err)
	}
	raw := buf.Bytes()[:buf.Len()-1]
	_, err := ReadFrame(bytes.NewReader(raw))
	if !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
		t.Fatalf("got %v", err)
	}
}

func TestCleanRel(t *testing.T) {
	got, err := CleanRel(`//DCIM/./Camera`)
	if err != nil || got != "DCIM/Camera" {
		t.Fatalf("%q %v", got, err)
	}
	if _, err := CleanRel(`DCIM/../../etc/passwd`); err == nil {
		t.Fatal("expected reject")
	}
	if _, err := CleanRel(`a\b`); err != nil {
		t.Fatal(err)
	} else if p, _ := CleanRel(`a\b`); p != "a/b" {
		t.Fatal(p)
	}
}

func TestBeaconJSON(t *testing.T) {
	b := Beacon{Magic: BeaconMagic, Name: "手机", OS: "android", ID: "abc", Port: DefaultTCPPort, Proto: ProtoVersion}
	raw, err := json.Marshal(b)
	if err != nil {
		t.Fatal(err)
	}
	var got Beacon
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if got.Magic != BeaconMagic || got.Port != DefaultTCPPort {
		t.Fatalf("%+v", got)
	}
}
