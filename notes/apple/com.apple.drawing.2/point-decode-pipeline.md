# From per-stroke archive to the stride-decode site

Continuation of `point-fields.md`. That file left `-[_PKStrokeConcrete
(PKStrokePersistenceAdditions) initWithArchive:sortedUUIDs:inks:
transientArchiveDictionary:]` (node K in its call graph) as the top-priority
undisassembled target, being the per-stroke constructor Function 3
(`initWithArchive:loadNonInkingStrokes:error:`) calls once per stroke. This
file disassembles it and follows the concrete call edges it reveals, all the
way down to the actual site that divides a raw byte length by a per-point
stride - the original motivating mystery.

## 1. `initWithArchive:sortedUUIDs:inks:transientArchiveDictionary:` is a thin wrapper

Disassembled via `ipsw macho disass PencilKit -s '-[_PKStrokeConcrete
(PKStrokePersistenceAdditions) initWithArchive:sortedUUIDs:inks:
transientArchiveDictionary:]' --demangle --force`. It is four instructions:
shuffle the incoming `transientArchiveDictionary` argument into position,
zero out two new argument slots, and tail-branch (`b`, not `bl`) into a
longer, distinctly-named selector on the same class:
`-[_PKStrokeConcrete(PKStrokePersistenceAdditions) initWithArchive:
sortedUUIDs:inks:parent:isHidden:transientArchiveDictionary:]`, passing
`parent:` as `nil` and `isHidden:` as `NO`. All the real work - and
everything below - lives in that six-argument selector, confirmed via `nm -U
PencilKit | grep initWithArchive:sortedUUIDs:inks:parent`.

## 2. `initWithArchive:sortedUUIDs:inks:parent:isHidden:transientArchiveDictionary:`

Same binary/toolchain. This function is substantial (~230 instructions plus
ARC exception-landing-pad cleanup blocks) and is **self-recursive** - it
calls itself, by the same six-argument selector, once per "substroke" (see
§2d). Argument registers on entry, and the callee-saved registers they're
moved into for the body: `self`→x26 (unused further; a fresh instance is
`init`'d separately), the per-stroke `archive` struct pointer→x24,
`sortedUUIDs`→x19, `inks`→x20, `parent`→x21 (also spilled to `[sp]`),
`isHidden`→x25, `transientArchiveDictionary`→x22.

### 2a. Entry bounds check

If `archive+0xb0` is non-null, a 32-bit field at `(archive+0xb0)+0x10` is
read as an index and bounds-checked (`b.ls`) against `[sortedUUIDs count]`;
if the index exceeds the count, the function abandons construction
immediately and returns `nil` (same defensive shape `point-fields.md`
described for other archive-index reads). If `archive+0xb0` is null, this
check is skipped entirely.

### 2b. Fields read from `archive` and what they become

A fresh instance is obtained via a plain `[self init]` and used as the
receiver (x23) for the rest of the function, then populated field-by-field.
All offsets below are on this function's own `archive` argument - a
different struct from Function 3's outer per-drawing `archive` (same
begin/end-pointer-pair idiom family, but distinct layout, since these are
per-stroke fields):

- `+0x78`: stroke's own identity UUID. If non-null, an 8-byte pointer to raw
  UUID bytes is read from it and passed to `initWithUUIDBytes:`; if null, a
  fresh UUID is generated (bare `UUID` message send to an unnamed global
  class reference, same unconfirmed-identity pattern noted elsewhere in this
  directory). Result stored via `_setStrokeUUID:`.
- `+0x68`: if non-null, passed together with `sortedUUIDs` to
  `-[_PKStrokeConcrete readStrokeIDFromArchive:withSortedUUIDs:]` (confirmed
  exact selector via `nm`), whose result (a 24-byte value written across two
  stack slots, consistent with a UUID+generation-number pair) is handed to
  `set_sortID:`.
- `+0xb0`: same `readStrokeIDFromArchive:withSortedUUIDs:` call again, result
  passed to `set_version:` instead. (This is the same field already consulted
  for the §2a bounds check, reused here as an actual archive-with-sortedUUIDs
  lookup, not just a bounds probe.)
