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

## Not concluded

- None of the newly-confirmed field *names* above have been mapped back to
  confirmed wire *field numbers* - `formatText`'s string literals give
  names in declaration order but not the field-number constants
  `readFrom`/`writeTo` dispatch on. That would need actually disassembling
  `readFrom` (a tag-based dispatch loop, likely a large switch/jump table)
  for each class, not attempted this pass.
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
