# `legacy_drawing::*` - the real generated protobuf parsers

`envelope.md` and `point-fields.md` in this directory both trace hand-written
Objective-C wrapper/conversion code that operates on an *already-parsed*
representation of the `com.apple.drawing.2` blob - three functions deep in
`point-fields.md`'s case, and neither file ever located the code that
actually unpacks raw protobuf wire-format bytes. This file is a new
direction: `nm -U PencilKit | grep -i legacy` (broader than any grep run for
the other two files) turns up an entire C++ namespace, `legacy_drawing`, of
real compiler-generated protobuf message types - each with its own
`readFrom(PB::Reader&)` deserializer. `PB::Reader` is Apple's own internal
C++ protobuf-like reader library, and is a **different, unrelated type**
from the `drawing::Drawing` C++ object `envelope.md` describes being handed
to a virtual-call protobuf parser - despite the similarly-shaped names, they
are not the same thing. This file covers only what's reachable from
disassembling `legacy_drawing::Command::readFrom(PB::Reader&)`, per this
session's explicit scoping constraint (see "Not concluded" for what's
deliberately left for a future session).

## 1. `legacy_drawing::*` type inventory (running index)

From `nm -U PencilKit | grep -i legacy`, confirmed 2026-09-11. Each type also
carries the usual generated-code retinue (copy/move ctors, destructor,
`writeTo(PB::Writer&)`, `formatText(...)`, `hash_value()`, `operator==`,
`swap()`, RTTI/vtable symbols) - omitted below since none of it was examined
and it's boilerplate.

| Type | `readFrom` examined? | Notes |
|---|---|---|
| `legacy_drawing::Command` | **Yes (this file)** | Also has `make*` accessors: `makeBounds`, `makeCommandID`, `makeTimestamp`, `makeBaseValues`, `makeClipNormal`, `makeClipOrigin`, `makeParameters`, `makeColor` - none of these 8 were themselves disassembled; used only as corroborating names below. |
| `legacy_drawing::Drawing` | No | Has `makeTimestamp`, `makeUnrotatedBounds`. Confirmed (see §2) to be the type that owns a `std::vector<std::unique_ptr<Command>>` - i.e. `Drawing` is the top-level message with a repeated `Command` field. Best next target - see "Not concluded". |
| `legacy_drawing::Point` | No | Confirmed call target from `Command::readFrom` (4 distinct dispatch sites - one repeated field, three singular fields). Not disassembled itself. |
| `legacy_drawing::Color` | No | Confirmed call target from `Command::readFrom` (1 field). |
| `legacy_drawing::Rectangle` | No | Confirmed call target from `Command::readFrom` (1 field). |
| `legacy_drawing::CommandID` | No | Confirmed call target from `Command::readFrom` (2 distinct fields, same type). |
| `legacy_drawing::Parameters` | No | Confirmed call target from `Command::readFrom` (1 field). |
| `legacy_drawing::VectorTimestamp` | No | No confirmed call edge from `Command::readFrom` in this pass. |
| `legacy_drawing::VectorTimestampClock` | No | No confirmed call edge from `Command::readFrom`. Its demangled type name is incidentally attached to a shared/folded vector-growth error-path symbol referenced from `Command::readFrom` - almost certainly a linker identical-code-folding (ICF) artifact, not a real reference; see §2. |
| `legacy_drawing::VectorTimestampClockReplicaClock` | No | No confirmed call edge from `Command::readFrom`. |

## 2. `legacy_drawing::Command::readFrom(PB::Reader&)`

`PencilKit.framework`, extracted from the macOS 26.6.2 (25G83) arm64e dyld
shared cache via `ipsw dyld extract`, disassembled via `ipsw macho disass
PencilKit -s '__ZN14legacy_drawing7Command8readFromERN2PB6ReaderE'
--demangle --force` (the raw mangled symbol; the demangled C++ signature
form was tried first and did not resolve, so the mangled form was used
instead - the opposite fallback direction from what was anticipated).

### Overall shape

