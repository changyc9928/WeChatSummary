package bridge

import (
	"bytes"
	"encoding/binary"
	"reflect"
	"testing"
)

func mustDec(t *testing.T, h string) []byte {
	t.Helper()
	b := mustHexBytes(h)
	if len(b) == 0 && len(h) > 0 {
		t.Fatalf("bad hex in test: %q", h)
	}
	return b
}

func TestSignatureConstant(t *testing.T) {
	sig := mustDec(t,
		"83ec404889d64889cb0f57c00f1142100f11024c8bb1c80200004883b9d0020000107209"+
			"488b9bb8020000eb074881c3b80200004d85f60f880a0200004983fe10736d4c897610"+
			"48c746180f0000000f10030f110648b8")
	if len(sig) != 87 {
		t.Fatalf("signature length = %d, want 87", len(sig))
	}
	if !bytes.Equal(sig[:4], []byte{0x83, 0xec, 0x40, 0x48}) {
		t.Fatalf("signature prefix mismatch: %x", sig[:4])
	}
	if !bytes.Equal(sig[len(sig)-5:], []byte{0x0f, 0x11, 0x06, 0x48, 0xb8}) {
		t.Fatalf("signature tail mismatch: %x", sig[len(sig)-5:])
	}
}

func TestExtractInternalKeyMovImm(t *testing.T) {
	// synthetic module: 4 x mov rdx,imm64 with prescribed immediates, junk in
	// between, then test rax,rax.
	imm := []uint64{0xDEADBEEFCAFEBABE, 0x0123456789ABCDEF, 0x1122334455667788, 0x99AABBCCDDEEFF00}
	var mod []byte
	mod = append(mod, bytes.Repeat([]byte{0x90}, 8)...) // nop padding
	for _, v := range imm {
		mod = append(mod, 0x48, 0xBA)
		var b [8]byte
		binary.LittleEndian.PutUint64(b[:], v)
		mod = append(mod, b[:]...)
		mod = append(mod, 0x90, 0x90) // gap
	}
	mod = append(mod, 0x48, 0x85, 0xC0)

	m := extractInternalKeyMovImm(mod, 0)
	if m == nil {
		t.Fatal("expected internal key extraction")
	}
	key := m.Key
	want := make([]byte, 0, 32)
	for _, v := range imm {
		var b [8]byte
		binary.LittleEndian.PutUint64(b[:], v)
		want = append(want, b[:]...)
	}
	if !bytes.Equal(key, want) {
		t.Fatalf("key = %x, want %x", key, want)
	}
}

func TestExtractInternalKeyMovImmMissing(t *testing.T) {
	mod := bytes.Repeat([]byte{0x48, 0xBA, 1, 2, 3, 4, 5, 6, 7, 8}, 3)
	mod = append(mod, 0x48, 0x85, 0xC0)
	if m := extractInternalKeyMovImm(mod, 0); m != nil {
		t.Fatalf("expected nil when 4th immediate missing, got %x", m.Key)
	}
	if m := extractInternalKeyMovImm(bytes.Repeat([]byte{0x48, 0xBA, 1, 2, 3, 4, 5, 6, 7, 8}, 3), 0); m != nil {
		t.Fatalf("expected nil on incomplete sequence, got %x", m.Key)
	}
}

func TestSignatureMatch(t *testing.T) {
	base := uintptr(0x180000000)
	var mod []byte
	mod = append(mod, weixinKeySignature...) // 87 bytes, ends with 48 b8
	var v1, v2 [8]byte
	binary.LittleEndian.PutUint64(v1[:], uint64(base+0x12300))
	binary.LittleEndian.PutUint64(v2[:], uint64(base+0x45600))
	// the three wkt markers with their imm64s
	mod = append(mod, 0x48, 0x89, 0x44, 0x24, 0x20, 0x48, 0xb8)
	mod = append(mod, v1[:]...)
	mod = append(mod, 0x48, 0x89, 0x44, 0x24, 0x28, 0x48, 0xb8)
	mod = append(mod, v2[:]...)
	mod = append(mod, 0x48, 0x89, 0x44, 0x24, 0x30, 0x48, 0xb8)
	var v3 [8]byte
	binary.LittleEndian.PutUint64(v3[:], uint64(base+0x78900))
	mod = append(mod, v3[:]...)

	idx, got := signatureMatch(mod)
	if idx != 0 {
		t.Fatalf("sig index = %d, want 0", idx)
	}
	if len(got) != 3 {
		t.Fatalf("matched %d markers, want 3", len(got))
	}
	want := []uintptr{base + 0x12300, base + 0x45600, base + 0x78900}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("VAs = %x, want %x", got, want)
	}
	if idx2, got2 := signatureMatch(bytes.Repeat([]byte{0x90}, 200)); idx2 != -1 || len(got2) != 0 {
		t.Fatalf("missing sig: idx=%d targets=%v", idx2, got2)
	}
}

func TestDllOffsets(t *testing.T) {
	base := uintptr(0x141200000) // relocated module
	va := uintptr(0x18000ABCD)
	offs := dllOffsets(va, base)
	// primary = va-base; secondary = va-0x180000000
	want := []int{int(va - base), int(va - 0x180000000)}
	if !reflect.DeepEqual(offs, want) {
		t.Fatalf("offsets = %v, want %v", offs, want)
	}
	if offs[0] < 0 || offs[1] != 0xABCD {
		t.Fatalf("unexpected offset values %v", offs)
	}
}

