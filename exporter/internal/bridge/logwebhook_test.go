package bridge

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// TestLogWebhookBatches verifies that log lines enqueued while a webhook URL
// is set are delivered as batched JSON POSTs, and that the sink never blocks
// the caller even when the endpoint is down.
func TestLogWebhookBatches(t *testing.T) {
	var (
		got       atomic.Int64
		lastBatch atomic.Value
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Lines []logLine `json:"lines"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		got.Add(int64(len(body.Lines)))
		lastBatch.Store(body.Lines)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	SetLogWebhook(srv.URL)
	defer SetLogWebhook("")

	// Fire a batch of lines; Add() must never block even while the batcher
	// is mid-flush.
	for i := 0; i < 25; i++ {
		bridgeLog.Add("info", "line %d", i)
	}
	waitFor(t, 3*time.Second, func() bool { return got.Load() >= 25 })

	b, ok := lastBatch.Load().([]logLine)
	if !ok || len(b) == 0 {
		t.Fatalf("no batch captured: %#v", lastBatch.Load())
	}
	// The global bridgeLog ring is shared with other tests in this package, so
	// filter to the lines this test enqueued and check they arrive in order.
	var mine []string
	for _, l := range b {
		if len(l.Msg) >= 5 && l.Msg[:5] == "line " {
			mine = append(mine, l.Msg)
		}
	}
	if len(mine) != 25 {
		t.Fatalf("want 25 of our lines, got %d: %v", len(mine), mine)
	}
	if mine[0] != "line 0" || mine[24] != "line 24" {
		t.Fatalf("lines out of order: first=%q last=%q", mine[0], mine[24])
	}
}

// TestLogWebhookNoBlockOnFailure: with an unreachable endpoint the enqueue
// path still returns immediately (the sender drops batches).
func TestLogWebhookNoBlockOnFailure(t *testing.T) {
	SetLogWebhook("http://127.0.0.1:1/unreachable")
	defer SetLogWebhook("")
	done := make(chan struct{})
	go func() {
		for i := 0; i < 20; i++ {
			bridgeLog.Add("info", "drop %d", i)
		}
		close(done)
	}()
	select {
	case <-done:
		// ok: never blocked
	case <-time.After(2 * time.Second):
		t.Fatal("Add() blocked on an unreachable webhook")
	}
}

func waitFor(t *testing.T, d time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("condition not met within", d)
}
