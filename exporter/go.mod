module wechatsummary/exporter

go 1.21

require golang.org/x/crypto v0.24.0

require (
	github.com/klauspost/compress v1.17.9
	github.com/wdvxdr1123/go-silk v0.0.0-20220304095002-f67345df09ea
)

require (
	github.com/mattn/go-isatty v0.0.12 // indirect
	github.com/remyoudompheng/bigfft v0.0.0-20200410134404-eec4a21b6bb0 // indirect
	golang.org/x/sys v0.21.0 // indirect
	modernc.org/libc v1.8.1 // indirect
	modernc.org/mathutil v1.2.2 // indirect
	modernc.org/memory v1.0.4 // indirect
)

// Local vendored copy of the BSD-3-Clause SILK SDK Go port with
// //go:nocheckptr added to every transpiled function. The ccgo-generated sdk
// performs raw unsafe.Pointer arithmetic that trips Go's checkptr (enabled by
// `go test -race`); the annotation is the supported way to exempt such
// third-party transpiled code, and this build must stay green under -race.
replace github.com/wdvxdr1123/go-silk => ./third_party/go-silk
