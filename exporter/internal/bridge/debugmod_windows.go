//go:build windows

package bridge

import (
	"encoding/hex"
	"fmt"
	"net/http"
	"strconv"

	"wechatsummary/exporter/internal/scan"
)

// handleDebugModule dumps a hex slice of a loaded module's image:
// GET /api/debug/module?pid=17364&name=weixin.dll&from=0x331b87&len=0x200
// Used to pull the bytes around the discovered key functions for local
// disassembly when the resident mem_data address still eludes the probe scan.
func (s *server) handleDebugModule(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	pid64, err := strconv.ParseUint(q.Get("pid"), 0, 32)
	if err != nil {
		http.Error(w, "bad pid", http.StatusBadRequest)
		return
	}
	name := q.Get("name")
	from64, err1 := strconv.ParseUint(q.Get("from"), 0, 64)
	len64, err2 := strconv.ParseUint(q.Get("len"), 0, 64)
	if err1 != nil || err2 != nil || len64 == 0 || len64 > 2<<20 {
		http.Error(w, "bad from/len", http.StatusBadRequest)
		return
	}
	mi := scan.FindModule(uint32(pid64), name)
	if !mi.Found || mi.Base == 0 {
		http.Error(w, "module not found", http.StatusNotFound)
		return
	}
	if uintptr(from64)+uintptr(len64) > uintptr(mi.Size) {
		http.Error(w, "range exceeds module size", http.StatusBadRequest)
		return
	}
	b, rerr := scan.ReadMemory(uint32(pid64), mi.Base+uintptr(from64), int(len64))
	if rerr != nil || len(b) == 0 {
		http.Error(w, fmt.Sprintf("read failed: %v", rerr), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/plain")
	fmt.Fprintf(w, "module=%s pid=%d base=0x%x from=0x%x len=%d\n%s",
		mi.Path, pid64, mi.Base, from64, len(b), hex.EncodeToString(b))
}
