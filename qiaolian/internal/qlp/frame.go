// Package qlp is the QiaoLian wire protocol shared by the PC, Android, and HarmonyOS clients.
package qlp

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

const (
	Magic0       = 'Q'
	Magic1       = 'L'
	Version      = 1
	HeaderSize   = 12
	MaxJSON      = 1 << 20
	MaxBin       = 4 << 20
	ChunkSize    = 256 << 10
	ProtoVersion = 1

	DefaultTCPPort = 37622
	DefaultUDPPort = 37621
	BeaconMagic    = "QLP1"
)

const (
	TypeHello          byte = 1
	TypePair           byte = 2
	TypePairResult     byte = 3
	TypePing           byte = 4
	TypePong           byte = 5
	TypeList           byte = 10
	TypeListResult     byte = 11
	TypePull           byte = 12
	TypePushBegin      byte = 13
	TypeChunk          byte = 14
	TypeTransferStatus byte = 15
	TypeMkdir          byte = 16
	TypeDelete         byte = 17
	TypeRename         byte = 18
	TypeOpResult       byte = 19
	TypeScreenStart    byte = 20
	TypeScreenStop     byte = 21
	TypeFrame          byte = 22
	TypeScreenStatus   byte = 23
	TypeInput          byte = 30
	TypeInputResult    byte = 31
	TypeClipboardSet   byte = 32
	TypeClipboardGet   byte = 33
	TypeClipboardData  byte = 34
	TypeError          byte = 40
)

// Frame is one length-delimited message.
// Layout (big-endian):
//
//	0-1  magic 'Q''L'
//	2    version (1)
//	3    type
//	4-7  json length
//	8-11 binary length
//	12.. json bytes, then binary bytes
type Frame struct {
	Type byte
	JSON []byte
	Bin  []byte
}

func (f Frame) Decode(dst any) error {
	if len(f.JSON) == 0 || dst == nil {
		return nil
	}
	return json.Unmarshal(f.JSON, dst)
}

// WriteFrame writes one frame. obj may be nil. bin may be nil.
func WriteFrame(w io.Writer, typ byte, obj any, bin []byte) error {
	var js []byte
	var err error
	if obj != nil {
		js, err = json.Marshal(obj)
		if err != nil {
			return err
		}
	}
	if len(js) > MaxJSON {
		return errors.New("json 过大")
	}
	if len(bin) > MaxBin {
		return errors.New("数据块过大")
	}
	var hdr [HeaderSize]byte
	hdr[0] = Magic0
	hdr[1] = Magic1
	hdr[2] = Version
	hdr[3] = typ
	binary.BigEndian.PutUint32(hdr[4:8], uint32(len(js)))
	binary.BigEndian.PutUint32(hdr[8:12], uint32(len(bin)))
	if _, err = w.Write(hdr[:]); err != nil {
		return err
	}
	if len(js) > 0 {
		if _, err = w.Write(js); err != nil {
			return err
		}
	}
	if len(bin) > 0 {
		if _, err = w.Write(bin); err != nil {
			return err
		}
	}
	return nil
}

// ReadFrame reads one frame. It rejects oversized lengths before allocating them.
func ReadFrame(r io.Reader) (Frame, error) {
	var hdr [HeaderSize]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Frame{}, err
	}
	if hdr[0] != Magic0 || hdr[1] != Magic1 {
		return Frame{}, errors.New("协议头不匹配")
	}
	if hdr[2] != Version {
		return Frame{}, fmt.Errorf("协议版本 %d 不受支持", hdr[2])
	}
	jl := binary.BigEndian.Uint32(hdr[4:8])
	bl := binary.BigEndian.Uint32(hdr[8:12])
	if jl > MaxJSON || bl > MaxBin {
		return Frame{}, errors.New("帧过大")
	}
	f := Frame{Type: hdr[3]}
	if jl > 0 {
		f.JSON = make([]byte, jl)
		if _, err := io.ReadFull(r, f.JSON); err != nil {
			return Frame{}, err
		}
	}
	if bl > 0 {
		f.Bin = make([]byte, bl)
		if _, err := io.ReadFull(r, f.Bin); err != nil {
			return Frame{}, err
		}
	}
	return f, nil
}
