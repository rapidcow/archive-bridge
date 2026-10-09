// a2sdump decodes an ipsw .a2s cache (a gob-encoded map[uint64]string of
// address -> symbol name) and reprints it either as sorted "addr\tsymbol"
// text (the default, safe to diff) or, with -R, re-encoded as gob bytes
// again (a real .a2s ipsw can load back).
//
// Why sorting matters: per blacktop/ipsw's own source (pkg/disass/macho.go
// @ f46be4032de2937451431e8cf1c38e8627c3f5fc), the cache is just a struct
// field `a2s map[uint64]string` (line 31) written with
// `gob.NewEncoder(buff).Encode(d.a2s)` (line 928). Encoding a Go map with
// encoding/gob just ranges over it directly, and Go intentionally
// randomizes map iteration order per process, so the raw bytes of two
// otherwise-identical caches never match. The sorted text form is the
// canonical, diffable representation; -R is for regenerating a loadable
// .a2s blob. Since encoding/gob can't be asked to sort, -R writes gob's
// (tiny, fixed) wire format for map[uint64]string by hand, with entries in
// ascending key order, so the bytes are canonical; stock gob decodes it.
package main

import (
	"bufio"
	"bytes"
	"encoding/gob"
	"flag"
	"fmt"
	"io"
	"os"
	"sort"
	"strconv"
	"strings"
)

func main() {
	rawFlag := flag.Bool("R", false, "write raw gob-encoded bytes instead of sorted text")
	verify := flag.Bool("t", false, "round-trip verify the generated output against the input")
	verbose := flag.Bool("v", false, "print diagnostics (entry counts) to stderr")
	outPath := flag.String("o", "", `output file ("-" for stdout; default stdout, but -R refuses a terminal unless this is set)`)
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: %s [-R] [-t] [-v] [-o path] <input.a2s>\n", os.Args[0])
	}
	flag.Parse()

	if flag.NArg() != 1 {
		flag.Usage()
		os.Exit(1)
	}
	inPath := flag.Arg(0)

	m, err := loadA2S(inPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s: %v\n", inPath, err)
		os.Exit(1)
	}
	if *verbose {
		fmt.Fprintf(os.Stderr, "read %d entries from %s\n", len(m), inPath)
	}

	var body []byte
	if *rawFlag {
		body, err = encodeRaw(m)
	} else {
		body, err = encodeText(m)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "encode: %v\n", err)
		os.Exit(1)
	}

	if *verify {
		var m2 map[uint64]string
		if *rawFlag {
			m2, err = decodeRaw(body)
		} else {
			m2, err = decodeText(body)
		}
		if err != nil {
			fmt.Fprintf(os.Stderr, "verify: round-trip decode failed: %v\n", err)
			os.Exit(1)
		}
		if diff := diffMaps(m, m2); diff != "" {
			fmt.Fprintf(os.Stderr, "verify: round-trip mismatch:\n%s", diff)
			os.Exit(1)
		}
		if *verbose {
			fmt.Fprintf(os.Stderr, "verify: round-tripped %d entries, matches input\n", len(m2))
		}
	}

	dest, destName, err := resolveOutput(*outPath, *rawFlag)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
	if c, ok := dest.(io.Closer); ok {
		defer c.Close()
	}

	if _, err := dest.Write(body); err != nil {
		fmt.Fprintf(os.Stderr, "write to %s: %v\n", destName, err)
		os.Exit(1)
	}
	if *verbose {
		format := "text"
		if *rawFlag {
			format = "raw"
		}
		fmt.Fprintf(os.Stderr, "wrote %d entries (%s) to %s\n", len(m), format, destName)
	}
}

// resolveOutput decides where output goes per the -o flag, refusing to
// write raw bytes to a terminal unless the destination was explicitly
// requested.
func resolveOutput(outPath string, raw bool) (io.Writer, string, error) {
	switch outPath {
	case "-":
		return os.Stdout, "stdout", nil
	case "":
		if raw && isTerminal(os.Stdout) {
			return nil, "", fmt.Errorf("refusing to write raw .a2s bytes to a terminal.\neither use -o <file> to write to a file, or `-o -'\nif you really mean to use stdout.")
		}
		return os.Stdout, "stdout", nil
	default:
		f, err := os.Create(outPath)
		if err != nil {
			return nil, "", fmt.Errorf("create %s: %w", outPath, err)
		}
		return f, outPath, nil
	}
}

func isTerminal(f *os.File) bool {
	info, err := f.Stat()
	if err != nil {
		return false
	}
	return info.Mode()&os.ModeCharDevice != 0
}

