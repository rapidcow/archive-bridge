// a2sdump decodes an ipsw .a2s cache (a gob-encoded map[uint64]string of
// address -> symbol name) and prints it as sorted "addr\tsymbol" text.
//
// ipsw re-encodes the map by iterating it directly, and Go deliberately
// randomizes map iteration order per process, so the raw .a2s bytes differ
// between runs even when the underlying symbol data is identical. Sorting
// by address gives a canonical form that's actually safe to diff.
package main

import (
	"bufio"
	"encoding/gob"
	"fmt"
	"os"
	"sort"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: a2sdump <file.a2s>")
		os.Exit(1)
	}
	f, err := os.Open(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer f.Close()

	m := make(map[uint64]string)
	if err := gob.NewDecoder(f).Decode(&m); err != nil {
		fmt.Fprintln(os.Stderr, "decode:", err)
		os.Exit(1)
	}

	keys := make([]uint64, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return keys[i] < keys[j] })

	w := bufio.NewWriter(os.Stdout)
	defer w.Flush()
	for _, k := range keys {
		fmt.Fprintf(w, "%#x\t%s\n", k, m[k])
	}
}
