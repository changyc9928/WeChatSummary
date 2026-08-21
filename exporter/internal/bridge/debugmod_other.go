//go:build !windows

package bridge

import "net/http"

// handleDebugModule is unavailable off Windows: module enumeration and memory
// reads only exist in the Windows engine.
func (s *server) handleDebugModule(w http.ResponseWriter, _ *http.Request) {
	http.Error(w, "module dumps require the Windows bridge", http.StatusNotImplemented)
}