This is not Objective-C - there is no `objc_msgSend` anywhere in this
function. The `PB::Reader&` argument (`x1` on entry) is a plain C++
reference to a struct with (at least) three relevant fields read directly at
fixed offsets by this function: a data pointer at `+0x0`, a cursor/position
at `+0x8`, an end pointer at `+0x10`, and a one-byte flag at `+0x18`
(checked/set at function entry and at each loop iteration boundary - reads
as an error/"done" latch, though its exact semantics beyond that weren't
traced further). Tag parsing (the protobuf varint that encodes `(field_number
<< 3) | wire_type`) is **inlined directly into this function** - there is no
call to a `PB::Reader::readTag()`-shaped method. The inlined decode is the
textbook byte-at-a-time varint loop: read a byte, keep its low 7 bits,
shift left by a running bit-count, OR into an accumulator, continue while
the byte's high bit is set (checked via `tbz ..., #0x7`), stop otherwise -
appearing twice in the function (once for the type-parameterized skip/retry
path, once in the main loop), consistent with the compiler unrolling the
same inline logic at more than one call site rather than sharing one copy.

Once a tag is decoded, `wire_type = tag & 0x7` and `field_number = tag >>
3`. If `wire_type == 4` (protobuf's deprecated `SGROUP`/end-group marker),
the function treats this as "stop parsing, return success" - it does not
attempt to look up a field for it. This is a real, if unusual, code path
observed directly in the disassembly; its correctness given that this
message's own submessage fields are otherwise strictly length-delimited
(see below) is not something this pass can judge - noted as an observed
quirk, not fully explained.

Otherwise, `field_number - 1` is bounds-checked against `9` (unsigned
compare): if out of range (i.e. `field_number` is `0` or `> 10`), execution
falls through to a call to a shared, unnamed helper at a fixed address
(referred to below as **skip-helper**, called with the reader, `wire_type`,
and two zeroed arguments) that is presumably `PB::Reader`'s generic
"consume and discard an unrecognized field" routine, shared across every
`readFrom` in this binary - not specific to `Command`, and not itself
disassembled. The same call site is also reached, with all arguments
zeroed, if the tag's own varint decode ran the maximum 10 bytes without
terminating (a malformed/overlong tag) - i.e. this one shared call also
functions as the malformed-tag error path.

If `field_number` is in `1..10`, control passes through a genuine ARM64
jump table (`adr`+scaled-load+indirect `br`) with one entry per valid field
number. **The jump table's contents aren't visible in ordinary disassembly
listings** (the disassembler just shows the indirect branch), so to
determine which of the ten basic blocks corresponds to which field number,
the jump table's raw bytes were located via `otool -l` (finding which
section of the extracted Mach-O contains the table's virtual address - it
turned out to be embedded directly inside `__TEXT,__text`, i.e. inline
literal data rather than a dedicated section) and then read with `dd`
against the extracted binary file at the corresponding file offset. This
is the only place in this pass where raw section/file bytes were consulted
directly rather than relying on `ipsw`'s instruction-level output; per the
prose-only constraint, the raw byte values themselves are not reproduced
here - only the resulting field-number-to-offset mapping below, which is
what matters.

### Confirmed per-field breakdown

Each of the ten dispatch targets was identified by what it does with the
`Command` object pointer (`x0` at entry, kept in a callee-saved register
across the whole function) and, for submessage fields, by a literal,
unambiguous data reference to `vtable for legacy_drawing::<Type>` visible
right where the newly-allocated field object's vtable pointer is stored -
this is the most solid signal in the whole function, since it's a direct
address computation (`adrp`/`add`) against a named symbol, not an inference
from register contents.

