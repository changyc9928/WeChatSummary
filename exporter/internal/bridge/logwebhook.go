package bridge

import (
	"bytes"
	"encoding/json"
	"net/http"
	"sync"
	"time"
)

// webhookSender forwards bridge log lines to a remote collector (the web
// backend's POST /api/tools/bridge/logs) as batched JSON. It is fully
// non-blocking: Add() only enqueues into a buffered channel, and a single
// background goroutine flushes batches on a timer or when the batch is full.
// Lines are dropped (never retried) when the channel is full or the POST
// fails, because logging must never stall the export pipeline.
type webhookSender struct {
	mu   sync.RWMutex
	url  string
	ch   chan logLine
	done chan struct{}
}

var logWebhook = newWebhookSender()

func newWebhookSender() *webhookSender {
	w := &webhookSender{
		ch:   make(chan logLine, 512),
		done: make(chan struct{}),
	}
	go w.loop()
	return w
}

// SetLogWebhook enables (or, with an empty url, disables) forwarding of every
// bridge log line to url as batched JSON POSTs ({"lines":[{seq,ts,level,msg}]}).
func SetLogWebhook(url string) {
	logWebhook.mu.Lock()
	logWebhook.url = url
	logWebhook.mu.Unlock()
}

// LogWebhookURL returns the currently configured webhook URL ("" = disabled).
// The runtime endpoint POST /api/log/webhook lets a caller (the web frontend)
// enable forwarding on an already-running bridge, so the --log-webhook flag is
// no longer required.
func LogWebhookURL() string {
	return logWebhook.currentURL()
}

func (w *webhookSender) currentURL() string {
	w.mu.RLock()
	defer w.mu.RUnlock()
	return w.url
}

// enqueue hands one line to the batcher without blocking the caller.
func (w *webhookSender) enqueue(line logLine) {
	select {
	case w.ch <- line:
	default: // channel full: drop rather than block the export
	}
}

func (w *webhookSender) loop() {
	client := &http.Client{Timeout: 3 * time.Second}
	tick := time.NewTicker(500 * time.Millisecond)
	defer tick.Stop()
	var batch []logLine
	flush := func() {
		if len(batch) == 0 {
			return
		}
		url := w.currentURL()
		if url == "" {
			batch = nil
			return
		}
		payload, err := json.Marshal(map[string]any{"lines": batch})
		batch = nil
		if err != nil {
			return
		}
		req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := client.Do(req)
		if err == nil {
			resp.Body.Close()
		}
		// On failure the batch is dropped: logs are best-effort diagnostics.
	}
	for {
		select {
		case line := <-w.ch:
			batch = append(batch, line)
			if len(batch) >= 100 {
				flush()
			}
		case <-tick.C:
			flush()
		case <-w.done:
			return
		}
	}
}
