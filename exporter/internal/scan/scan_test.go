package scan

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestSearchPatterns(t *testing.T) {
	patterns := [][]byte{[]byte("AB"), []byte("BC")}
	hits := searchPatterns(patterns, []byte("ABBCAB"), 10)
	got := map[int][]int{}
	for _, h := range hits {
		got[h.PatternIdx] = append(got[h.PatternIdx], h.Offset)
	}
	if len(got[0]) != 2 || got[0][0] != 0 || got[0][1] != 4 {
		t.Fatalf("pattern AB offsets = %v, want [0 4]", got[0])
	}
	if len(got[1]) != 1 || got[1][0] != 2 {
		t.Fatalf("pattern BC offsets = %v, want [2]", got[1])
	}
}

func TestSearchPatternsCap(t *testing.T) {
	patterns := [][]byte{[]byte("A")}
	hits := searchPatterns(patterns, []byte("AAAAAA"), 3)
	if len(hits) != 3 {
		t.Fatalf("got %d hits, want cap 3", len(hits))
	}
}

func TestSearchPatternsBoundaryCarry(t *testing.T) {
	// The Windows reader keeps the (maxLen-1) tail of a chunk and prepends it
	// to the next chunk; with carry "B", chunk "C..." the pattern "BC" must
	// be found at absolute address base-1.
	patterns := [][]byte{[]byte("BC")}
	carry := []byte("B")
	chunk := []byte("CX")
	full := append(append([]byte{}, carry...), chunk...)
	hits := searchPatterns(patterns, full, 10)
	if len(hits) != 1 || hits[0].Offset != 0 {
		t.Fatalf("boundary match not found, hits=%v", hits)
	}
}

func TestCaptureWindow(t *testing.T) {
	data := make([]byte, 300)
	for i := range data {
		data[i] = byte(i)
	}
	patLen := 4

	// match in the middle: before+match+after
	mid := captureWindow(data, 100, patLen, 8, 8)
	if len(mid) != 20 {
		t.Fatalf("mid window len = %d, want 20", len(mid))
	}
	if mid[8] != 100 || mid[8+patLen-1] != 103 { // match bytes 100..103
		t.Fatalf("mid window does not contain the match bytes: %v", mid[8:12])
	}

	// near the start: clamped to 0
	start := captureWindow(data, 2, patLen, 8, 8)
	if len(start) != 2+patLen+8 {
		t.Fatalf("start window len = %d, want %d", len(start), 2+patLen+8)
	}

	// near the end: after-region clamped to len(data)
	end := captureWindow(data, len(data)-8, patLen, 8, 8)
	if len(end) != 8+patLen+8-4 { // after part clamps 8 -> 4
		t.Fatalf("end window len = %d, want %d", len(end), 8+patLen+4)
	}
	if end[len(end)-1] != data[len(data)-1] {
		t.Fatalf("end window does not reach the chunk end: last=%d", end[len(end)-1])
	}

	// match would run past the end of the chunk: nil
	oob := captureWindow(data, len(data)-1, patLen, 8, 8)
	if oob != nil {
		t.Fatalf("out-of-bounds match window = %v, want nil", oob)
	}
}

func TestEffectiveOptionsWindowDefaults(t *testing.T) {
	opts := effectiveOptions(Options{})
	if opts.WindowBefore != DefaultWindowBefore || opts.WindowAfter != DefaultWindowAfter {
		t.Fatalf("window defaults not applied: %+v", opts)
	}
}

func TestParsePatterns(t *testing.T) {
	doc := `{"patterns":[
		{"name":"one","hex":"deadbeef"},
		{"raw":"00 11 aa"},
		{"name":"dup-wcdb","hex":"57434442"}
	]}`
	patterns, err := ParsePatterns([]byte(doc))
	if err != nil {
		t.Fatalf("ParsePatterns: %v", err)
	}
	if len(patterns) != 2 {
		t.Fatalf("got %d patterns, want 2 (dup dropped)", len(patterns))
	}
	if patterns[0].Name != "one" || len(patterns[0].Bytes) != 4 {
		t.Fatalf("bad first pattern: %+v", patterns[0])
	}
	if patterns[1].Name != "pattern-0011aa" || len(patterns[1].Bytes) != 3 {
		t.Fatalf("bad bare pattern: %+v", patterns[1])
	}
}

func TestLoadPatternsFile(t *testing.T) {
	path := filepath.Join("testdata", "patterns.json")
	if _, err := os.Stat(path); err != nil {
		t.Skipf("no testdata fixture: %v", err)
	}
	patterns, err := LoadPatternsFile(path)
	if err != nil {
		t.Fatalf("LoadPatternsFile: %v", err)
	}
	if len(patterns) == 0 || patterns[0].Name != "from-file" {
		t.Fatalf("unexpected patterns: %+v", patterns)
	}
}

func TestDefaultPatternsHaveHex(t *testing.T) {
	for _, p := range DefaultPatterns {
		if len(p.Bytes) == 0 {
			t.Fatalf("pattern %s has empty bytes", p.Name)
		}
		if _, err := json.Marshal(p); err != nil {
			t.Fatalf("pattern %s not JSON-serializable: %v", p.Name, err)
		}
	}
	if maxPatternLen(DefaultPatterns) < 4 {
		t.Fatal("maxPatternLen too small")
	}
}

func TestEffectiveOptions(t *testing.T) {
	o := effectiveOptions(Options{})
	if len(o.Processes) == 0 || len(o.Patterns) == 0 {
		t.Fatal("defaults not applied")
	}
	if o.MaxHits <= 0 || o.MaxBytes <= 0 {
		t.Fatal("default caps not applied")
	}
}