| field # | wire type | `Command` member offset | confirmed type | corroborating name |
|---|---|---|---|---|
| 1 | length-delimited (submessage) | `+0x30` | `CommandID` | matches `makeCommandID()` |
| 2 | varint (scalar) | value `+0x60`, presence bit `+0x64` (bit `0x1`) | plain integer, stored as 32-bit despite a full 64-bit-capable varint decode | best match by elimination is `makeTimestamp()` - the only `make*` name with no corresponding submessage type found among fields 1/3/4/6/7/8/9/10 below |
| 3 | length-delimited | `+0x28` | `Color` | matches `makeColor()` |
| 4 | length-delimited | `+0x8` | `Point` | not confidently matched to a specific `make*` name - see below |
| 5 | length-delimited, **repeated** | `+0x40`/`+0x48`/`+0x50` (begin/end/capacity-end pointer triple - standard `std::vector` layout) | `std::vector<std::unique_ptr<Point>>` | no `make*` name (repeated fields don't get one in this codegen, matching field 5 being the only one of the ten with no corresponding `make*` method) |
| 6 | length-delimited | `+0x58` | `CommandID` | second, distinct `CommandID`-typed field; `Command`'s own `make*` list has only one `makeCommandID`, so this field's real name is unconfirmed |
| 7 | length-delimited | `+0x10` | `Rectangle` | matches `makeBounds()` |
| 8 | length-delimited | `+0x20` | `Point` | not confidently matched to a specific `make*` name - see below |
| 9 | length-delimited | `+0x18` | `Point` | not confidently matched to a specific `make*` name - see below |
| 10 | length-delimited | `+0x38` | `Parameters` | matches `makeParameters()` |

For every length-delimited (submessage) field, the same shared sequence
runs: allocate a fixed-size block (`0x18`, `0x20`, or `0x28` bytes,
matching `CommandID`/`Parameters`, `Color`/`Rectangle`, and `Point`
respectively) via a shared, unnamed allocate+construct helper; stamp the
new object's vtable pointer (pointer-authenticated, ARM64e `pacda`) to the
literal `vtable for legacy_drawing::<Type>` address; store the new pointer
into the `Command` member slot, releasing/destroying whatever was
previously there (via the outgoing object's own virtual destructor slot,
also pointer-authenticated) if non-null; then - in a second, shared block
common to *all* nine of these dispatch sites - call another shared,
unnamed helper (referred to below as **begin-length-delimited**) that reads
the field's length prefix and narrows the reader's bounds to just that
submessage's bytes; make a **virtual call** through the new object's own
vtable (offset `+0x10` past the vtable pointer, i.e. skipping the two
Itanium-ABI RTTI/offset-to-top slots - the first real virtual method slot,
consistent with `readFrom` being declared first in each generated class)
passing the same reader reference; and finally call a third shared, unnamed
helper (**end-length-delimited**) to restore the reader's outer bounds.
Control then rejoins the main tag-decode loop. None of these three shared
helper addresses were themselves disassembled (out of this session's
scope), but the call pattern - allocate typed object, narrow reader, make a
*virtual* call into that object bearing the demangled name `readFrom` per
`ipsw`'s own call-target resolution for the analogous vtable slot on other
`legacy_drawing::*` types, then widen the reader back out - is unambiguous
and consistent across all nine submessage fields including the repeated
one.

### The repeated-submessage-vs-raw-bytes question: **answered - repeated submessage, standard tag-delimited encoding**

Field 5 is the one whose dispatch block differs from the others in exactly
the way a `repeated` (as opposed to `optional`) embedded-message field
should: instead of loading/storing a single pointer field, it treats
`Command+0x40`/`+0x48`/`+0x50` as a `std::vector<std::unique_ptr<Point>>`'s
begin/end/capacity-end pointers, checks whether there's spare capacity
(`end == capacity-end` triggers a grow-the-vector path that computes a new
capacity, calls a shared allocator helper, moves the existing elements, and
updates all three pointers), constructs a **new** `Point` object (`0x28`
bytes, vtable-stamped for `legacy_drawing::Point`) exactly as the singular
Point fields do, appends its pointer as the new last element, and then runs
through the exact same begin-length-delimited / virtual-`readFrom` /
end-length-delimited sequence described above - once per repeated element,
each iteration re-entering the outer tag-decode loop to read the *next*
tag, which for consecutive repeated-field wire entries will again be field
5's tag, appending another `Point`.

This directly answers the question this whole session was chasing: at the
`legacy_drawing::Command` level, points are **not** carried as a raw
`bytes`-typed field with custom flat-buffer packing. They are ordinary
`proto2` `repeated` embedded `Point` messages, each individually
tag-delimited and each parsed by a real recursive `Point::readFrom` call.
The `.proto` file's `Geometry.points` field (`tools/apple-notes-drawing.proto`,
a *different*, envelope-level message reached via the ordinary `protoc
--decode_raw`-derived schema, not `legacy_drawing::Command`) - whose
empirically-observed flat/fixed-stride byte layout motivated this whole
investigation - is therefore **not modeling the same thing** as
`legacy_drawing::Command`'s field 5. They're either: (a) two different,
coexisting on-disk schemas for the same conceptual data (plausible, given
`legacy_drawing::Command`'s very "legacy" namespace name, and given
`envelope.md`/`point-fields.md`'s C-struct-consuming functions already
being a step removed from any protobuf parse), or (b) `legacy_drawing::*`
is simply a wholly separate historical format from the `com.apple.drawing.2`
blob's own `Drawing`/`Stroke`/`Geometry` schema, unrelated in practice
despite superficial naming overlap. This pass cannot distinguish (a) from
(b) - see "Not concluded".

