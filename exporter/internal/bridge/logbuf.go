package bridge

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// logLine is one buffered bridge log entry. Seq is a per-process monotonic
// counter so the frontend can poll incrementally (/api/logs?after=N).
type logLine struct {
	Seq   int    `json:"seq"`
	Ts    string `json:"ts"`
	Level string `json:"level"`
	Msg   string `json:"msg"`
}

// logBuffer is a small, thread-safe ring buffer of recent bridge log lines,
// mirroring everything to stdout so `bridge.exe` console stays informative.
type logBuffer struct {
	mu       sync.Mutex
	lines    []logLine
	seq      int
	cap      int
	minLevel string // debug|info|warn|error; lines below this are not stored
}

// levelRank orders the log levels for the minimum-level filter.
var levelRank = map[string]int{"debug": 0, "info": 1, "warn": 2, "error": 3}

// newLogBuffer returns a log ring holding up to cap lines.
func newLogBuffer(cap int) *logBuffer {
	if cap <= 0 {
		cap = 500
	}
	return &logBuffer{cap: cap, minLevel: "info"}
}

// MinLevel reports the current minimum level (debug|info|warn|error).
func (b *logBuffer) MinLevel() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.minLevel
}

// SetMinLevel sets the lowest level that is stored (and mirrored to stdout).
// Lines below it are dropped entirely; a --log-level debug bridge therefore
// shows DEBUG lines in the panel, the default does not.
func (b *logBuffer) SetMinLevel(level string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if _, ok := levelRank[level]; ok {
		b.minLevel = level
	}
}

// Add appends one line at level; evicts the oldest when full. Lines below the
// configured minimum are dropped without consuming a sequence number, so the
// frontend's incremental cursor stays aligned with what it can actually see.
func (b *logBuffer) Add(level, format string, args ...any) {
	b.mu.Lock()
	if levelRank[level] < levelRank[b.minLevel] {
		b.mu.Unlock()
		return
	}
	b.seq++
	line := logLine{
		Seq:   b.seq,
		Ts:    time.Now().Format("2006-01-02 15:04:05.000"),
		Level: level,
		Msg:   fmt.Sprintf(format, args...),
	}
	b.lines = append(b.lines, line)
	if len(b.lines) > b.cap {
		b.lines = b.lines[len(b.lines)-b.cap:]
	}
	b.mu.Unlock()
	fmt.Printf("[%s] %s: %s\n", line.Level, line.Ts, line.Msg)
	logWebhook.enqueue(line)
}

// Since returns lines with Seq > after (in order) plus the next Seq cursor.
func (b *logBuffer) Since(after int) ([]logLine, int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := b.lines
	if after > 0 {
		i := sort.Search(len(out), func(i int) bool { return out[i].Seq > after })
		out = out[i:]
	}
	next := b.seq
	if len(out) > 0 {
		next = out[len(out)-1].Seq
	}
	return out, next
}

// log handler for the bridge server.
var bridgeLog = newLogBuffer(1000)

// bridgeLogDebug adds a DEBUG line (shown in the frontend panel only when the
// bridge runs with --log-level debug). Scan progress and per-candidate detail
// go here so the default INFO view stays readable.
func bridgeLogDebug(format string, args ...any) {
	bridgeLog.Add("debug", format, args...)
}

// logsRequest is the query for incremental log reads.
type logsResponse struct {
	Lines []logLine `json:"lines"`
	Next  int       `json:"next"`
}

// handleLogs returns log lines since ?after=N (GET). A tiny read path useful
// for the frontend "bridge logs" panel and for debugging from this machine.
func (s *server) handleLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use GET")
		return
	}
	after := 0
	if v := r.URL.Query().Get("after"); v != "" {
		fmt.Sscanf(v, "%d", &after)
	}
	lines, next := bridgeLog.Since(after)
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: logsResponse{Lines: lines, Next: next}})
}

// logWebhookRequest is the body of POST /api/log/webhook: the URL to forward
// bridge log lines to ("" disables). Lets the frontend enable the server-log
// mirror on an already-running bridge without the --log-webhook flag.
type logWebhookRequest struct {
	URL string `json:"url"`
}

// handleLogWebhook sets the log-forwarding webhook at runtime (POST) or
// reports the current status (GET). This is the automation that makes the
// server-log panel work even when bridge.exe was started without
// --log-webhook.
func (s *server) handleLogWebhook(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
			"url":     LogWebhookURL(),
			"enabled": LogWebhookURL() != "",
		}})
	case http.MethodPost:
		body, err := io.ReadAll(io.LimitReader(r.Body, 1<<16))
		if err != nil {
			s.fail(w, http.StatusBadRequest, "bad_request", "cannot read body: %v", err)
			return
		}
		var req logWebhookRequest
		if err := json.Unmarshal(body, &req); err != nil {
			s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
			return
		}
		req.URL = strings.TrimSpace(req.URL)
		SetLogWebhook(req.URL)
		if req.URL != "" {
			bridgeLog.Add("info", "log webhook enabled via runtime endpoint -> %s", req.URL)
		} else {
			bridgeLog.Add("info", "log webhook disabled via runtime endpoint")
		}
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
			"url":     req.URL,
			"enabled": req.URL != "",
		}})
	default:
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use GET or POST")
	}
}
