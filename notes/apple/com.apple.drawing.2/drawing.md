# Real field names for `drawing::Stroke`/`drawing::Drawing`/`drawing::Ink` - and `ink_category` almost certainly isn't real

Session date 2026-09-12, same SSH access/extraction as
`point-decode-pipeline.md` §8-§9 (`cooper.plug.rapidcow.net`, macOS 26.6.2
build 25G83, `~/re-notes/PencilKit` + `PencilKit.a2s`). This file is
deliberately **not** a correction to `envelope.md`/`point-fields.md`/
`point-decode-pipeline.md` - those stay as-written, as the historical
record of what was known/guessed at the time. This file records a later,
more direct finding that supersedes some of those guesses in practice; see
each file's own text for what it originally claimed and why (and
`point-decode-pipeline.md`'s "Not concluded" now points forward to this
file for the `ink_category` question specifically).

**Status as of 2026-09-12, later same day**: acted on. The findings below
are no longer just a recommendation for "any future `.proto` rewrite" - a
new `tools/apple-notes-drawing-2.proto` (kept alongside, not replacing, the
original `apple-notes-drawing.proto`) applies the renames this file
justifies, and `tools/apple-notes-drawing-decode.pl` now parses against
that file. Concretely: `StrokeSeqInfo`/`seq`/`ink_category` are gone,
replaced by `StrokeUnknown2`/`f1`/`f2`/`f3` (the decoder now prints these as
an unlabeled `(unknown2 ...)` triple, not as asserted `seq`/
`ink-category-raw` values); `Geometry`/`BBox`/`InkDef` are renamed to
reference `stroke_data`/`bounds`/`Ink` per this file's findings, kept under
their old internal shapes where the real wire field numbers still aren't
known (see "Not concluded" below - unchanged by this update).

## Method: `formatText` prints real field names as literal strings