### The three singular `Point` fields (4, 8, 9) and a cross-file coincidence

Fields 4, 8, and 9 are all singular (non-repeated) `Point`-typed fields, at
`Command` offsets `+0x8`, `+0x20`, and `+0x18` respectively. `Command`'s own
`make*` list has exactly two Point-shaped candidate names,
`makeClipNormal()` and `makeClipOrigin()`, which fits fields 8 and 9 (offsets
`+0x20`/`+0x18`) far better than it fits three fields - leaving field 4
(offset `+0x8`) unaccounted for by name, with `makeBaseValues()` as the only
remaining unclaimed `make*` name (unconfirmed pairing).

Worth flagging explicitly: `point-fields.md`'s Function 2
(`-[_PKStrokeConcrete(PKStrokePersistenceAdditions)
initWithLegacyArchive:sortedUUIDs:]`) describes its own, *completely
different*, plain-C `archive` struct argument (not this `Command` C++
object) as having: a point at `archive+0x8` read first as a special
baseline point; and two more points at `archive+0x18`/`archive+0x20` that
are **not** ordinary path points but feed directly into constructing a clip
plane (`_PKStrokeClipPlane initWithOrigin:normal:`). That is the exact same
set of three offsets (`+0x8`, `+0x18`, `+0x20`) found here for `Command`'s
three singular `Point` fields, with the same odd one-out-from-clip-plane
shape (one "first/special" point plus a clip-origin/clip-normal pair). This
is a striking match, but it is **not proof** the two structs are the same
memory layout or even directly related - offsets like these are also just
common/small numbers that could coincide by chance in two unrelated 0x68-ish
structs with a handful of pointer/struct fields. Flagged as strongly
suggestive circumstantial evidence, not a confirmed link, and not chased
further this session per the single-function scope constraint.

### The `std::vector<unique_ptr<legacy_drawing::Command>>` red herring

While isolating field 5's vector-growth path, the disassembly's call
targets include a function whose `ipsw`-resolved demangled name is
`std::__1::__allocate_at_least<std::__1::allocator<std::__1::unique_ptr<legacy_drawing::Command, ...>>>(...)`
- i.e. it looks, by name, like it's growing a `vector<unique_ptr<Command>>`,
not a `vector<unique_ptr<Point>>`. Likewise, the length-error throw path
reachable from the same growth logic resolves to a name mentioning
`legacy_drawing::VectorTimestampClock`, not `Point` or `Command` either.
Both are almost certainly linker identical-code-folding (ICF) artifacts:
the generated machine code for growing *any* `std::vector` of
pointer-sized `unique_ptr<T>` elements is byte-for-byte identical regardless
of `T` (it never dereferences the pointee), so the linker collapses many
templates' worth of these helper instantiations into one physical function
and arbitrarily keeps one of their names for symbolication. The actual
element type being grown here, per the vtable-stamping evidence immediately
surrounding the call (§ above), is unambiguously `Point`. The genuinely-named
`vector<unique_ptr<Command>>` is very plausibly real, though - `nm`'s
top-level symbol listing (§1) does show this same instantiation family
independently, and `legacy_drawing::Drawing` (not examined this session)
is the natural owner of a repeated `Command` field, matching `Drawing`'s
`make*` list (`makeTimestamp`, `makeUnrotatedBounds` - no repeated-field
`make*`, consistent with the pattern established above where repeated
fields don't get one). This is a good next-session target, not
disassembled here.

## 3. Not concluded

- Whether `legacy_drawing::Command`'s repeated `Point` field (field 5,
  offset `+0x40`/`+0x48`/`+0x50`) is the *same* on-disk data as the
  `.proto`'s `Geometry.points` flat buffer under a different, older/newer
  encoding, or an entirely separate, unrelated schema that happens to share
  vocabulary (both being "legacy" PencilKit formats). Not resolved this
  pass - would require finding where a `legacy_drawing::Drawing`/`Command`
  tree is actually invoked from (i.e. its caller), which was out of scope.
