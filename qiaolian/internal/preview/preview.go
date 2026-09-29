// Package preview draws a test picture for the fake phone.
package preview

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	"sync"
	"time"

	"qiaolian/internal/qlp"
)

// Screen is a generated phone display that records the last remote action.
type Screen struct {
	mu     sync.Mutex
	Name   string
	W      int
	H      int
	Action string
	MarkX  float64
	MarkY  float64
	Marked bool
	Text   string
	Frames int
}

func (s *Screen) size() (int, int) {
	w, h := s.W, s.H
	if w <= 0 {
		w = 360
	}
	if h <= 0 {
		h = 780
	}
	return w, h
}

// Note records a remote input so the next frame can show it.
func (s *Screen) Note(in qlp.Input) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Action = in.Action
	if in.Key != "" {
		s.Action = in.Action + " " + in.Key
	}
	if in.Text != "" {
		s.Text = in.Text
	}
	if in.Action == "tap" || in.Action == "swipe" || in.Action == "scroll" {
		s.MarkX = in.X
		s.MarkY = in.Y
		s.Marked = true
	}
}

// JPEG renders the current picture.
func (s *Screen) JPEG(quality int) ([]byte, int, int, error) {
	s.mu.Lock()
	w, h := s.size()
	name := s.Name
	action := s.Action
	text := s.Text
	marked := s.Marked
	mx, my := s.MarkX, s.MarkY
	s.Frames++
	frame := s.Frames
	s.mu.Unlock()

	img := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(img, img.Bounds(), &image.Uniform{color.RGBA{18, 28, 38, 255}}, image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(0, 0, w, 72), &image.Uniform{color.RGBA{18, 92, 86, 255}}, image.Point{}, draw.Src)
	drawText(img, 16, 28, "QIAOLIAN", color.RGBA{232, 248, 244, 255})
	if name != "" {
		drawText(img, 16, 96, name, color.RGBA{180, 210, 206, 255})
	}
	shift := (frame * 12) % (w + 80)
	draw.Draw(img, image.Rect(shift-80, h/2, shift, h/2+28), &image.Uniform{color.RGBA{61, 214, 198, 255}}, image.Point{}, draw.Src)
	clock := time.Now().Format("15:04:05")
	drawText(img, 16, h-120, clock, color.RGBA{232, 236, 244, 255})
	if action != "" {
		drawText(img, 16, h-88, action, color.RGBA{255, 196, 87, 255})
	}
	if text != "" {
		drawText(img, 16, h-56, text, color.RGBA{232, 236, 244, 255})
	}
	if marked {
		cx := int(mx * float64(w-1))
		cy := int(my * float64(h-1))
		draw.Draw(img, image.Rect(cx-8, cy-8, cx+8, cy+8), &image.Uniform{color.RGBA{255, 90, 80, 255}}, image.Point{}, draw.Src)
	}
	if quality <= 0 || quality > 95 {
		quality = 75
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
		return nil, 0, 0, err
	}
	return buf.Bytes(), w, h, nil
}

func drawText(img *image.RGBA, x, y int, text string, col color.RGBA) {
	for _, r := range text {
		glyph, ok := font3x5[r]
		if !ok {
			glyph = font3x5[' ']
			if r == ' ' {
				x += 16
				continue
			}
			glyph = []string{"111", "101", "111", "101", "101"}
		}
		for row, line := range glyph {
			for coln, bit := range line {
				if bit != '1' {
					continue
				}
				px := x + coln*3
				py := y + row*3
				draw.Draw(img, image.Rect(px, py, px+3, py+3), &image.Uniform{col}, image.Point{}, draw.Src)
			}
		}
		x += 16
		if x > img.Bounds().Dx()-16 {
			break
		}
	}
}

// 3x5 glyphs, scaled by 3 when drawn.
var font3x5 = map[rune][]string{
	' ': {"000", "000", "000", "000", "000"},
	'0': {"111", "101", "101", "101", "111"},
	'1': {"010", "110", "010", "010", "111"},
	'2': {"111", "001", "111", "100", "111"},
	'3': {"111", "001", "111", "001", "111"},
	'4': {"101", "101", "111", "001", "001"},
	'5': {"111", "100", "111", "001", "111"},
	'6': {"111", "100", "111", "101", "111"},
	'7': {"111", "001", "001", "001", "001"},
	'8': {"111", "101", "111", "101", "111"},
	'9': {"111", "101", "111", "001", "111"},
	'A': {"111", "101", "111", "101", "101"},
	'B': {"110", "101", "110", "101", "110"},
	'C': {"111", "100", "100", "100", "111"},
	'D': {"110", "101", "101", "101", "110"},
	'E': {"111", "100", "110", "100", "111"},
	'F': {"111", "100", "110", "100", "100"},
	'G': {"111", "100", "101", "101", "111"},
	'H': {"101", "101", "111", "101", "101"},
	'I': {"111", "010", "010", "010", "111"},
	'J': {"001", "001", "001", "101", "111"},
	'K': {"101", "101", "110", "101", "101"},
	'L': {"100", "100", "100", "100", "111"},
	'M': {"101", "111", "101", "101", "101"},
	'N': {"110", "101", "101", "101", "101"},
	'O': {"111", "101", "101", "101", "111"},
	'P': {"111", "101", "111", "100", "100"},
	'Q': {"111", "101", "101", "111", "001"},
	'R': {"110", "101", "110", "101", "101"},
	'S': {"111", "100", "111", "001", "111"},
	'T': {"111", "010", "010", "010", "010"},
	'U': {"101", "101", "101", "101", "111"},
	'V': {"101", "101", "101", "101", "010"},
	'W': {"101", "101", "101", "111", "101"},
	'X': {"101", "101", "010", "101", "101"},
	'Y': {"101", "101", "010", "010", "010"},
	'Z': {"111", "001", "010", "100", "111"},
	':': {"000", "010", "000", "010", "000"},
	'-': {"000", "000", "111", "000", "000"},
	'.': {"000", "000", "000", "000", "010"},
}
