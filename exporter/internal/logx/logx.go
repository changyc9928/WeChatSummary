package logx

import (
	"fmt"
	"io"
	"os"
	"sync"
	"time"
)

var (
	mu  sync.Mutex
	out io.Writer = os.Stderr
)

func SetOutput(w io.Writer) { mu.Lock(); out = w; mu.Unlock() }

func printf(level, format string, args ...any) {
	mu.Lock()
	defer mu.Unlock()
	fmt.Fprintf(out, "%s [%s] %s\n", time.Now().Format("15:04:05.000"), level, fmt.Sprintf(format, args...))
}

func Info(format string, args ...any)  { printf("INFO ", format, args...) }
func Warn(format string, args ...any)  { printf("WARN ", format, args...) }
func Error(format string, args ...any) { printf("ERROR", format, args...) }
func Debug(format string, args ...any) { printf("DEBUG", format, args...) }
