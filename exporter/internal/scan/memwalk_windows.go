//go:build windows

package scan

import (
	"fmt"
	"syscall"
)

// ForEachReadableRegion walks the address space of pid, reading readable
// committed regions in chunks (same walk as Scan), and invokes fn for every
// chunk with its absolute address. fn may return an error to stop early
// (ErrStopScanning aborts cleanly). Returns bytes actually read and capped
// region read errors.
//
// Unlike Scan it does not search for patterns: it hands the raw memory to the
// caller, which is what key-recovery scans need (probe-driven candidate search
// cannot be expressed as static byte patterns).
func ForEachReadableRegion(pid uint32, maxBytes int64, fn func(addr uintptr, data []byte) error) (int64, []error) {
	var total int64
	var errs []error
	h, oerr := openProcess(pid)
	if oerr != nil {
		return 0, []error{oerr}
	}
	defer procCloseHandle.Call(uintptr(h))

	stop := false
	var addr uintptr
	for {
		var mbi memoryBasicInformation
		n, err := virtualQueryEx(h, addr, &mbi)
		if err != nil || n == 0 || mbi.RegionSize == 0 {
			break
		}
		if mbi.State == memCommit && isReadable(mbi.Protect) && total < maxBytes {
			region := mbi.RegionSize
			if uintptr(maxBytes-total) < region {
				region = uintptr(maxBytes - total)
			}
			read := walkRegion(h, mbi.BaseAddress, int64(region), fn, &stop, &errs)
			total += read
			if stop {
				break
			}
		}
		next := mbi.BaseAddress + mbi.RegionSize
		if next <= addr || next < mbi.BaseAddress {
			break // overflow / no progress
		}
		addr = next
		if total >= maxBytes {
			break
		}
	}
	return total, errs
}

// walkRegion reads one region in chunks and calls fn per chunk. Region read
// errors are collected (capped) without aborting the walk.
func walkRegion(h syscall.Handle, base uintptr, size int64, fn func(addr uintptr, data []byte) error, stop *bool, errs *[]error) int64 {
	var read int64
	for off := int64(0); off < size && !*stop; {
		chunk := int64(readChunkSize)
		if chunk > size-off {
			chunk = size - off
		}
		buf := make([]byte, chunk)
		n, rerr := readProcessMemory(h, base+uintptr(off), buf)
		if n > 0 {
			read += int64(n)
			if err := fn(base+uintptr(off), buf[:n]); err != nil {
				if err == ErrStopScanning {
					*stop = true
				} else if len(*errs) < maxErrors {
					*errs = append(*errs, fmt.Errorf("callback @0x%x: %v", base+uintptr(off), err))
				}
			}
		}
		if rerr != nil {
			if n == 0 {
				if len(*errs) < maxErrors {
					*errs = append(*errs, fmt.Errorf("read @0x%x failed: %v", base+uintptr(off), rerr))
				}
				break
			}
			if len(*errs) < maxErrors {
				*errs = append(*errs, fmt.Errorf("partial read @0x%x: %v", base+uintptr(off), rerr))
			}
		}
		if n == 0 {
			break
		}
		off += int64(n)
	}
	return read
}

// FindWeChatProcesses returns the running WeChat-family processes (the same
// default target list Scan uses).
func FindWeChatProcesses() ([]ProcessInfo, error) {
	return findProcesses(DefaultProcesses)
}
