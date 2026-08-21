//go:build windows

package scan

import (
	"encoding/hex"
	"fmt"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

// Windows process-memory scanner. It walks the address space of every
// WeChat-family process, reads committed readable regions in chunks and
// searches for the configured byte patterns. All Win32 entry points are
// resolved lazily so the binary has no import-time dependencies beyond
// kernel32, and the code compiles cleanly when cross-built with
// GOOS=windows from any host.
//
// Notes:
//   - The MEMORY_BASIC_INFORMATION layout below is the x64 layout (includes
//     PartitionId); WeChat is 64-bit, so the bridge should be built with
//     GOARCH=amd64.
//   - Scanning requires the same user (or admin); if the target process is
//     elevated, OpenProcess fails with access denied and the error is
//     reported in Result.Errors instead of aborting the scan.

const (
	th32csSnapProcess  = 0x00000002
	th32csSnapModule   = 0x00000008
	processQueryInfo   = 0x00000400
	processVMRead      = 0x00000010
	memCommit          = 0x00001000
	pageNoAccess       = 0x00000001
	pageReadOnly       = 0x00000002
	pageReadWrite      = 0x00000004
	pageWriteCopy      = 0x00000008
	pageExecuteRead    = 0x00000020
	pageExecuteRw      = 0x00000040
	pageExecuteWc      = 0x00000080
	pageGuard          = 0x00000100
	invalidHandleValue = ^uintptr(0)

	readChunkSize = 64 * 1024
	// maxErrors caps Result.Errors so a noisy process cannot balloon the
	// response (one error per unreadable region is typical).
	maxErrors = 50
)

var (
	kernel32              = syscall.NewLazyDLL("kernel32.dll")
	procOpenProcess       = kernel32.NewProc("OpenProcess")
	procCreateSnap        = kernel32.NewProc("CreateToolhelp32Snapshot")
	procProcess32First    = kernel32.NewProc("Process32FirstW")
	procProcess32Next     = kernel32.NewProc("Process32NextW")
	procModule32First     = kernel32.NewProc("Module32FirstW")
	procModule32Next      = kernel32.NewProc("Module32NextW")
	procVirtualQueryEx    = kernel32.NewProc("VirtualQueryEx")
	procReadProcessMemory = kernel32.NewProc("ReadProcessMemory")
	procCloseHandle       = kernel32.NewProc("CloseHandle")
	procGetLastError      = kernel32.NewProc("GetLastError")

	processEntry32Size  = uintptr(unsafe.Sizeof(processEntry32{}))
	moduleEntry32Size   = uintptr(unsafe.Sizeof(moduleEntry32{}))
	memoryBasicInfoSize = uintptr(unsafe.Sizeof(memoryBasicInformation{}))
)

// processEntry32 mirrors PROCESSENTRY32W (x64).
type processEntry32 struct {
	Size            uint32
	Usage           uint32
	ProcessID       uint32
	DefaultHeapID   uintptr
	ModuleID        uint32
	Threads         uint32
	ParentProcessID uint32
	PriClassBase    int32
	Flags           uint32
	ExeFile         [260]uint16
}

// moduleEntry32 mirrors MODULEENTRY32W (x64).
type moduleEntry32 struct {
	Size         uint32
	ModuleID     uint32
	ProcessID    uint32
	GlobalUsage  uint32
	ProccntUsage uint32
	ModBaseAddr  uintptr
	ModBaseSize  uint32
	HModule      uintptr
	SzModule     [256]uint16
	SzExePath    [260]uint16
}

// ModuleInfo describes one loaded module of a target process.
type ModuleInfo struct {
	PID   uint32  `json:"pid"`
	Name  string  `json:"name"`
	Base  uintptr `json:"base"`
	Size  uint32  `json:"size"`
	Path  string  `json:"path"`
	Found bool    `json:"found"`
	Err   string  `json:"error,omitempty"`
}

// FindModule returns the first module of pid whose name contains the
// case-insensitive substring nameSub (e.g. "weixin.dll"). The toolhelp module
// snapshot must be taken against the process; a zero PID or a process the
// bridge has no permission to snapshot yields an error in ModuleInfo.Err.
func FindModule(pid uint32, nameSub string) ModuleInfo {
	mi := ModuleInfo{PID: pid}
	if pid == 0 {
		mi.Err = "pid 0"
		return mi
	}
	snap, _, _ := procCreateSnap.Call(th32csSnapModule, uintptr(pid))
	if snap == invalidHandleValue {
		mi.Err = "CreateToolhelp32Snapshot(module) failed"
		return mi
	}
	defer procCloseHandle.Call(snap)

	var e moduleEntry32
	e.Size = uint32(moduleEntry32Size)
	ok, _, _ := procModule32First.Call(snap, uintptr(unsafe.Pointer(&e)))
	for ok != 0 {
		name := syscall.UTF16ToString(e.SzModule[:])
		if strings.Contains(strings.ToLower(name), strings.ToLower(nameSub)) {
			mi.Name = name
			mi.Base = e.ModBaseAddr
			mi.Size = e.ModBaseSize
			mi.Path = syscall.UTF16ToString(e.SzExePath[:])
			mi.Found = true
			break
		}
		ok, _, _ = procModule32Next.Call(snap, uintptr(unsafe.Pointer(&e)))
	}
	if !mi.Found {
		mi.Err = "module not found"
	}
	return mi
}

// ReadMemory reads up to n bytes at addr from pid and returns the bytes
// actually read (which may be fewer than n when a region ends or a read is
// partial). It is used by the bridge to read a single module or a set of
// known-good absolute addresses.
func ReadMemory(pid uint32, addr uintptr, n int) ([]byte, error) {
	if n <= 0 {
		return nil, nil
	}
	h, err := openProcess(pid)
	if err != nil {
		return nil, err
	}
	defer procCloseHandle.Call(uintptr(h))

	out := make([]byte, 0, n)
	for len(out) < n {
		chunk := n - len(out)
		if chunk > 64*1024 {
			chunk = 64 * 1024
		}
		buf := make([]byte, chunk)
		got, rerr := readProcessMemory(h, addr+uintptr(len(out)), buf)
		if got > 0 {
			out = append(out, buf[:got]...)
		}
		if rerr != nil || got == 0 {
			break
		}
	}
	return out, nil
}

// memoryBasicInformation mirrors MEMORY_BASIC_INFORMATION (x64 layout).
type memoryBasicInformation struct {
	BaseAddress       uintptr
	AllocationBase    uintptr
	AllocationProtect uint32
	PartitionID       uint16
	RegionSize        uintptr
	State             uint32
	Protect           uint32
	Type              uint32
}

// Scan runs the Windows memory scan. See Options.
func Scan(opts Options) (*Result, error) {
	start := time.Now()
	opts = effectiveOptions(opts)
	res := &Result{}

	procs, err := findProcesses(opts.Processes)
	if err != nil {
		return nil, err
	}
	res.Processes = procs
	if len(procs) == 0 {
		res.DurationMs = time.Since(start).Milliseconds()
		return res, nil
	}

	patBytes := make([][]byte, len(opts.Patterns))
	for i, p := range opts.Patterns {
		patBytes[i] = p.Bytes
	}

	for _, pi := range procs {
		h, oerr := openProcess(pi.PID)
		if oerr != nil {
			addError(res, fmt.Sprintf("%s (pid %d): open failed: %v", pi.Name, pi.PID, oerr))
			continue
		}
		perProcess := make(map[int]int, len(opts.Patterns))
		scanProcess(h, pi, opts, patBytes, res, perProcess)
		procCloseHandle.Call(uintptr(h))
	}

	res.DurationMs = time.Since(start).Milliseconds()
	return res, nil
}

// findProcesses enumerates running processes and returns those whose image
// name contains any of the case-insensitive target substrings.
func findProcesses(targets []string) ([]ProcessInfo, error) {
	snap, _, _ := procCreateSnap.Call(th32csSnapProcess, 0)
	if snap == invalidHandleValue {
		return nil, fmt.Errorf("scan: CreateToolhelp32Snapshot failed")
	}
	defer procCloseHandle.Call(snap)

	var out []ProcessInfo
	var e processEntry32
	e.Size = uint32(processEntry32Size)
	ok, _, _ := procProcess32First.Call(snap, uintptr(unsafe.Pointer(&e)))
	for ok != 0 {
		name := syscall.UTF16ToString(e.ExeFile[:])
		for _, t := range targets {
			if strings.Contains(strings.ToLower(name), strings.ToLower(t)) {
				out = append(out, ProcessInfo{PID: e.ProcessID, Name: name})
				break
			}
		}
		ok, _, _ = procProcess32Next.Call(snap, uintptr(unsafe.Pointer(&e)))
	}
	return out, nil
}

func openProcess(pid uint32) (syscall.Handle, error) {
	r, _, _ := procOpenProcess.Call(processQueryInfo|processVMRead, 0, uintptr(pid))
	h := syscall.Handle(r)
	if h == 0 {
		return 0, lastError("OpenProcess")
	}
	return h, nil
}

// scanProcess walks the address space of one process and searches the
// readable committed regions for the patterns.
func scanProcess(h syscall.Handle, pi ProcessInfo, opts Options, patBytes [][]byte, res *Result, perProcess map[int]int) {
	var addr uintptr
	remaining := opts.MaxBytes

	for {
		var mbi memoryBasicInformation
		n, err := virtualQueryEx(h, addr, &mbi)
		if err != nil || n == 0 || mbi.RegionSize == 0 {
			break
		}
		if mbi.State == memCommit && isReadable(mbi.Protect) && remaining > 0 {
			region := mbi.RegionSize
			if region > uintptr(remaining) {
				region = uintptr(remaining)
			}
			res.RegionsScanned++
			read := scanRegion(h, pi, mbi.BaseAddress, int64(region), opts, patBytes, res, perProcess)
			res.BytesScanned += read
			remaining -= read
		}

		next := mbi.BaseAddress + mbi.RegionSize
		if next <= addr || next < mbi.BaseAddress {
			break // overflow / no progress: stop walking
		}
		addr = next
		if remaining <= 0 {
			break
		}
	}
}

func isReadable(protect uint32) bool {
	if protect&pageGuard != 0 || protect&pageNoAccess != 0 {
		return false
	}
	switch protect & 0xFF {
	case pageReadOnly, pageReadWrite, pageWriteCopy,
		pageExecuteRead, pageExecuteRw, pageExecuteWc:
		return true
	}
	return false
}

// scanRegion reads a region in chunks, carrying the pattern tail across chunk
// boundaries, and records matches. Returns bytes actually read.
func scanRegion(h syscall.Handle, pi ProcessInfo, base uintptr, size int64, opts Options, patBytes [][]byte, res *Result, perProcess map[int]int) int64 {
	var read int64
	carryLen := maxPatternLen(opts.Patterns) - 1
	var carry []byte

	for off := int64(0); off < size; {
		chunk := int64(readChunkSize)
		if chunk > size-off {
			chunk = size - off
		}
		buf := make([]byte, chunk)
		n, rerr := readProcessMemory(h, base+uintptr(off), buf)
		if n > 0 {
			read += int64(n)
			haystack := buf[:n]
			absBase := base + uintptr(off) - uintptr(len(carry))
			if len(carry) > 0 {
				full := make([]byte, 0, len(carry)+n)
				full = append(full, carry...)
				full = append(full, haystack...)
				recordMatches(full, absBase, opts, patBytes, res, pi, perProcess)
			} else {
				recordMatches(haystack, absBase, opts, patBytes, res, pi, perProcess)
			}
			keep := carryLen
			if keep > n {
				keep = n
			}
			carry = append(carry[:0], haystack[n-keep:]...)
		}
		if rerr != nil {
			// A region that was committed/readable at query time may fail on
			// read (guard pages, race with unmap). Partial progress is kept;
			// a full failure ends this region only.
			if n == 0 {
				addError(res, fmt.Sprintf("%s (pid %d): read @0x%x failed: %v", pi.Name, pi.PID, base+uintptr(off), rerr))
				break
			}
			addError(res, fmt.Sprintf("%s (pid %d): partial read @0x%x: %v", pi.Name, pi.PID, base+uintptr(off), rerr))
		}
		if n == 0 {
			break
		}
		off += int64(n)
	}
	return read
}

// recordMatches converts raw matches into Hits. chunkStartAbs is the absolute
// address of chunkData[0] (already adjusted for any carry prefix).
func recordMatches(chunkData []byte, chunkStartAbs uintptr, opts Options, patBytes [][]byte, res *Result, pi ProcessInfo, perProcess map[int]int) {
	matches := searchPatterns(patBytes, chunkData, opts.MaxHits)
	for _, m := range matches {
		if perProcess[m.PatternIdx] >= opts.MaxHits {
			continue
		}
		perProcess[m.PatternIdx]++
		pat := opts.Patterns[m.PatternIdx]
		matched := chunkData[m.Offset : m.Offset+len(pat.Bytes)]
		ctxEnd := m.Offset + 64
		if ctxEnd > len(chunkData) {
			ctxEnd = len(chunkData)
		}
		ctxBytes := chunkData[m.Offset:ctxEnd]
		res.Hits = append(res.Hits, Hit{
			PID:        pi.PID,
			Process:    pi.Name,
			Pattern:    pat.Name,
			Address:    uint64(chunkStartAbs + uintptr(m.Offset)),
			MatchedHex: hex.EncodeToString(matched),
			ContextHex: hex.EncodeToString(ctxBytes),
			Context:    printable(ctxBytes, 48),
			WindowHex:  hex.EncodeToString(captureWindow(chunkData, m.Offset, len(pat.Bytes), opts.WindowBefore, opts.WindowAfter)),
		})
	}
}

func printable(b []byte, max int) string {
	var sb strings.Builder
	if len(b) > max {
		b = b[:max]
	}
	for _, c := range b {
		if c >= 0x20 && c < 0x7f {
			sb.WriteByte(c)
		} else {
			sb.WriteByte('.')
		}
	}
	return sb.String()
}

func virtualQueryEx(h syscall.Handle, addr uintptr, mbi *memoryBasicInformation) (int, error) {
	r, _, err := procVirtualQueryEx.Call(uintptr(h), addr, uintptr(unsafe.Pointer(mbi)), memoryBasicInfoSize)
	if r == 0 {
		return 0, err
	}
	return int(r), nil
}

func readProcessMemory(h syscall.Handle, addr uintptr, buf []byte) (int, error) {
	var read uintptr
	r, _, err := procReadProcessMemory.Call(uintptr(h), addr, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)), uintptr(unsafe.Pointer(&read)))
	if r == 0 {
		return int(read), err
	}
	return int(read), nil
}

func lastError(api string) error {
	code, _, _ := procGetLastError.Call()
	if code == 0 {
		return fmt.Errorf("%s failed", api)
	}
	return fmt.Errorf("%s failed: %s (code 0x%x)", api, syscall.Errno(code).Error(), code)
}

func addError(res *Result, msg string) {
	if len(res.Errors) < maxErrors {
		res.Errors = append(res.Errors, msg)
	}
}
