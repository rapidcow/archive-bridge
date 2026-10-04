# Per-point channels across macOS releases (10 -> 11 -> 12)

Continuation of `point-decode-pipeline.md` §6-§9, which pinned down the
11-entry `sFieldOffsets`/`sFieldSizes` tables on macOS 26.6.2. This file
extends that to two more releases and records what changed:

| macOS | build | entries | last channel | source of the binary |
|---|---|---|---|---|
| 15.8.1 | 24H32 | 10 | 9 `radius2` | this Mac's own shared cache |
| 26.6.2 | 25G83 | 11 | 10 `threshold` | remote-extracted from the public IPSW |
| 27.0 | 26A428 | 12 | 11 `elevation` (new) | remote-extracted from the public IPSW |

Session date 2026-10-03. Nothing here required the SSH access earlier notes
used for the 26.6.2 machine; every binary below was obtained locally.

## 1. Provenance: how the three binaries were obtained

All three are the real `/System/Library/Frameworks/PencilKit.framework/
Versions/A/PencilKit` image, extracted from a dyld shared cache with
`ipsw dyld extract`.

- **15.8.1 (24H32)**: the running OS. The cache is world-readable
  (`-rwxr-xr-x root admin`) under
  `/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/`; extraction ran
  as an ordinary user, no sudo. `ipsw dyld extract` refuses the bare name
  `PencilKit` (three images match, including the Catalyst one under
  `/System/iOSSupport/` and an `.axbundle`), so the full image path is
  required. The `Cryptexes/Incoming` cache next to it is the same release,
  not a staged update.
- **26.6.2 and 27.0**: `ipsw download ipsw --macos --version <v> --device
  Mac16,11 --dyld --dyld-arch arm64e -y -o <dir>`. This fetches only the
  dyld cache's byte ranges from Apple's public IPSW, not the whole restore
  image (full images are about 19.8 GB and 26.6 GB; the transfer was a few
  GB each). No Apple ID or beta profile was needed for either. The caches are
  kept at `/Volumes/SN770_ng/usr/ethan/lib/macos/{26.6.2,27.0}/
  <build>__Mac16,11/`, so re-extracting costs no further download.
  `ipsw` buffers the whole transfer in memory (about 5 GB resident) and
  writes at the end, so the target folder looks empty for several minutes.

Reading the tables out of any of these takes four steps:

1. `ipsw dyld extract <cache> /System/Library/Frameworks/PencilKit.framework/
   Versions/A/PencilKit --output <dir>`
2. `ipsw macho disass PencilKit --symbol '+[PKStrokePath
   _calculateOffsets:sizes:]' --demangle --force`. The function is a pure
   literal store (no branches, no calls), so the disassembly is short.
3. Parse `otool -l PencilKit` for each segment's `vmaddr`/`vmsize`/`fileoff`
   and map the `adrp`+`ldr q` addresses to file offsets, then
   `struct.unpack('<2q', ...)` each 16-byte literal. Entries written as bare
   immediates (`mov w8, #imm` then `str`, or `dup.2d`) are filled in by hand.
4. Cross-check the entry count two ways: the distance between the symbols
   `__ZN12_GLOBAL__N_113sFieldOffsetsE` and `...111sFieldSizesE` in
   `nm -U` (8 bytes per entry), and the `cmp x?, #N` loop bound in
   `PKStrokePathPointsShared::computeCachedValues()`.

## 2. Results

`sFieldOffsets` / `sFieldSizes` (byte offset, byte width) by channel index:

| idx | channel | 15.8.1 | 26.6.2 | 27.0 |
|---|---|---|---|---|
| 0 | location (x,y f32 pair) | 0 / 8 | 0 / 8 | 0 / 8 |
| 1 | timestamp | 8 / 4 | 8 / 4 | 8 / 4 |
| 2 | radius | 12 / 4 | 12 / 4 | 12 / 4 |
| 3 | aspectRatio | 16 / 2 | 16 / 2 | 16 / 2 |
| 4 | edgeWidth | 18 / 2 | 18 / 2 | 18 / 2 |
| 5 | force | 20 / 2 | 20 / 2 | 20 / 2 |
| 6 | azimuth | 22 / 2 | 22 / 2 | 22 / 2 |
| 7 | altitude | 24 / 2 | 24 / 2 | 24 / 2 |
| 8 | opacity | 26 / 2 | 26 / 2 | 26 / 2 |
| 9 | radius2 | 28 / 4 | 28 / 4 | 28 / 4 |
| 10 | threshold | absent | 32 / 2 | 32 / 2 |
| 11 | elevation | absent | absent | 36 / 4 |

Entry count evidence per build:

- 15.8.1: symbols 0x50 apart; writer stores through `[x2,#0x40]` as a single
  16-byte `str q` (entries 8-9) and nothing past it; `computeCachedValues`
  loops with `cmp #0xa`.