- `+0xb8`, bit `0x2`: if set, a value at `archive+0x60` is read and passed to
  `_setShapeType:`.
- `+0x28`: if non-null, its first field is passed (along with
  `transientArchiveDictionary`) to `+[_PKStrokeConcrete
  decodeRenameableUUIDFromData:transientArchiveDictionary:]` (a *class*
  method - confirmed by the `_OBJC_CLASS_$__PKStrokeConcrete` receiver load
  immediately before the call), and the result stored via `_setGroupID:`. If
  null, no group ID is set at all (no default-generation fallback, unlike
  most other optional fields here).
- `+0x58`: identical `decodeRenameableUUIDFromData:transientArchiveDictionary:`
  pattern, feeding `_setRenderGroupID:` instead.
- `+0x30` (an "ink index"), gated by bit `0x1` of a flag byte at `+0xb8`:
  bounds-checked against `INT64_MAX` (sentinel "absent") and against
  `[inks count]`; on either failure, an `os_log` diagnostic is emitted
  (subsystem `"com.apple.pencilkit"`, format string `"Archive had invalid ink
  index: %lu"`, read directly as a literal `cstring` operand) and the index
  is treated as invalid rather than aborting construction. When valid, `inks[
  index]` is fetched via `objectAtIndexedSubscript:` and stored via
  `_setInk:`. When invalid *and* `parent` (the recursion argument, see §2d)
  is non-null, the parent stroke's own `ink` (via plain `ink` accessor) is
  used as a fallback instead, with a second `os_log` message ("Unable to find
  ink from saved inkIndex or parent") emitted only if `parent` is *also* nil.
  This is the first concrete evidence in this directory of ink being
  inherited from a parent stroke.
- `+0x20`: if non-null, a `drawing::Rectangle` C++ object is copy-constructed
  from it (`bl drawing::Rectangle::Rectangle(drawing::Rectangle const&)` -
  confirmed C++ symbol, the same `drawing` namespace `envelope.md` describes
  as the real protobuf parse target) and its four `float32`s widened to
  `double` and passed to `_setBounds:`. **This settles a `point-fields.md`
  "Not concluded" item**: real (non-placeholder) per-stroke bounds, at this
  level of the archive tree, come directly from a live `drawing::Rectangle`
  object reachable from the archive - i.e. this per-stroke `archive` struct
  is not purely a flat C struct the way Function 3's was; at least this one
  field is a genuine pointer into the parsed protobuf `drawing::*` object
  tree. If `+0x20` is null, a fixed global default-transform-shaped constant
  is used instead via `_setTransform:` (see next item) rather than a
  separate bounds default - `_setBounds:` is simply not called in that case.
- Transform (`_setTransform:`): a fixed global 6-double constant (presumably
  an identity affine transform) is always set first. Then, if `archive+0xa8`
  is non-null, two `float32` pairs are read from it (widened to `double`,
  NaN-checked per-component via the same `fcmeq`/`bsl` select idiom
  `point-fields.md`'s Function 2 used for color) and `_setTransform:` is
  called again with the real values where valid, default-constant components
  elsewhere. If `archive+0xa8` is null, `parent`'s own `_transform` is copied
  instead (if `parent` is non-nil) - another parent-inheritance fallback.
- Ink transform (`_setInkTransform:`): same NaN-select pattern, reading from
  `archive+0x38`, but only set at all if `archive+0x38` is non-null - no
  parent-fallback branch for this one.
- `+0x80`: raw value copied directly to `_setFlags:`.
- `+0x70`: if non-null, passed to `+[PKStrokePath(PersistenceAdditions)
  strokeDataFromDataArchive:]` and the result stored via `set_strokeData:`
  (see §3 - this is the edge into the actual point-decoding chain). If null,
  `parent`'s own `_strokeData` is copied instead if `parent` is non-nil;
  if both are absent, `strokeData` is left unset.

### 2c. `+0xc0`/`+0xc8`: `unknownFields`, same `shared_ptr` idiom as Function 3

If `archive+0xc0` is non-null, a `std::shared_ptr`-shaped two-word value
(pointer + control block, with an explicit atomic `ldadd` refcount increment
on the control block at `archive+0xc8`) is moved into the new stroke's
`_unknownFields` ivar (located via `OBJC_IVAR_$__PKStrokeConcrete
._unknownFields`, an exact ivar-offset symbol), releasing any previously
held control block via `std::__1::__shared_weak_count::__release_shared`.
Identical shape to Function 3's drawing-level `unknownFields` handling in
`point-fields.md`, just at a different archive offset (`+0xc0`/`+0xc8`
instead of `+0x80`/`+0x88`).

### 2d. Substrokes: this function recurses into itself

`archive+0x88`/`+0x90` is a begin/end pointer-pair array (element count =
byte-length `>> 3`, i.e. 8-byte pointer-sized entries - same idiom as every
other array in this directory). If non-empty:

- `archive+0xa0`, if non-null, feeds `readStrokeIDFromArchive:
  withSortedUUIDs:` into `set_substrokesVersion:`.
- An `NSMutableArray` is allocated with capacity equal to the element count
  and stored via `set_substrokes:`.
- For each element (a per-substroke archive-entry pointer), the function
  inspects a nested pointer-pair at that entry's own `+0x40` offset (empty or
  not) purely to decide the `isHidden:` argument for the recursive call: if
  that nested pair is non-empty, `isHidden` is taken from bit 0 of the outer
  function's own `isHidden` parameter (threaded straight through); if empty,
  `isHidden` is instead taken from bit 0 of the *just-built parent stroke's
  own* `_flags` value. Either way, the function then calls **itself** -
  `-[_PKStrokeConcrete(PKStrokePersistenceAdditions) initWithArchive:
  sortedUUIDs:inks:parent:isHidden:transientArchiveDictionary:]` - passing
  the substroke's archive-entry pointer as `archive:`, the *same*
  `sortedUUIDs`/`inks`/`transientArchiveDictionary` values threaded down
  unchanged, and the just-built stroke (x23, the enclosing call's own result)
  as `parent:`. The returned child stroke, if non-nil, is appended to the
  parent's `substrokes` array via `addObject:`.

This confirms strokes form a recursive tree (substrokes), built by repeated
self-invocation of this one function, with ink/transform/hidden-state
inheritance from parent to child wherever the child's own archive fields are
absent (§2b/§2d above) - a new structural fact not previously documented in
this directory.

### 2e. Stroke mask and mutation guard

`archive+0x40`/`+0x48` (another begin/end pointer pair): if non-empty, a
fresh `_OBJC_CLASS_$_PKStrokeMask` instance is built via `-[PKStrokeMask
initWithArchive:]`, passing the *entire* outer `archive` struct pointer
(not a sub-field) as its own `archive:` argument, and attached via
`_setStrokeMask:`. Not disassembled further.

As in Function 3, a global mutation-counter value is snapshotted at function
entry and re-checked at the normal return point; on mismatch, an
error/cleanup path runs instead of returning the built stroke - same
unconfirmed-purpose guard noted in `point-fields.md`.

## 3. `+[PKStrokePath(PersistenceAdditions) strokeDataFromDataArchive:]`

Three-instruction-plus-boilerplate wrapper: allocs a `PKStrokePath` and
tail-calls `-[PKStrokePath(PersistenceAdditions) readStrokeDataFromArchive:]`
on it with the same archive-field pointer. Confirmed via `nm -U PencilKit |
grep strokeDataFromDataArchive`.

## 4. `-[PKStrokePath(PersistenceAdditions) readStrokeDataFromArchive:]` - the stride-division site

This is the function the whole `point-fields.md` investigation was ultimately
looking for. Its `archive` argument (x20/x2) is a *third*, distinct small
struct (not the per-stroke struct from §2, not Function 3's per-drawing
struct) - call it the "points archive." Offsets below are on it.

- Allocates a fresh, zero-initialized `0x70`-byte block via a
  `malloc_type`-tagged allocator call, wraps it in a `std::shared_ptr<
  PKStrokePathPointsShared>` (confirmed exact C++ type name from the
  demangled `std::__1::shared_ptr<PKStrokePathPointsShared>` constructor
  symbol), and stores that shared_ptr into the `PKStrokePath` instance at
  `self+0x8` (releasing whatever shared_ptr was previously there). The rest
  of this function populates fields of that freshly-allocated
  `PKStrokePathPointsShared` struct (referred to as `shared` below, its raw
  pointer kept in a register across the function) directly.
- `points_archive+0x54` is a one-byte flag field, tested repeatedly via `tst`
  against several bit masks (`0x2`, `0x8`, `0x10`, `0x20`, `0x40`) to gate
  presence of the fields below - same "presence bitmask" idiom used
  throughout this directory, but a fresh, function-local bitmask distinct
  from anything in §2.
  - bit `0x20` gates `points_archive+0x48` (a `double`, else `0.0`) → stored
    to `shared+0x0`.
  - bit `0x40` gates a byte at `points_archive+0x50` (else a hardcoded `1`) →
    stored, narrowed to one bit, to `self+0x60` (a field on the
    `PKStrokePath` object itself, not `shared`).
  - bit `0x8` gates `points_archive+0x30` (a pointer, else `NULL`) → stored to
    `shared+0x20`.
  - bit `0x2`, combined with `points_archive+0x18` being non-null: reads an
    8-byte pointer-and-length pair (`{ptr, len}`) from `*(points_archive
    +0x18)` and appends `len` bytes from `ptr` into a `std::vector<unsigned
    char>` embedded in `shared` at offset `+0x8` (confirmed from the call to
    `std::__1::vector<unsigned char, ...>::__append(unsigned long)`
    immediately followed by a raw copy). `points_archive+0x10` is also
    stashed into `shared+0x28` alongside this.
- **The stride division itself**: if `points_archive+0x20` (a scalar - the
  per-point byte stride) and `points_archive+0x28` (a pointer) are both
  non-null, the function reads an 8-byte length field from
  `*(points_archive+0x28)+0x8`, computes `length / stride` via `udiv`, and
  stores the quotient into `shared+0x30`. This is the exact `npts = byte_length
  / stride` computation `point-fields.md`'s `.proto` comment describes as
  "implicit, not itself a wire-format construct" - now located precisely.
  Separately (same `points_archive+0x28` object), its own `{ptr, len}` pair
  is copied verbatim into a *second*, independent `std::vector<unsigned
  char>`, this one embedded directly on the `PKStrokePath` object itself at
  `self+0x18` (not on `shared`) - i.e. the raw point bytes end up stored in
  two different places for two different purposes.
- `points_archive+0x40`: stroke-path identity UUID, same
  present-bytes-vs-fresh-`UUID` pattern as everywhere else in this
  directory, stored to `self+0x68`.
- Calls `PKStrokePathPointsShared::computeCachedValues()` (confirmed C++
  instance method, non-inlined `bl`) on `shared` - see §5.
- A handful of remaining fields (`points_archive+0x38`/`+0x54` bit `0x10` →
  `self+0x58`; `points_archive+0x8` or, if flag bit `0` is set instead, `self
  _pointsCount` → `self+0x50`; a second `shared_ptr`-shaped pair at
  `points_archive+0x58`/`+0x60` → `self+0x30`) round out the object but were
  not chased further - out of scope for the stride question.
- `self+0x48` is unconditionally set to the sentinel `0x7fefffffffffffff`
  (just under `DBL_MAX`) at the end - plausibly a "cached bounds not yet
  computed" dirty marker, consistent with `point-fields.md`'s open question
  about where lazily-computed stroke bounds come from, though this pass
  cannot confirm what later reads/clears this sentinel.

## 5. `PKStrokePathPointsShared::computeCachedValues()` - per-field size/offset tables, but not the per-point loop

Confirmed C++ symbol (`__ZN24PKStrokePathPointsShared19computeCachedValuesEv`,
demangled directly by `ipsw`), called on `self=shared` from §4. This function
references two data-section tables by name, both resolved directly from
`adrp`/`add` operands (not inferred): `(anonymous namespace)::sFieldSizes`
and `(anonymous namespace)::sFieldOffsets`. Both are indexed by an 11-entry
(`0xb`), 8-byte-stride array (`lsl #3` scaling), addressed by a per-point
"channel index" `0..10`.

- `shared+0x28` (set in §4 only when `points_archive+0x18` was present and
  its bit `0x2` was set) is read as an **11-bit presence bitmask** - one bit
  per optional per-point channel.
- A first loop walks bits `0..10` of that mask; for each set bit `i`, it
  copies `sFieldSizes[i]` bytes from a running cursor into a channel's slot
  at `sFieldOffsets[i]` within a 32-byte on-stack scratch record, advancing
  the read cursor by `sFieldSizes[i]` each time. The read cursor starts at
  the raw-bytes vector built in §4 (`self+0x8`'s vector `begin()`), so this
  is directly consuming the flat per-point tail bytes - but it runs **once**,
  not once per point: the result (32 bytes) is copied into `shared+0x38`/
  `+0x48` as a single fixed record, not multiplied by the point count from
  `shared+0x30`. This reads as building one reference/template record - e.g.
  default values, or a decode key - not decoding the whole points array.
- A second, structurally similar loop walks a *different* 11-bit mask
  (`shared+0x20`, set in §4 from `points_archive+0x30` when present) and
  builds a per-channel cumulative-offset table into an 11-byte array at
  `shared+0x5c`, with the running total (the byte size of a struct containing
  only this second mask's selected channels) stored to `shared+0x58`, and a
  bit-population-style count (same NEON idiom as the first mask) stored to
  `shared+0x68`.

**This confirms the mechanism behind the observed 12/14/16/18/20/22-byte
strides**: a per-path 11-bit channel-presence mask (`shared+0x28`, ultimately
from `points_archive+0x18`'s referenced struct) selects which of up to 11
optional auxiliary channels are packed after each point's X/Y pair, with
fixed per-channel byte widths (`sFieldSizes`) and fixed per-channel offsets
in an in-memory decoded-point record (`sFieldOffsets`) - the sum of the
selected channels' sizes is exactly the "stride minus 8" tail
`point-fields.md` measured empirically. **However, this function itself does
not loop over all `npts` points** - it computes shared per-channel metadata
once. The actual per-point walk that applies this metadata to every point in
the flat buffer (multiplying by `shared+0x30`'s count) is still not located -
see "Not concluded."

## 6. `sFieldSizes`/`sFieldOffsets` contents - found via their writer, not just their reader

An earlier revision of this file concluded the two tables' numeric contents
were unrecoverable from static analysis, since both addresses
(`0x1eb219438`/`0x1eb2193e0`) fall inside `PencilKit`'s `__DATA,__common`
section (a zerofill/BSS-class section with no on-disk file backing -
`otool -l` shows `offset 0` for it), and only *reader* call sites
(`computeCachedValues()`, `updateFromCachedValues()`) had been checked. **That
conclusion was wrong** - it never looked for the *writer*. `nm -U PencilKit`
shows a `dispatch_once`-token local symbol, `__ZZ32+[PKStrokePath
calculateOffsets]E9onceToken`, sitting at `0x1eb219388` - immediately
adjacent to `sFieldOffsets`/`sFieldSizes` in the same `__common` section
(all three fall within the same 176-byte common block). This is the writer's
entry point.

`+[PKStrokePath calculateOffsets]` (confirmed selector) is a plain
`dispatch_once_f` call site: it builds a stack-based ObjC block (pointer-auth
signed, as usual on arm64e) and calls `dispatch_once_f` against that
once-token, guaranteeing the block runs exactly once per process lifetime.
The block, `___32+[PKStrokePath calculateOffsets]_block_invoke` (confirmed
symbol), does nothing itself but load one captured value and tail-call
`+[PKStrokePath _calculateOffsets:sizes:]` (confirmed selector) with `x2` =
the address of `sFieldOffsets` and `x3` = the address of `sFieldSizes` -
i.e. this method's two out-parameters *are* the two `__common` tables,
passed by address.

`+[PKStrokePath _calculateOffsets:sizes:]`'s body writes to both output
addresses using nothing but `ldr q`/`stp q`/plain-immediate `mov`+`str`
instructions - nine total memory writes, all either copying a 16-byte
literal pair straight from a fixed, file-backed address range
(`0x1ae769170`-`0x1ae7691e0`, inside `PencilKit`'s `__TEXT` segment, i.e.
ordinary compiled-in constant data, not computed) or storing a bare
immediate (`#0x20`, `#0x2`) directly. There is no arithmetic, no branch, no
external call anywhere in this function - it is a pure, unconditional
literal-constant store, run once via `dispatch_once`. Because the source
literals live in `__TEXT` (file-backed, unlike the `__common` destination),
they were extracted with `dd` at the corresponding file offset (`__TEXT`
segment `fileoff` is `0` and `vmaddr` is `0x1ae60f000` for this binary, so
file offset = virtual address − `0x1ae60f000`), the same technique
`legacy_drawing.md` used for a jump table - the earlier conclusion that this
data "requires a live memory read" only held for the `__common` destination
addresses, not for the `__TEXT` source literals the writer copies from.

Decoded as eleven consecutive 8-byte little-endian integers each (matching
the `sFieldSizes[11]`/`sFieldOffsets[11]` indexing `computeCachedValues()`
and `updateFromCachedValues()` use), indexed by the same 0-10 channel/bit
index as everywhere else in this file:

| channel index (bit in the 11-bit presence mask) | `sFieldOffsets[i]` | `sFieldSizes[i]` |
|---|---|---|
| 0 | 0  | 8 |
| 1 | 8  | 4 |
| 2 | 12 | 4 |
| 3 | 16 | 2 |
| 4 | 18 | 2 |
| 5 | 20 | 2 |
| 6 | 22 | 2 |
| 7 | 24 | 2 |
| 8 | 26 | 2 |
| 9 | 28 | 4 |
| 10 | 32 | 2 |

Internally self-consistent: `sFieldOffsets[i]` is exactly the running sum of
`sFieldSizes[0..i-1]` (e.g. offset `12` at index 2 = size `8` + size `4` from
indices 0-1) - i.e. this is a real packed-record layout table, offsets are
derived from sizes in ascending bit-index order, not independently chosen.
If every one of the 11 channels were simultaneously present, the full
packed tail would be `32 + 2 = 34` bytes (offset of the last field plus its
own size), for a total per-point record of `8 (X/Y) + 34 = 42` bytes -
larger than any stride `point-fields.md` observed empirically (max `22`
bytes total, i.e. max `14` bytes of tail). This is not a contradiction: the
observed samples simply never had all 11 channels enabled simultaneously in
one path's presence mask - a tail of `14` bytes is reachable from several
different subsets of `{8,4,4,2,2,2,2,2,2,4,2}` (e.g. six of the `2`-byte
channels, or one `4` plus five `2`s, etc.), and this pass has no way to
determine, from the size table alone, exactly which subset any given sample
used - that still requires reading each sample's own presence-mask byte
(`points_archive+0x18`'s referenced struct in §4), which was not attempted
this pass. **What this table conclusively establishes** is the *number* of
optional channels (11, not some other count), their *individual byte
widths* (`8,4,4,2,2,2,2,2,2,4,2`, in this fixed bit-index order), and that
they are packed contiguously with no padding - a materially stronger result
than "the tail is 4-14 bytes of something," even without yet knowing which
channel bit corresponds to which semantic name (force/azimuth/altitude/
timestamp/etc.) or which bits were set in any specific observed sample.

No named symbol, string constant, or enum-like identifier tying these 11
indices to semantic field names (e.g. "azimuth", "force") was found anywhere
in `PencilKit`'s symbol table or string tables during this pass - a targeted
`nm`/`strings` grep for those terms surfaced only unrelated PKInk/
PKToolConfiguration azimuth-related API and no connection to this specific
table. The size values themselves are suggestive by common sense (a `4`-byte
field is plausibly a `float32`; a `2`-byte field is plausibly a quantized
`int16`/`fixed16`; the one `8`-byte field, index 0, is plausibly a
`double`-precision timestamp) but none of that is confirmed.

## Not concluded

- The actual per-point iteration that applies `sFieldSizes`/`sFieldOffsets`
  (or the `shared+0x5c` cumulative-offset table) to every point in the
  `shared+0x8` raw-bytes vector, `shared+0x30` times - not located. Likely
  candidates: `PKStrokePathPointsShared::updateFromCachedValues()` (confirmed
  sibling symbol, `__ZN24PKStrokePathPointsShared22updateFromCachedValuesEv`,
  not disassembled this pass) or an on-demand point-accessor method on
  `PKStrokePath` itself.
- ~~Numeric contents of `sFieldSizes`/`sFieldOffsets`~~ **Resolved, see §6**:
  found via their writer (`+[PKStrokePath calculateOffsets]` →
  `_calculateOffsets:sizes:`, `dispatch_once`-guarded, pure literal-constant
  stores) rather than their readers. Still open: which bit/index corresponds
  to which semantic channel name, and which bits are actually set in any
  given real sample.
- What the two independent 11-bit masks (`shared+0x28` from
  `points_archive+0x18`, `shared+0x20` from `points_archive+0x30`) actually
  mean relative to each other - one reads as "which channels are present on
  disk," the other as "which channels the caller wants materialized," but
  this is inferred from shape, not confirmed by any named field.
- `-[_PKStrokeConcrete readStrokeIDFromArchive:withSortedUUIDs:]` - called
  from three sites in §2b/§2d, confirmed exact selector, not itself
  disassembled; its 24-byte result shape (UUID-sized value across two stack
  slots) is inferred from the caller's stack layout only.
- `-[PKStrokeMask initWithArchive:]`, `+[_PKStrokeConcrete
  decodeRenameableUUIDFromData:transientArchiveDictionary:]` - confirmed call
  targets, not disassembled.
- Whether `points_archive` (§4's argument) is populated from the same parsed
  `drawing::Drawing` C++ tree §2b's `drawing::Rectangle` bounds field came
  from, i.e. whether it's a pointer straight into `Geometry.points`'s parsed
  protobuf representation or something already copied out - not traced back
  further than `-[_PKStrokeConcrete(...) initWithArchive:sortedUUIDs:inks:
  parent:isHidden:transientArchiveDictionary:]`'s own `archive+0x70` field
  (§2b), which is where `points_archive` (this function's `archive`
  argument) actually comes from.

## Provenance

Same source Mac/OS/build and toolchain as the other files in this directory
(macOS 26.6.2, build 25G83, Apple Silicon T8103; `ipsw` v3.1.714; `otool`
from Xcode Command Line Tools; `nm`). Used `otool -l` (segment/section
listing) to determine that `sFieldSizes`/`sFieldOffsets` live in a zerofill
`__DATA,__common` section with no on-disk file backing. §6's writer
(`+[PKStrokePath calculateOffsets]`) was located, after that dead end, by
`nm -U PencilKit | grep -iE 'field|once'` turning up a `dispatch_once`-token
local symbol (`__ZZ32+[PKStrokePath calculateOffsets]E9onceToken`) sharing
the same `__common` block as the two tables - a naming-adjacency technique
not used elsewhere in this directory. Also attempted `ipsw dyld xref`
(a `[WIP]`-flagged ipsw subcommand for cross-reference search directly
against the live dyld shared cache) and a brute-force whole-`__TEXT.__text`
disassembly-plus-grep of the extracted `PencilKit` binary as parallel,
more-exhaustive approaches to the same writer-search question; both were
technically successful (the whole-section disassembly completed and its
`adrp 0x1eb219000` hits were reviewed) but contributed no additional writer
sites beyond what the naming-adjacency approach already found - the
`dispatch_once`/`_calculateOffsets:sizes:` chain is the only writer for
these two tables in `PencilKit`. `dd` was used twice: once (per §5, as
before) attempting to read the `__common` destination addresses directly
(no file backing, zero bytes, as expected), and a second time, successfully,
against `__TEXT` segment file offset `0x15a170` (computed from
`vmaddr 0x1ae60f000`, `fileoff 0`) to read the sixteen file-backed 8-byte
literals `+[PKStrokePath _calculateOffsets:sizes:]` copies into the two
tables - the actual source of §6's decoded values. Method, class, C++
symbol, and constant identifiers referenced above (e.g.
`initWithArchive:sortedUUIDs:inks:parent:isHidden:transientArchiveDictionary:`,
`readStrokeIDFromArchive:withSortedUUIDs:`,
`decodeRenameableUUIDFromData:transientArchiveDictionary:`,
`drawing::Rectangle::Rectangle`, `OBJC_IVAR_$__PKStrokeConcrete
._unknownFields`, `strokeDataFromDataArchive:`, `readStrokeDataFromArchive:`,
`PKStrokePathPointsShared`, `computeCachedValues`, `updateFromCachedValues`,
`sFieldSizes`, `sFieldOffsets`, `calculateOffsets`, `_calculateOffsets:
sizes:`) are exact, verbatim symbol names from `PencilKit.framework`'s
symbol table/disassembly, extracted from the macOS 26.6.2 (25G83) arm64e
dyld shared cache in a prior session (not re-extracted here). The two
`os_log` literal strings ("Archive had invalid ink index: %lu", "Unable to
find ink from saved inkIndex or parent") are exact, verbatim string
constants read directly as instruction operands. The `sFieldSizes`/
`sFieldOffsets` table *values* in §6 are decoded integers derived from raw
bytes read via `dd`, reported as a decimal table rather than as raw
hex/binary content, consistent with this directory's no-raw-bytes
convention; no disassembly, hex dump, or extracted binary content is
otherwise reproduced here or committed to this repo.

## Call graph (this file's additions)

Confirmed call edges only, extending `point-fields.md`'s graph from node K.

```mermaid
graph TD
  K["-[_PKStrokeConcrete(PKStrokePersistenceAdditions)<br/>initWithArchive:sortedUUIDs:inks:transientArchiveDictionary:]<br/>(4-instr wrapper, parent=nil, isHidden=NO)"]
  K2["-[_PKStrokeConcrete(PKStrokePersistenceAdditions)<br/>initWithArchive:sortedUUIDs:inks:parent:isHidden:transientArchiveDictionary:]<br/>(real implementation; self-recursive for substrokes)"]
  P["+[PKStrokePath(PersistenceAdditions)<br/>strokeDataFromDataArchive:]"]
  Q["-[PKStrokePath(PersistenceAdditions)<br/>readStrokeDataFromArchive:]<br/>(npts = byte_length / stride computed here)"]
  R["PKStrokePathPointsShared::computeCachedValues()<br/>(sFieldSizes/sFieldOffsets metadata; not the per-point loop)"]
  S["-[PKStrokeMask initWithArchive:]"]
  T["-[_PKStrokeConcrete readStrokeIDFromArchive:withSortedUUIDs:]"]
  U["+[_PKStrokeConcrete<br/>decodeRenameableUUIDFromData:transientArchiveDictionary:]"]

  K --> K2
  K2 -->|recursive, once per substroke| K2
  K2 --> P
  K2 --> S
  K2 --> T
  K2 --> U
  P --> Q
  Q --> R
```

`K` is the same node `K` in `point-fields.md`'s call graph. This file
resolves it fully and shows it bottoms out, for point data specifically, at
`computeCachedValues()` - which itself defers the real per-point stride walk
to a still-unidentified caller of `updateFromCachedValues()` or an on-demand
accessor (see "Not concluded").