func loadA2S(path string) (map[uint64]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	m := make(map[uint64]string)
	if err := gob.NewDecoder(f).Decode(&m); err != nil {
		return nil, fmt.Errorf("decode: %w", err)
	}
	return m, nil
}

func sortedKeys(m map[uint64]string) []uint64 {
	keys := make([]uint64, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return keys[i] < keys[j] })
	return keys
}

func encodeText(m map[uint64]string) ([]byte, error) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	for _, k := range sortedKeys(m) {
		fmt.Fprintf(w, "%#x\t%s\n", k, m[k])
	}
	if err := w.Flush(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func decodeText(body []byte) (map[uint64]string, error) {
	m := make(map[uint64]string)
	sc := bufio.NewScanner(bytes.NewReader(body))
	for sc.Scan() {
		line := sc.Text()
		if line == "" {
			continue
		}
		tab := strings.IndexByte(line, '\t')
		if tab < 0 {
			return nil, fmt.Errorf("malformed line: %q", line)
		}
		addr, err := strconv.ParseUint(line[:tab], 0, 64)
		if err != nil {
			return nil, fmt.Errorf("malformed address %q: %w", line[:tab], err)
		}
		m[addr] = line[tab+1:]
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	return m, nil
}

// mapTypeDef is the gob type-definition message for map[uint64]string: the
// first user-defined type id (64) described as a wireType whose MapT has an
// empty name, Key uint (6) and Elem string (12). It never varies.
var mapTypeDef = []byte{
	0x0d, 0x7f, 0x04, 0x01, 0x02, 0xff, 0x80, 0x00,
	0x01, 0x06, 0x01, 0x0c, 0x00, 0x00,
}

// encodeRaw writes m as a gob stream that encoding/gob decodes back into a
// map[uint64]string, but with entries in ascending key order. (gob's own
// Encoder ranges over the map, so its output is non-deterministic.)
func encodeRaw(m map[uint64]string) ([]byte, error) {
	// Value message body: type id 64, singleton marker 0, entry count,
	// then key/string pairs.
	var body []byte
	body = appendGobInt(body, 64)
	body = append(body, 0)
	body = appendGobUint(body, uint64(len(m)))
	for _, k := range sortedKeys(m) {
		body = appendGobUint(body, k)
		body = appendGobUint(body, uint64(len(m[k])))
		body = append(body, m[k]...)
	}
	out := append([]byte(nil), mapTypeDef...)
	out = appendGobUint(out, uint64(len(body)))
	return append(out, body...), nil
}

// appendGobUint appends gob's unsigned integer encoding: values below 128
// are one byte; otherwise a negated byte count followed by big-endian bytes.
func appendGobUint(b []byte, u uint64) []byte {
	if u < 128 {
		return append(b, byte(u))
	}
	var tmp [8]byte
	n := 0
	for ; u != 0; u >>= 8 {
		tmp[7-n] = byte(u)
		n++
	}
	b = append(b, byte(-n))
	return append(b, tmp[8-n:]...)
}

// appendGobInt appends gob's signed integer encoding: the sign goes in the
// low bit, with negative values complemented.
func appendGobInt(b []byte, i int64) []byte {
	var u uint64
	if i < 0 {
		u = (uint64(^i) << 1) | 1
	} else {
		u = uint64(i) << 1
	}
	return appendGobUint(b, u)
}

func decodeRaw(body []byte) (map[uint64]string, error) {
	m := make(map[uint64]string)
	if err := gob.NewDecoder(bytes.NewReader(body)).Decode(&m); err != nil {
		return nil, err
	}
	return m, nil
}

func diffMaps(a, b map[uint64]string) string {
	var sb strings.Builder
	if len(a) != len(b) {
		fmt.Fprintf(&sb, "  entry count differs: %d vs %d\n", len(a), len(b))
	}
	for _, k := range sortedKeys(a) {
		av, bv, ok := a[k], b[k], false
		if bv, ok = b[k]; !ok {
			fmt.Fprintf(&sb, "  %#x: missing from round-trip (want %q)\n", k, av)
		} else if av != bv {
			fmt.Fprintf(&sb, "  %#x: %q != %q\n", k, av, bv)
		}
	}
	for _, k := range sortedKeys(b) {
		if _, ok := a[k]; !ok {
			fmt.Fprintf(&sb, "  %#x: unexpected in round-trip (got %q)\n", k, b[k])
		}
	}
	return sb.String()
}