- 26.6.2: symbols 0x58 apart; writer adds `mov w8,#0x20 ; str x8,[x2,#0x50]`
  (entry 10 offset) and `str x8,[x3,#0x50]` (entry 10 size = 2); loop bound
  `cmp #0xb`.
- 27.0: symbols 0x60 apart; writer stores two full q-registers at `[x2,#0x40]`
  (entries 8-11), and entries 10-11 of the size table reuse the same
  `(2, 4)` literal as entries 8-9; loop bound `cmp #0xc`.

Entries 0-10 are byte-for-byte identical wherever they exist: the format has
only ever been extended at the end, in bit order. The 15.8.1 and 26.6.2
values were re-derived independently on 2026-10-03 and agree with
`point-decode-pipeline.md` §6a's account of 26.6.2.

### The offset table stops being a packed running sum at entry 11

§6 observed that `sFieldOffsets[i]` equals the sum of `sFieldSizes[0..i-1]`
and read that as "packed layout". That holds through index 10 (offset 32 +
size 2 = 34). `sFieldOffsets[11]` is **36**, not 34: two bytes of alignment
padding so the f32 starts on a 4-byte boundary. So `sFieldOffsets` describes
the **in-memory defaults/template record** (now 40 bytes; the 27.0
`PKStrokePoint` init does `vector<uint8_t>::resize(0x28)` for a one-point
buffer), while the **wire layout is packed**: per-point offsets come from a
cumulative table built from the presence mask (the byte table at
`shared+0x60+channel` that every `*AtIndex` accessor indexes with `madd`
stride*index + that byte), with no padding. Stride on the wire is
`8 + sum(sizes of set bits)`, as the Java and Perl decoders already assume.
Evidence the two layouts differ: defaults for absent channels are read at
`shared+0x38+sFieldOffsets[i]` (e.g. `radius2` default at `+0x54 = 0x38+28`,
`elevation` default at `+0x5c = 0x38+36`), whereas present channels are read
at `+0x60+channel` bytes into the per-point stride.

## 3. The twelfth channel is `elevation`, an f32

Not `lateralJitter`, which was the first guess (the public `PKStrokePoint`
docs list `threshold` then `lateralJitter` as the recent additions, and a
4-byte slot after `threshold` looked like a natural fit). The evidence for
`elevation`, all in the 27.0 binary:

- **The init stores it there.** `-[PKStrokePoint initWithLocation:timeOffset:
  size:opacity:force:azimuth:altitude:secondaryScale:threshold:lateralJitter:
  elevation:]` (`0x1b1a4fa6c`) takes the 12th argument (`[fp,#0x30]`) and, only
  if bit `0x8` of the second mask byte (`[mask,#0x21]`) is set, does
  `fcvt s0, d0 ; str s0,[ptr + offsetTable[0x6b]]`. Byte 1, bit 3 is channel
  11 (the mask is indexed by channel; byte 1 bit n is channel 8+n).
- **The getter reads it there.** `-[PKStrokePoint elevation]` (`0x1b1a504b0`)
  and `PKStrokePathPointsPrivate::elevationAtIndex` (`0x1b1a8f7b4`) do
  `ldr s0, [...] ; fcvt d0, s0`: a 32-bit float load widened to double, with
  the default at `shared+0x5c` when the bit is clear.
- **`PKCompressedStrokePoint::setElevation` stores an f32 at struct offset
  0x24 (36)**, the exact twelfth offset-table entry.
- **f32 on the wire, not u32.** No `scvtf`/`ucvtf`/`fcvtz*`/byte-reversal
  instruction appears in `elevationAtIndex`, `setElevation`,
  `-[PKStrokePoint elevation]`, and `compressElevation`/`uncompressElevation`
  are bare `fcvt`. Unlike the u16 channels there is no divisor or bias.
  AArch64 is little-endian, matching the other channels.
- **Not an alias for altitude.** The same init takes `altitude:` and
  `elevation:` as separate arguments; altitude is transformed (`const -
  altitude`) into a u16 at index 7, elevation is an untransformed f32 at
  index 11, and each has its own presence bit and default slot. A
  `PKStrokeGenerator` property `elevationPointsPerMillimeter` hints the
  value is a physical distance (hover height?), not the tilt angle altitude
  already carries; that reading is a hint only, the input source was not
  traced (`PKAveragePointGenerator` has an `_elevation` ivar but the
  disassembly of `currentStrokePoint` did not show where it is fed from).

**Not verified:** a real stroke with bit 11 set. The on-wire f32 conclusion
rests on the accessors indexing the same stride/offset buffer the other
channels use, which is the same buffer `points_data` becomes. Units and range
of `elevation` are unknown. `elevation` is not in the public API
documentation that was checked (`threshold` and `lateralJitter` are).

## 4. `lateralJitter` is the existing `edgeWidth` channel

Correcting an earlier statement (this session, before the 27.0 disassembly)
that `edgeWidth` had no public counterpart:

- `-[PKStrokePoint lateralJitter]` (`0x1b1a50458`) is two instructions plus a
  tail call: `add x0,x0,#8 ; mov x1,#0 ; b PKStrokePathPointsPrivate::
  edgeWidthAtIndex(unsigned long) const`. `-[PKStrokePoint _edgeWidth]`
  (`0x1b1a5044c`) tail-calls the identical function. They are the same
  getter under two names.
- In the 27.0 init, the `lateralJitter:` argument (`[fp,#0x28]`) is the one
  passed to `setEdgeWidth(double, size_t)`.
- So channel 4 (u16, offset 18, `value / 10.0`) already existed on 15.8.1;
  the **public name** `lateralJitter` first appears in 27.0. Neither the
  `lateralJitter` getter nor the `lateralJitter:` initializer exists in the
  26.6.2 binary (`nm -U` and `strings` both come up empty; `_edgeWidth`,
  `secondaryScale`, `threshold` are present there). The public docs' wording
  ("lateral particle jitter at the stroke edge for supported inks") is
  consistent with a per-point edge-width value for particle inks.
- `edgeWidth` keeps its old name on the wire/Java side; the rename is a
  documentation fact, not a format change.
- Public-API availability (from the user's reading of Apple's documentation,
  2026-10-03; not independently checked against an SDK header, since the
  installed Command Line Tools SDKs stop at 15.5): `threshold` is macOS 26+
  and `lateralJitter` is macOS 27+. Both agree with the binaries above:
  `threshold` first appears in 26.6.2 (wire channel 10), `lateralJitter`
  first appears in 27.0 (a new public name over the older wire channel 4).
  `secondaryScale` is macOS 14+ per the 15.5 SDK header. `elevation` has no
  public documentation that was checked.

## 5. How the other channels derive from `PKStrokePoint` arguments

Read off the same 27.0 init (each is a straight conversion; nothing else
touches the buffer):

| channel | source argument | transformation |
|---|---|---|
| timestamp (1) | `timeOffset` | `fcvt` d -> s |
| radius (2) | `size.width` | `fcvt` d -> s |
| aspectRatio (3) | `size.height / size.width` | `fdiv`; `fcmp width,#0 ; fcsel` gives **1.0 when width <= 0** (no NaN/inf); then x1000 into a u16 via `setAspectRatio` |
| edgeWidth (4) | `lateralJitter` | `setEdgeWidth` (x10 into a u16) |
| force (5) | `force` | `setForce` |
| azimuth (6) | `azimuth` | plus a constant, then `setAzimuth` |
| altitude (7) | `altitude` | constant minus altitude, then `setAltitude` |
| opacity (8) | `opacity` | `setOpacity` |
| radius2 (9) | `size.width * secondaryScale` | `fmul`; `fcvt` d -> s |
| threshold (10) | `threshold` | `setThreshold` |
| elevation (11) | `elevation` | `fcvt` d -> s |

Consequences, consistent with the radius/aspect_ratio write-up elsewhere:
`size.width = radius`, `size.height = radius * aspectRatio`, and (new)
`secondaryScale = radius2 / radius`. The public `PKStrokePoint` property
types are `CGFloat`/`CGSize` (doubles), but the archive stores f32 and u16;
the SDK header says as much ("stores its properties compressed, the value
read for a property may not exactly equal the value set"). Do not infer wire
types from the public API's doubles.

## 6. Forward-compatibility notes for decoders

- The format has only grown by appending a channel at the next bit index.
  Wire order is ascending bit order, so a channel above the decoder's
  highest-known bit is always the last bytes of each point.
- A decoder that tests only known bits (as `PointsData.decode` does for
  bits 1-10) and ignores the rest computes a stride 4 bytes short for a
  stroke that has bit 11 set. Its existing `buf.length == count * stride`
  check then fails, so the failure is loud (for any non-zero `count`), not a
  silent misparse. Sizes of unknown channels cannot be guessed, which is why
  ignoring unknown bits is not an option; the practical response is to reject
  them with their bit numbers named and add the channel from this table when
  a new release introduces one.
- `points_flags` bit 0 is the mandatory location pair (always present); the
  optional channels are bits 1-11. Bit `i` of `points_flags` is channel `i`.
- To check a future release: repeat §1, compare the entry count and the new
  rows against §2, and look in `-[PKStrokePoint init...]` (the longest
  `initWithLocation:...` variant) for which `PKStrokePoint` argument feeds
  the new slot.

## 7. Not yet concluded

- A real macOS 27 sample with `points_flags` bit 11 set (confirms f32 on the
  wire, gives a range for `elevation`).
- Where `elevation` comes from at input time and what its units are
  (`PKStrokeGenerator`'s `elevationPointsPerMillimeter` at offset `0x190`
  suggests millimetres).
- Whether any shipping ink's rendering descriptor consumes `elevation` or
  `lateralJitter`.
- The unrelated `elevation:`-less init variants and `_radius2` getters were
  not re-disassembled; their behaviour is taken from §7 of
  `point-decode-pipeline.md`.