`envelope.md`/`point-decode-pipeline.md` already established that Apple's
actual parse target is a genuine, non-Google, in-house protobuf-like
library operating on C++ classes in a `drawing::` namespace (e.g.
`drawing::Rectangle`, confirmed via a real C++ copy-constructor symbol).
This session confirms that's a full protoc-style code-generation setup,
not just one struct: every `drawing::*` message type has a matched set of
`readFrom(PB::Reader&)`, `writeTo(PB::Writer&) const`,
`formatText(PB::TextFormatter&, char const*) const`, and `hash_value()
const` methods (`PB::Reader`/`PB::Writer`/`PB::TextFormatter` being Apple's
own in-house protobuf-equivalent runtime, not Google's `google::protobuf`).

`formatText` is the useful one here: a text-format serializer (protobuf's
own `TextFormat` convention) necessarily writes each field's *real name* as
a literal string, in field-declaration order, with no guessing required -
unlike `protoc --decode_raw` against raw bytes, which only sees wire
types/lengths and has no access to field names at all. Found each class's
`formatText` via `nm -U ~/re-notes/PencilKit | c++filt | grep -E
'drawing::(Stroke|Drawing|Ink)::'`, then disassembled it and pulled every
string literal:

```
ipsw macho disass ~/re-notes/PencilKit -a <formatText's address> --demangle --force \
  | grep -oE '"[^"]+"' | sort -u
```

(Symbolic `-s '<demangled name>'` disassembly failed here - `ipsw`'s symbol
lookup doesn't cope well with a demangled name containing spaces/parens/
`::` - so this used `-a <vaddr>` directly, taking the address straight from
`nm`'s output instead.)

## `drawing::Stroke` - real fields (from `formatText` at `0x1ae648610`)

```
bounds, centerlineSlices, groupUUID, inkIndex, inkTransform, maskPoints,
renderGroupUUID, shapeType, sortID, strokeData, strokeUUID, styleFlags,
substrokes, substrokesVersion, transform, version
```

Fifteen fields. **There is no `seq`, `ink_category`, or any
`StrokeSeqInfo`-shaped nested message anywhere in this list.** What exists
instead is a plain scalar called **`inkIndex`** - an index into
`Drawing.inks[]` - which matches, by name, the mechanism
`point-decode-pipeline.md` §2b already confirmed independently and by a
completely different route (disassembling `K2`'s own bounds-checked
`archive+0x30` read, gated by flag bit `0x1` of `archive+0xb8`, into
`inks[index]`). Two independent confirmations (real field name here,
real behavior there) landing on the same concept is a good sign
`inkIndex` is the real mechanism.

**What this means for `apple-notes-drawing.proto`'s `StrokeSeqInfo { seq,
ink_category, f3 }`**: almost certainly not a real nested message at all.
`protoc --decode_raw` (which is all that `.proto` was ever built from) has
no notion of Apple's real message boundaries - it only sees wire
types/lengths, and a length-delimited byte run that *looks* like a
sub-message to `decode_raw` could just as easily be several adjacent
scalar fields (e.g. some combination of `sortID`, `inkIndex`, `version`,
`styleFlags`) that `decode_raw` grouped together purely because of how
their varints/tags happened to lay out, not because Apple's schema
actually nests them. `ink_category`'s specific "meaning" was never the
real gap - the entire submessage grouping around it was likely already
wrong.

Two other names line up cleanly with existing confirmed behavior, worth
carrying into any future `.proto` rewrite:
- `strokeData` matches `point-decode-pipeline.md` §2b's confirmed
  `archive+0x70` -> `+[PKStrokePath(PersistenceAdditions)
  strokeDataFromDataArchive:]` edge - i.e. the `.proto`'s `Stroke.geometry`
  field (our own made-up name, since we never located a `Geometry` class -
  see below) is really `strokeData`.
- `bounds` matches the `.proto`'s `Stroke.bbox` field by straightforward
  naming, though the exact 4-float ordering inside it is still whatever
  `apple-notes-drawing.proto`'s `BBox` comment already flags as
  unconfirmed.

Two fields have no prior counterpart in `apple-notes-drawing.proto` at
all: `centerlineSlices` and `maskPoints`. Not investigated further this
pass - plausibly related to non-inking/mask-shaped strokes (`PKStrokeMask`,
seen but not disassembled in `point-decode-pipeline.md` §2e) but that's a
guess, not a finding.

## No `drawing::Geometry` class exists

`nm -U ~/re-notes/PencilKit | c++filt | grep 'drawing::Geometry::'` returns
nothing. The per-point flat-byte-blob concept `apple-notes-drawing.proto`
calls `Geometry` (with its own `uuid`/`npts`/`point_flags`/`points` fields)
isn't its own protobuf message in Apple's real schema at all - it's
already accounted for by `Stroke.strokeData` (a `drawing::StrokeData`
value, itself a confirmed real class - see `drawing::StrokeData::StrokeData()`
etc. in the raw `nm` output) plus whatever `PKStrokePathPointsShared`-level
unpacking `point-decode-pipeline.md` already fully traced (§4-§7). In other
words: `apple-notes-drawing.proto`'s `Geometry` message was always really
describing `StrokeData`'s payload, just under a name invented before
`StrokeData`'s real identity was known.

## `drawing::Drawing` - real fields (from `formatText` at `0x1ae63ee04`)

```
bounds, boundsVersion, inks, replicaUUIDs, strokes, uuid, versionVector
```

`inks`/`strokes`/`uuid` line up with `apple-notes-drawing.proto`'s
`ink_defs`/`strokes`/(implicit) fields by straightforward renaming.
`replicaUUIDs` plausibly corresponds to the `.proto`'s `uuids` (field 2,
repeated bytes) - matches shape (a repeated list of raw UUIDs) though the
"replica" semantic (sync/CRDT-flavored, guessing from the name alone) isn't
independently confirmed. `versionVector` is a strong candidate for what
the `.proto` calls `index` (field 3, repeated `IndexEntry`) -
`point-decode-pipeline.md`'s Function 3 notes (§3c-equivalent prose,
"builds... a `PKVectorTimestamp` object... via `+0x68`/`+0x70`... clock/
subclock... resolved against `sortedUUIDs`") already described building
exactly a version-vector-shaped structure at the drawing level, independent
of this session - though that description was of an *already-parsed* C
array on the archive side, not a direct disassembly of `Drawing`'s own
`readFrom`, so the exact correspondence to `IndexEntry`'s three raw
`int32` sub-fields (`f1`/`f2`/`f3`) is still not nailed down to specific
wire field numbers. `bounds`/`boundsVersion` have no counterpart at all in
the current `.proto`'s `Drawing` message - not identified at any specific
field number this pass.

## `drawing::Ink` - real fields (from `formatText` at `0x1ae6b5f84`)

```
azimuth, color, hdrAddColor, hdrColor, hdrHeadroom, hdrMultiplyColor,
identifier, requiredContentVersion, sixChannelAddColor,
sixChannelMultiplyColor, variant, version, weight
```

`identifier`/`color`/`weight` line up cleanly with the `.proto`'s `InkDef`
message (`name`/`rgba`/`weight`). `identifier` being a real field also
matches `point-fields.md`'s Function 2 finding that `PKInk` gets built via
`initWithIdentifier:color:version:variant:` - `identifier`/`variant` are
both real `Ink` fields confirmed here, `version` too. **`azimuth` existing
as an `Ink`-level field is worth flagging explicitly so it doesn't get
confused with the per-point `azimuth` channel** (`point-decode-pipeline.md`
§7-§8) - these are two unrelated concepts that happen to share a name: this
one is presumably some kind of ink-level lighting/shading direction (e.g.
for inks with directional shading, guessing from the name and the
neighboring HDR-color fields), not a per-touch-sample reading. Not
investigated further this pass. The six HDR/multi-channel color fields
(`hdrColor`, `hdrAddColor`, `hdrMultiplyColor`, `hdrHeadroom`,
`sixChannelAddColor`, `sixChannelMultiplyColor`) and
`requiredContentVersion` have no counterpart in the current `.proto`'s
`InkDef` message at all.

## Update (2026-09-12, later session): `drawing::Stroke`'s real field numbers

The "not mapped to field numbers" gap below is now mostly closed. Two
corrections to how this was originally scoped, first:

- `formatText`'s string-literal order is **not** declaration order after
  all - re-extracting it *without* piping through `sort -u` (the original
  extraction command, quoted above, did pipe through `sort -u`) produced
  the exact same alphabetical sequence. So `formatText` itself must sort
  fields by name before printing (a reasonable thing for a canonical/diff-
  friendly text format to do), independent of wire field numbers or
  declaration order. This method genuinely cannot recover field-number
  order; the real technique needed disassembling `readFrom` directly.
- `drawing::Stroke::readFrom(PB::Reader&)` (`0x1ae6488f0`) parses each
  field's protobuf tag into a field number (`x28`/`w8` in the disassembly)
  and dispatches through a computed jump table at `0x1ae611e84` (16
  entries, one per field number 1-16, resolved via `adrp`/`add` then a
  `ldrsw`+`br` indirect branch). Reading the jump table's 16 relative
  offsets directly off disk gives the real code address for every field
  number 1-16 without guessing.
- Separately, `drawing::Stroke` exports seven `makeXXX()` accessors
  (`makeBounds`, `makeSortID`, `makeVersion`, `makeTransform`,
  `makeStrokeData`, `makeInkTransform`, `makeSubstrokesVersion`) - these
  are the lazy-construct-if-absent accessors for every field whose type
  needs vtable-based construction (`Rectangle`/`StrokeID`/`Transform`/
  `StrokeData`, as opposed to a plain scalar or `PB::Data` blob). Each one
  is a two-instruction giveaway: `ldr x8, [x0, #OFFSET]` (read the field)
  then, further down, construct-and-store back at that same `OFFSET`. That
  directly ties a *name* to a *struct offset*, with zero ambiguity.

Cross-referencing those two independent things - "field number N's
`readFrom` case touches offset X" and "`makeFoo` touches offset X" - gives
a fully proven number for every field that has a `make` accessor:

| field # | name                  | type         |
|---------|-----------------------|--------------|
| 2       | `sortID`              | `StrokeID`   |
| 3       | `version`             | `StrokeID`   |
| 4       | `inkIndex`            | scalar int32 (confirms `apple-notes-drawing.proto`'s guessed `f4`) |
| 5       | `strokeData`          | `StrokeData` (confirms `apple-notes-drawing.proto`'s `geometry`) |
| 6       | `bounds`              | `Rectangle`  (confirms `apple-notes-drawing.proto`'s `bbox`) |
| 7       | `transform`           | `Transform`  |
| 9       | `substrokesVersion`   | `StrokeID`   |
| 15      | `inkTransform`        | `Transform`  |

`substrokes` (field 11) is separately proven without needing an offset
match at all: its `readFrom` case literally calls
`PB::PtrVector<drawing::Stroke>::emplace_back<>()` - the mangled type name
alone says "repeated `Stroke` message," which is exactly `substrokes`.

One finding worth flagging on its own: `StrokeID` is *not* purely a UUID
wrapper the way its name suggests - Apple reuses the same class for
`sortID`/`version`/`substrokesVersion`, none of which are identity/UUID
concepts. It's better understood as a generic versioned-identifier type
(plausibly a `{replicaID, counter}`-shaped logical-clock value, given this
codebase's other CRDT-flavored machinery - see `point-decode-pipeline.md`'s
`PKVectorTimestamp` notes) that Apple happens to reuse for both identity
and version-numbering purposes.

That leaves 9 of 16 fields with hard, disassembly-proven numbers
(2,3,4,5,6,7,9,11,15 above). The remaining 7 fields have no `make`
accessor to cross-reference (they're plain scalars or `PB::Data` blobs,
which don't need lazy vtable construction), so only *type*, not exact
number, could be nailed down for them - by matching each remaining
`readFrom` case's type against the remaining name list
(`centerlineSlices`, `groupUUID`, `maskPoints`, `renderGroupUUID`,
`shapeType`, `strokeUUID`, `styleFlags`):

| field #s      | plausible name(s)                              | type (proven) |
|---------------|-------------------------------------------------|---------------|
| 1, 13, 16     | `groupUUID`, `renderGroupUUID`, `strokeUUID` (order unconfirmed) | `PB::Data` (raw byte blob) |
| 8, 14         | `shapeType`, `styleFlags` (order unconfirmed)   | scalar int32  |
| 10            | `centerlineSlices` (by elimination + shape fit) | `vector<PB::Data>` (repeated blob) |
| 12            | `maskPoints` (by elimination + name fit - a flat float list literally called "points") | `vector<float>` (repeated, packed-or-unpacked dual path) |

The three-way and two-way ties were a real gap at the `readFrom`-dispatch
level - all three `PB::Data`-typed fields (and both scalar-int fields) look
identical there, since the field number alone determines behavior and
there's no accessor symbol left to disambiguate further via disassembly.
`writeTo`/`hash_value` wouldn't help either - they'd just re-confirm the
same offset<->number pairs `readFrom` already gave, not new name evidence.

## Update 2: resolving the remaining ties empirically

Closed almost all of the above by decoding real sample data instead -
`xt/probe-stroke-fields.pl`, run against every `com.apple.drawing.2`
attachment in a real local `NoteStore.sqlite` (1278 drawings, 773710
strokes total), tallying the raw values Google::ProtocolBuffers actually
produces for fields 1/8/10/12/13/14/16 (using the already-updated
`apple-notes-drawing-2.proto`, which by this point has real field numbers
for `Stroke` - see Update 1 above):

- **Field 1 (`stroke_uuid`) is 16 bytes on literally 100% of strokes**
  (773710/773710) - about as strong a confirmation as `strokeUUID` could
  get without a name-level proof.
- **Field 13 is 16 bytes on exactly 532 strokes** (rare) - **and field 14's
  532 total occurrences match that count exactly.** That's a strong
  co-occurrence signal that fields 13 and 14 are part of the same feature -
  consistent with "a group of strokes recognized as one rendered shape
  carries both a group id and the shape's type." Field 14's actual values
  are small and enum-like (`1,2,3,6,7,10,12` - seven distinct values, in
  the right ballpark for a PencilKit shape-type enum: line/arrow/rectangle/
  filled-rectangle/oval/filled-oval/star, or similar). That fits
  **field 14 = `shapeType`**, and by the co-occurrence, **field 13 =
  `renderGroupUUID`** (a shape's render group, not the more general
  `groupUUID`).
- **Field 16 never appeared even once** across the whole dataset. By
  elimination this leaves **field 16 = `groupUUID`** - the weakest
  conclusion of this batch, since it's argument-by-elimination only, not
  positive evidence; `groupUUID` (arbitrary multi-stroke selection
  grouping, distinct from `renderGroupUUID`'s shape-recognition grouping)
  is plausibly just a feature these particular sample notes never
  exercised.
- **Field 8's values are overwhelmingly `0` (273230) or `1` (246592), with
  a small cluster of `6` (26), then hundreds of large, all-distinct 64-bit-
  ish values (~1.37×10^11), each occurring exactly once.** Checked those
  outliers bit-by-bit: every single one has bit 37 set
  (`v & (1<<37) != 0`), with the remaining bits varying per-stroke and
  otherwise mostly zero - i.e. a sentinel high bit plus a handful of
  per-stroke low bits, the classic shape of a bitmask/flags field, not a
  small closed enum. That rules out `shapeType` for field 8 and leaves
  **field 8 = `styleFlags`**, matching by elimination with field 14 above.
- **Field 10 (`centerlineSlices`) had 27 occurrences, always exactly 1
  element** - rare and small, consistent with its guessed name (unchanged
  from Update 1).
- **Field 12 (`maskPoints`) had 14 occurrences, always an even element
  count (2 or 4)** - consistent with flat (x,y) point pairs, matching its
  guessed name literally (unchanged from Update 1).

Net result: of the 7 fields left tied after Update 1, 6 now have a specific
proposed field number (1=`strokeUUID`, 8=`styleFlags`, 10=`centerlineSlices`,
12=`maskPoints`, 13=`renderGroupUUID`, 14=`shapeType`), all but the last two
resting on much stronger evidence than a guess (100% presence, exact
co-occurrence counts, or a literal shared high bit). Only field 16
(`groupUUID`) is argument-by-elimination rather than positive evidence.
`apple-notes-drawing-2.proto`'s `Stroke` message has been updated to use
these names.

## Update 3: `strokeUUID` proven at disassembly level, not just empirically

Update 2 left field 1 = `strokeUUID` resting on "100% presence across
773710 real strokes" - strong, but statistical, not a name-level proof like
Update 1's `makeXXX()` trick gave for `sortID`/`version`/etc. Closed that
gap too, via a different disassembly route: PencilKit's public/private
Objective-C bridge onto the C++ `drawing::` types.

`nm -U` (grepping broadly for `-i strokeuuid`, not scoped to `drawing::`)
turned up `-[_PKStrokeConcrete _strokeUUID]` / `_setStrokeUUID:`, and -
notably - a **separate** `-[_PKStrokeConcrete _strokeDataUUID]` /
`-[PKStrokePath _strokeDataUUID]`. That's a second UUID concept entirely,
plausibly the `uuid` field already sitting inside `Geometry` in this same
`.proto` (i.e. `Stroke.strokeData`'s own internal UUID) - worth remembering
so `strokeUUID` and `strokeDataUUID` don't get conflated; only the former
is this section's subject.

`-[_PKStrokeConcrete _strokeUUID]` itself just returns a cached `NSUUID*`
ivar (a plain ObjC property getter, no direct struct access) - not useful
by itself. But disassembling
`-[_PKStrokeConcrete(PKStrokePersistenceAdditions)
initWithArchive:sortedUUIDs:inks:parent:isHidden:transientArchiveDictionary:]`
(`0x1ae67ae24` - the method that constructs a `_PKStrokeConcrete` from a
freshly-`readFrom`'d `drawing::Stroke`) shows exactly where that ivar gets
populated:

```
ldr  x8, [x24, #0x78]        ; x24 = drawing::Stroke*, read field at offset 0x78
cbz  x8, loc_1ae67af10       ; null? -> fallback path
...
ldr  x8, [x24, #0x78]        ; (reloaded after a helper call)
ldr  x2, [x8]                ; dereference the PB::Data - its raw byte pointer
bl   _objc_msgSend$initWithUUIDBytes:   ; wrap those 16 bytes as an NSUUID
bl   _objc_msgSend$_setStrokeUUID:      ; store it
```

and the fallback path (field absent) is:

```
bl   _objc_msgSend$UUID      ; +[NSUUID UUID] - a fresh RANDOM UUID
bl   _objc_msgSend$_setStrokeUUID:
```

Offset `0x78` is exactly field 1's offset per Update 1's `readFrom`
jump-table entry - so this is a direct, non-statistical proof that field 1
holds 16 raw bytes which get wrapped as an `NSUUID` and become the object's
`strokeUUID`. The "generate a random UUID if this field was absent"
fallback is itself a strong sanity check: that's exactly the kind of
backfill behavior you'd want for an identity field, and would make little
sense for `groupUUID`/`renderGroupUUID` (which are legitimately *supposed*
to be absent on most strokes, per Update 2's rarity findings) or for any
non-UUID field.

Field 1 = `strokeUUID` is now proven at the same rigor as Update 1's
`makeXXX()`-derived fields, not merely empirically inferred.

## Update 4: `StrokeID`'s own `f1`/`f2`/`f3` - `clock`/`subclock`/`replicaIndex`

`drawing::StrokeID` (see Update 1/`StrokeID` message) has no `makeXXX()`
accessor of its own (all three sub-fields are plain scalars, no lazy
vtable construction needed), so the `makeXXX()` trick doesn't apply here.
But `drawing::StrokeID::readFrom(PB::Reader&)` (`0x1ae7069b8`) is small
enough to read directly - no computed jump table this time, just three
inline `cmp`/`b.eq` checks on the field number - and each of its three
branches writes to a distinct, easy-to-read offset in the `StrokeID`
struct:

| field # | offset | 
|---------|--------|
| 1       | `0x8`  |
| 2       | `0x10` |
| 3       | `0x18` |

`drawing::StrokeID::formatText`'s string literals (ground truth, same as
every other class here) give the three real names: `clock`, `subclock`,
`replicaIndex` - exactly the `{replicaID, counter}`-shaped logical-clock
concept speculated about in Update 1, and matching this framework's wider
CRDT machinery (`PKReplicaEntry`, `PKVectorTimestamp`/
`PKVectorMultiTimestamp`, all dealing in clock/subclock/replica-UUID
concepts for merge ordering).

First hypothesis, from `-[PKReplicaEntry
initWithReplicaUUIDIndex:clock:subclock:inUse:forDrawing:]`'s parameter
order (and its ivar layout mirroring that same order): guessed field 1 =
`replicaIndex`, field 2 = `clock`, field 3 = `subclock`. **This hypothesis
turned out to be wrong** - checked against real `sort-id` (`Stroke.sortID`,
field 2 of `Stroke`, `StrokeID`-typed) values across several real
drawings:

```
pk 15436: (sort-id 0 11 1) (sort-id 1 11 2) (sort-id 2 11 3) ... up through (sort-id N 11 N+1)
pk 20035: (sort-id 0 1 1)  (sort-id 1 1 2)  (sort-id 10 1 11) ...
```

Two clear patterns: the **middle** value (offset `0x10`, field 2) is
constant *within* one drawing but differs *across* drawings (`11` vs.
`1`) - exactly what you'd expect from a per-note/per-device replica
identifier, not a chronological counter. The **first and third** values
(offsets `0x8`/`0x18`, fields 1/3) always satisfy `third = first + 1`,
consistently, across every stroke checked - consistent with a
`clock`/`subclock` pair that ticks up together in simple single-writer,
non-concurrent editing (no actual conflict to arbitrate, so both counters
just advance in lockstep).

Net conclusion: **field 2 = `replicaIndex`** (empirically solid - the
per-note-constant/cross-note-varying pattern is a strong signal, though
still empirical rather than disassembly-proven the way `strokeUUID` was in
Update 3). Fields 1 and 3 co-vary in every sample seen so far because this
corpus has no genuinely concurrent multi-device edits to tell them apart -
but see Update 5 below, which resolves the order anyway via a different
route (comparator priority, not divergent values).

## Update 5: `clock` vs. `subclock` resolved via the stroke-ordering comparator

Update 4 left fields 1/3 as "`{clock, subclock}`, order unresolved" -
genuinely undecidable from *values* alone in a single-writer corpus, since
both counters trivially advance together when there's never a tie to
break. Resolved it a different way instead: by disassembling the actual
comparator that orders strokes for rendering, and reading off which field
it treats as primary vs. tiebreak - that's a property of the *code*, not
the *data*, so a single-writer corpus doesn't block it.

`-[_PKStrokeConcrete compareToStroke:]` (`0x1ae6bbb00`) fetches both
strokes' `_sortID` (the ObjC-bridged form of `Stroke.sortID`, i.e.
`StrokeID`) into a lightweight `_PKStrokeID` C struct, then calls
`_PKStrokeIDCompare(_PKStrokeID, _PKStrokeID)` (`0x1ae6c5c18`). That
comparator's structure is a plain three-level cascade:

1. compare `int32` at struct offset `0x0` - if unequal, return immediately
   (this is the primary sort key)
2. else compare a value at offset `0x4` via a helper (`0x1ae7588e4`) that
   looks like a string/UUID comparison - a secondary tiebreak
3. else compare `int32` at offset `0x14` - the final fallback, only
   reached if both of the above were equal

That's the textbook Lamport/vector-clock ordering convention: compare the
primary clock value first; if two operations happen to share a clock value
(only possible across *different* replicas, in a real concurrent-edit
scenario), break the tie by replica identity; only fall back to a
secondary counter (`subclock`) if even the replica identity somehow
matches too. Assuming this lightweight bridging struct's field order
mirrors `drawing::StrokeID`'s own declared field order (reasonable - such
structs are typically populated by copying fields out of the parsed
protobuf object in order - though not itself independently disassembly-
proven byte-for-byte), this gives:

- **field 1 (offset `0x8` in `drawing::StrokeID`) = `clock`** - compared
  first, i.e. the primary key, matching offset `0x0`'s priority in
  `_PKStrokeID`.
- **field 3 (offset `0x18`) = `subclock`** - the last-resort tiebreak,
  matching offset `0x14`'s priority.

This also explains the `third = first + 1` lockstep pattern more
precisely than either Update 4 hypothesis did: with `clock` alone already
sufficient to totally order every stroke in a single-writer trace (no two
strokes ever share a `clock` value), `subclock` never actually has
tie-breaking work to do - it just advances alongside `clock` because
there's nothing for it to disambiguate. A real multi-device concurrent
edit is still the only way to see `clock` and `subclock` genuinely
diverge, but the comparator's priority order settles which name goes with
which field regardless.

`StrokeID`'s three fields are now all named: 1 = `clock`, 2 =
`replicaIndex` (Update 4), 3 = `subclock`.

## Not concluded

- Field 16's identity as `groupUUID` rests on elimination only (see Update
  2) - would benefit from finding even one real sample where it's actually
  populated.
- `Ink.azimuth`, `Drawing.bounds`/`boundsVersion`, `Ink`'s six HDR/multi-
  channel color fields, `Ink.requiredContentVersion`, and
  `legacy_drawing::Drawing` remain untouched - see the original "Not
  concluded" items above (still true, not superseded by either update).
- `centerlineSlices`/`maskPoints` (`Stroke`), `bounds`/`boundsVersion`
  (`Drawing`), and all six HDR/multi-channel color fields plus
  `requiredContentVersion` (`Ink`) are confirmed-real names with no
  investigation into their actual wire content at all this pass.
- Whether `Ink.azimuth` has any numeric relationship to the per-point
  `azimuth` channel's encoding (`point-decode-pipeline.md` §8) - e.g.
  same `uint16`/`65535` convention reused - is unknown; flagged only as a
  naming collision to watch for, not investigated.
- `legacy_drawing::Drawing` (a distinct, `legacy_drawing::`-namespaced
  class, confirmed to exist via its own `formatText`/`readFrom`/`writeTo`
  trio, fields `timestamp`/`unrotatedBounds` seen via its own `make*`
  methods) was noticed in passing during the `nm`/`c++filt` search that
  found `drawing::Ink` above, but not investigated - possibly related to
  `point-fields.md`'s still-unconfirmed-reachable `initWithLegacyArchive:`
  subgraph, but that connection is a guess, not checked.

## Provenance

Same Mac/toolchain as `point-decode-pipeline.md` §8-§9 (macOS 26.6.2 build
25G83, `ipsw` v3.1.714, `~/re-notes/PencilKit` extraction and
`PencilKit.a2s` cache already present from the 2026-09-11 session, not
re-extracted). All class/method/field names quoted above are exact,
verbatim symbol names or string-literal contents read directly from
`PencilKit.framework`'s symbol table and disassembly (`nm -U`, `c++filt`,
`ipsw macho disass -a <vaddr> --demangle --force`, piped through `grep -oE
'"[^"]+"'` to pull literal string operands) - no raw bytes, hex dump, or
extracted binary content reproduced here, consistent with this directory's
convention.