func TestMemDataFilter(t *testing.T) {
	hi := bytes.Repeat([]byte{0x42}, 32) // constant byte -> reject
	if memDataFilter(hi) {
		t.Fatal("constant byte blob must be rejected")
	}
	rnd := []byte{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
		0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
		0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
		0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f}
	if !memDataFilter(rnd) {
		t.Fatal("high-entropy blob must be accepted")
	}
	trip := append([]byte{}, rnd...)
	trip[30] = trip[29]
	trip[31] = trip[29]
	if memDataFilter(trip) { // third occurrence of 0x1d -> reject
		t.Fatal("blob with a tripled byte must be rejected")
	}
}

func TestXorBytes32(t *testing.T) {
	a := bytes.Repeat([]byte{0xFF}, 32)
	b := bytes.Repeat([]byte{0x0F}, 32)
	out := xorBytes32(a, b)
	want := bytes.Repeat([]byte{0xF0}, 32)
	if !bytes.Equal(out, want) {
		t.Fatalf("xor = %x, want %x", out, want)
	}
	if bytes.Equal(out, a) {
		t.Fatal("xor must not alias input")
	}
}

func TestMarkerWindowsAndCandidates(t *testing.T) {
	mod := bytes.Repeat([]byte{0x41}, 4096) // fill
	mk := []byte(chatShadowMarkers[1])      // clicfg_xwechat
	pos := 3000
	copy(mod[pos:], mk)

	wr := markerWindows(mod, 256)
	if len(wr) == 0 {
		t.Fatal("expected marker window")
	}
	found := false
	for _, r := range wr {
		if r[0] <= pos && pos+len(mk) <= r[1] {
			found = true
		}
	}
	if !found {
		t.Fatalf("marker at %d not inside returned windows %v", pos, wr)
	}

	// fill window region with high-entropy content so candidates pass the filter
	for i := 0; i < len(mod); i++ {
		mod[i] = byte(i * 7)
	}
	copy(mod[pos:], mk)
	mems := memDataCandidates(mod, wr, 100)
	if len(mems) == 0 {
		t.Fatal("expected mem_data candidates")
	}
	for _, m := range mems {
		if len(m) != 32 {
			t.Fatalf("candidate length %d", len(m))
		}
	}
}

func TestPassphraseCandidates(t *testing.T) {
	ik1 := bytes.Repeat([]byte{0x11}, 32)
	ik2 := bytes.Repeat([]byte{0x22}, 32)
	m1 := bytes.Repeat([]byte{0x33}, 32)
	m2 := bytes.Repeat([]byte{0x44}, 32)
	pcs, src := passphraseCandidates([][]byte{ik1, ik2}, [][]byte{m1, m2}, 100)
	if len(pcs) != 4 || len(src) != 4 {
		t.Fatalf("got %d candidates / %d src", len(pcs), len(src))
	}
	if !bytes.Equal(pcs[0], bytes.Repeat([]byte{0x22}, 32)) { // 11^33
		t.Fatalf("candidate0 = %x", pcs[0])
	}
	if !bytes.Equal(pcs[3], bytes.Repeat([]byte{0x66}, 32)) { // 22^44
		t.Fatalf("candidate3 = %x", pcs[3])
	}
	capped, _ := passphraseCandidates([][]byte{ik1, ik2}, [][]byte{m1, m2}, 3)
	if len(capped) != 3 {
		t.Fatalf("cap not honored: %d", len(capped))
	}
}

func TestHasMarkers(t *testing.T) {
	found := hasMarkers([]byte("prefix clicfg_xwechat suffix"))
	if len(found) != 1 || found[0] != "clicfg_xwechat" {
		t.Fatalf("found = %v", found)
	}
	if len(hasMarkers([]byte("nothing here"))) != 0 {
		t.Fatal("unexpected marker match")
	}
}

func TestRipRelativeTargets(t *testing.T) {
	base := uintptr(0x180000000)
	var mod []byte
	// lea rax,[rip+0x1000]: 48 8d 05 <rel32>
	mod = append(mod, 0x48, 0x8d, 0x05)
	var r1 [4]byte
	binary.LittleEndian.PutUint32(r1[:], 0x1000)
	mod = append(mod, r1[:]...)
	// mov rcx,[rip-0x800]: 48 8b 0d <rel32>
	mod = append(mod, 0x48, 0x8b, 0x0d)
	var r2 [4]byte
	binary.LittleEndian.PutUint32(r2[:], 0xFFFFF800) // -0x800 as uint32
	mod = append(mod, r2[:]...)
	// movabs rax, 0x18000ABCD: 48 b8 <imm64>
	mod = append(mod, 0x48, 0xb8)
	var m1 [8]byte
	binary.LittleEndian.PutUint64(m1[:], 0x18000ABCD)
	mod = append(mod, m1[:]...)
	// junk between
	mod = append(mod, 0x90, 0x90, 0x90)

	got := ripRelativeTargets(mod, 0, len(mod), base)
	// lea at offset 0: VA = base+0+7+0x1000 ; mov at offset 7: VA = base+7+7-0x800
	want := []uintptr{
		base + 7 + 0x1000,
		base + 14 - 0x800,
		0x18000ABCD,
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("targets = %x, want %x", got, want)
	}
}
