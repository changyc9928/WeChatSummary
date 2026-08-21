package bridge

import "testing"

func TestLogBufferLevelGating(t *testing.T) {
	b := newLogBuffer(100)
	// default min level = info
	b.Add("debug", "hidden %d", 1)
	b.Add("info", "seen %d", 2)
	b.Add("warn", "seen %d", 3)
	lines, next := b.Since(0)
	if len(lines) != 2 {
		t.Fatalf("default: want 2 stored lines (info+warn), got %d: %+v", len(lines), lines)
	}
	for _, l := range lines {
		if l.Level == "debug" {
			t.Fatalf("debug line leaked through at default level: %+v", l)
		}
	}
	if next != 2 {
		t.Fatalf("default: seq cursor = %d, want 2 (dropped lines consume no seq)", next)
	}

	// raise verbosity: debug now stored, cursor continues seamlessly
	b.SetMinLevel("debug")
	b.Add("debug", "now visible %d", 4)
	lines, next = b.Since(2)
	if len(lines) != 1 || lines[0].Level != "debug" || lines[0].Seq != 3 {
		t.Fatalf("debug: got %+v (next=%d), want one debug line seq=3", lines, next)
	}
	if next != 3 {
		t.Fatalf("debug: next = %d, want 3", next)
	}

	// error/warn always pass
	b.SetMinLevel("error")
	b.Add("debug", "hidden2")
	b.Add("error", "boom")
	lines, _ = b.Since(3)
	if len(lines) != 1 || lines[0].Level != "error" {
		t.Fatalf("error: got %+v", lines)
	}
}