- Field 2's exact semantic identity (`makeTimestamp()` was inferred purely
  by elimination against the `make*` name list, not by any direct
  cross-reference).
- Field 4's identity (candidate `makeBaseValues()`, unconfirmed) and which
  of fields 8/9 is `makeClipNormal()` vs. `makeClipOrigin()` specifically
  (offsets alone don't disambiguate which name goes with which).
- Field 6's identity - a second `CommandID`-typed field with no obviously
  matching `make*` name in `Command`'s own inventory.
- Whether the offset coincidence between this `Command`'s three singular
  `Point` fields (`+0x8`/`+0x18`/`+0x20`) and `point-fields.md`'s
  `archive` struct (same three offsets, same "special point" + "clip
  origin/normal pair" shape) reflects a real relationship or is
  coincidental - flagged as suggestive, not proven.
- The exact names/behavior of the shared, unnamed helpers called from
  every submessage-field dispatch site (allocate+construct object;
  begin-length-delimited; end-length-delimited; the generic
  unknown-field/malformed-tag skip routine). Not disassembled - natural
  next targets since they're used by every `legacy_drawing::*::readFrom`,
  not just `Command`'s.
- `legacy_drawing::Drawing::readFrom` itself - the natural top-level
  entry point, and the confirmed owner of a `vector<unique_ptr<Command>>`
  per §2's discussion of the ICF-folded growth-helper symbol. Not
  disassembled this session (scope was `Command::readFrom` only).
- `legacy_drawing::Point::readFrom`, `Color::readFrom`,
  `Rectangle::readFrom`, `CommandID::readFrom`, `Parameters::readFrom` -
  all confirmed call targets from `Command::readFrom` (§2), none
  disassembled themselves. `Point::readFrom` in particular is the obvious
  next target given the session's original motivating mystery (per-point
  auxiliary channel bytes) is about `Point`-shaped data specifically.
- `legacy_drawing::VectorTimestamp`, `VectorTimestampClock`,
  `VectorTimestampClockReplicaClock` - no confirmed call edge from
  `Command::readFrom` at all in this pass (see §1); whether they're
  reachable from `Drawing::readFrom` or elsewhere is unknown.
- What ultimately calls into any `legacy_drawing::*` type's `readFrom` at
  all - i.e. whether this namespace is even reachable from the
  `com.apple.drawing.2` blob-read path examined in `envelope.md`/
  `point-fields.md`, or belongs to some other on-disk format PencilKit
  also happens to support. Not traced this pass.

## 4. Provenance

- Source Mac: macOS 26.6.2 (build 25G83), Apple Silicon (T8103), examined
  2026-09-11 - same source Mac/OS/build as `envelope.md` and
  `point-fields.md` in this directory.
- Tooling: `otool` (Xcode Command Line Tools), `ipsw` v3.1.714
  (blacktop/ipsw), `nm`, `xcrun c++filt`, `dd` (used once, to read the raw
  bytes of a jump table embedded in `__TEXT,__text`, located via `otool
  -l`'s section listing - see §2; no other raw byte/hex dumps were taken or
  are reproduced here).
- Binary: `PencilKit.framework` (public framework), extracted from
  `dyld_shared_cache_arm64e`, same extraction as the other two files in
  this directory (not re-extracted this session).
- All `legacy_drawing::*` type, method, and field-name (`make*`) identifiers
  referenced above are exact, verbatim symbol names from that binary's
  symbol table, obtained via `nm -U PencilKit | grep -i legacy` and
  cross-checked with `xcrun c++filt` demangling. `Command` member offsets
  and field numbers are read directly from disassembly/the jump table, not
  guessed. No disassembly, raw bytes, or extracted binary content is
  reproduced here or committed to this repo.
