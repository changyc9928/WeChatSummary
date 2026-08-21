// Command bridge is the local sidecar for WeChatSummary's browser frontend.
//
// It binds an HTTP API to 127.0.0.1 only, exposing three workflows that the
// frontend cannot do itself from the browser sandbox:
//
//	GET  /api/health        liveness + platform capability
//	GET  /api/dbs           discover WeChat MSG.db files on this machine
//	GET  /api/scan          scan WeChat process memory for key material
//	                          (Windows builds only)
//	POST /api/key/validate  verify a key/passphrase against a database
//	                          (cryptographic HMAC check)
//
// Build (Windows target, run on the user's machine):
//
//	cd exporter
//	GOOS=windows GOARCH=amd64 go build -o bridge.exe ./cmd/bridge
//	bridge.exe --token <optional> --allow-origins http://localhost:5173
//
// Security: the server refuses to bind a non-loopback address, CORS is
// allowlisted, and an optional bearer token gates every request.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"syscall"
	"time"

	"wechatsummary/exporter/internal/bridge"
	"wechatsummary/exporter/internal/logx"
	"wechatsummary/exporter/internal/scan"
)

func main() {
	var (
		addr         = flag.String("addr", "127.0.0.1", "listen address (loopback only)")
		port         = flag.Int("port", bridge.DefaultPort, "listen port")
		token        = flag.String("token", "", "require this bearer token on every request (empty = no auth)")
		allowOrigins = flag.String("allow-origins",
			"http://localhost:5173,http://127.0.0.1:5173,http://localhost:3001,http://127.0.0.1:3001",
			"comma-separated CORS allowlist (the frontend origin)")
		corsAny = flag.Bool("cors-any", false,
			"trust any page origin (loopback-only service; use when the frontend is on a LAN IP/hostname)")
		patternsFile = flag.String("patterns", "", "JSON file with extra memory-scan patterns")
		dbRoots      = flag.String("db-roots", "", "comma-separated roots for WeChat DB discovery (default: user Documents/Desktop)")
		keyToolPath  = flag.String("keytool-path", "", "path to CipherTalk's wechat_key_tool.dll (WeChat 4.1.x key recovery via /api/key/tool)")
		mediaXorKey  = flag.String("media-xor-key", "", "default media XOR key (hex, e.g. 73) for .dat decryption")
		mediaAesKey  = flag.String("media-aes-key", "", "default media AES key (16 ASCII chars) for V2 .dat decryption")
		logLevel     = flag.String("log-level", "info", "minimum log level stored in the bridge log ring (debug|info|warn|error). debug adds scan progress to the frontend log panel")
		exportDir    = flag.String("export-dir", "", "directory for large export ZIPs (default: the bridge's working directory)")
		logWebhook   = flag.String("log-webhook", "", "POST every bridge log line to this URL as batched JSON (e.g. http://192.168.0.216:8080/api/tools/bridge/logs); non-blocking, best-effort")
	)
	flag.Parse()

	if *logWebhook != "" {
		bridge.SetLogWebhook(*logWebhook)
		logx.Info("log webhook enabled -> %s", *logWebhook)
	}

	// Loopback enforcement: this service reads another process's memory and
	// should never be reachable off the machine.
	ip := net.ParseIP(*addr)
	if ip == nil || !ip.IsLoopback() {
		logx.Error("refusing to bind non-loopback address %q (memory scan service)", *addr)
		os.Exit(1)
	}
	if *port < 1 || *port > 65535 {
		logx.Error("invalid port %d", *port)
		os.Exit(1)
	}

	var extra []scan.Pattern
	if *patternsFile != "" {
		patterns, err := scan.LoadPatternsFile(*patternsFile)
		if err != nil {
			logx.Error("load patterns: %v", err)
			os.Exit(1)
		}
		extra = patterns
		logx.Info("loaded %d extra pattern(s) from %s", len(extra), *patternsFile)
	}

	if *corsAny {
		logx.Warn("--cors-any is set: any webpage open on this machine can read scan/validate results. Prefer --allow-origins with the exact frontend origin.")
	}

	cfg := bridge.Config{
		AllowOrigins:   splitCSV(*allowOrigins),
		AllowAnyOrigin: *corsAny,
		Token:          *token,
		DBRoots:        splitCSV(*dbRoots),
		ExtraPatterns:  extra,
		KeyToolPath:    *keyToolPath,
		MediaXorKey:    *mediaXorKey,
		MediaAesKey:    *mediaAesKey,
		LogLevel:       *logLevel,
		ExportDir:      *exportDir,
	}

	handler := bridge.NewServer(cfg)
	srv := &http.Server{
		Addr:              fmt.Sprintf("%s:%d", *addr, *port),
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		logx.Info("wechat-key-bridge v%s listening on http://%s:%d (log level: %s, scan supported: %v, auth: %v)",
			bridge.Version, *addr, *port, *logLevel, runtime.GOOS == "windows", *token != "")
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logx.Error("server error: %v", err)
			stop()
		}
	}()

	<-ctx.Done()
	logx.Info("shutting down…")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}

func splitCSV(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}
