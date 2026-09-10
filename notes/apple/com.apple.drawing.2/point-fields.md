# Per-point auxiliary channels (force/azimuth/altitude/...)

Continuation of `envelope.md` in this directory, going one function deeper to
try to pin down the "extra" bytes after each point's X/Y float32 pair in
`Geometry.points` (see `tools/apple-notes-drawing.proto`, which currently
just exposes them as an opaque hex tail per point).

## Function examined

`-[_PKStrokeConcrete(PKStrokePersistenceAdditions)
readPointFromLegacyArchive:deltaFrom:]`, same binary as before
(`PencilKit.framework`, extracted from the macOS 26.6.2 (25G83) arm64e dyld
shared cache via `ipsw dyld extract`, disassembled via `ipsw macho disass
... --demangle --force`).

## Important caveat before the findings

This function does **not** touch raw bytes or `NSData` at all - no
`getBytes:range:`, no `bytes`/`length` calls, nothing byte-stream shaped.
It operates purely on fixed-offset loads from two already-in-memory C
structs passed in registers (an "archive" point struct and a "delta from"
previous-point struct), and writes a third struct of the same shape by
value (ARM64's indirect-struct-return convention, a hidden pointer argument
ahead of the declared ones).

That means the flat-buffer byte layout the Perl decoder infers empirically
(strides of 12/14/16/18/20/22 bytes per point, first 8 bytes = float32 X/Y)
is **not** explained by this function - something upstream of it must
already unpack the raw `Geometry.points` bytes into these fixed-offset
structs, one per point, before handing them here. That upstream unpacking
is the better target for actually explaining the stride/tail-bytes mystery,
and hasn't been examined yet. Best guess for where to look next: `-[_PKStrokeConcrete(PKStrokePersistenceAdditions)
initWithLegacyArchive:sortedUUIDs:]`, which is what calls this function
(seen by symbol name only in `nm` output so far, not yet disassembled).

## What is confirmed about this function

Both input structs carry a one-byte bitmask (loaded from a fixed offset in
the "archive" struct) recording which auxiliary channels are actually
present for that touch/point sample, versus which should fall back to nil.
The function is written as fully-branchless SIMD/FP select logic (no
conditional branches at all): for each output field it computes the
presence bit, and picks between the freshly-read value and a fallback using
`fcsel`/`bsl`-style select instructions rather than an `if`.

There are (at least) two different fallback policies depending on the
field:

- Some fields default to **zero** when their presence bit is unset.
- Others default to **the corresponding field from the "delta from"
  (previous point) struct** when unset - i.e. a carry-forward/delta scheme,
  matching the function's own name.

Three of the output fields are gated by presence-bitmask bit values that
are visible directly as immediate operands in the disassembly (not
inferred from a data dump, so no endianness ambiguity): bit `0x2`, bit
`0x4`, and bit `0x8`. All three use the carry-forward-from-previous-point
fallback. Notably, these three bit values are numerically identical to
Apple's own public, documented `UITouchProperties` option set from
`UIKit/UITouch.h` (`UITouchPropertyAzimuth = 1<<1`, `UITouchPropertyAltitude
= 1<<2`, `UITouchPropertyLocation = 1<<3` - a bitmask iOS has shipped
publicly since iOS 9.1 for describing which properties of a touch sample
are known vs. estimated). That overlap is suggestive - it would make sense
for PencilKit's legacy on-disk format to reuse the same presence-bitmask
convention UIKit already uses at the touch-event level - but it is **not
confirmed**: one of the three fields sized as a single 8-byte scalar lines
up bit-for-bit with `UITouchPropertyLocation`, which in the public API
describes a 2-D point, not a scalar, so either the bit is being reused for
something else in this on-disk encoding, or my field-to-bit pairing has an
off-by-one. `UITouchPropertyForce` (bit `0x1`) is not tested anywhere in
this function at all, for what that's worth.

Two more output fields (each 16 bytes - a pair of doubles) are also
presence-gated, via a vector AND against a small embedded constant rather
than a plain immediate `tst`, so I don't have confident exact bit values
for those two yet. One of the two defaults to zero when absent, the other
to carry-forward, matching the same two-policy pattern as above.

## Not concluded

- Which channel is which (force vs. azimuth vs. altitude vs. something
  PencilKit-internal) beyond the tentative `UITouchProperties`-shaped
  overlap above.
- The exact presence bits for the two 16-byte (vector) fields.
- How any of this maps back onto byte offsets within the flat
  `Geometry.points` buffer the Perl decoder reads today - that requires
  disassembling the unpacking function mentioned above, not this one.

## Provenance

Same source Mac/OS/build and toolchain as `envelope.md` in this directory
(macOS 26.6.2, build 25G83, Apple Silicon T8103; `ipsw` v3.1.714; `otool`
from Xcode Command Line Tools). Method identifier referenced above is a
verbatim symbol name from `PencilKit.framework`'s symbol table. The
`UITouchProperties` bit values are public Apple API, not derived from this
disassembly. No disassembly, raw bytes, or extracted binary content is
reproduced here or committed to this repo.
