package main

import (
	"bytes"
	"encoding/gob"
	"math/rand"
	"testing"
)

func TestEncodeRawMatchesGob(t *testing.T) {
	for _, m := range []map[uint64]string{
		{}, {300: "ab"}, {0: "", 1<<64 - 1: "x"}, {127: "y", 128: "z", 1 << 40: string(make([]byte, 300))},
	} {
		var want bytes.Buffer
		gob.NewEncoder(&want).Encode(m)
		got, _ := encodeRaw(m)
		if len(m) <= 1 && !bytes.Equal(got, want.Bytes()) {
			t.Errorf("%v: got % x want % x", m, got, want.Bytes())
		}
		back, err := decodeRaw(got)
		if err != nil || diffMaps(m, back) != "" {
			t.Errorf("%d entries: roundtrip err=%v diff=%s", len(m), err, diffMaps(m, back))
		}
	}
	m := map[uint64]string{}
	for i := 0; i < 5000; i++ {
		m[rand.Uint64()>>uint(rand.Intn(64))] = string(make([]byte, rand.Intn(400)))
	}
	a, _ := encodeRaw(m)
	b, _ := encodeRaw(m)
	if !bytes.Equal(a, b) {
		t.Error("not deterministic")
	}
	back, err := decodeRaw(a)
	if err != nil || diffMaps(m, back) != "" {
		t.Errorf("big roundtrip err=%v", err)
	}
}
