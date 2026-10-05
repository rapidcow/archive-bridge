# com.apple.paper: a drawing / ink / strokes skeleton, with tombstones

The Coherence-era counterpart of `../com.apple.drawing.2/`. `drawing.2` can
no longer be produced on the owner's devices, so this is meant to be the
baseline against which purpose-made `com.apple.paper` fixtures answer
specific questions (see "Fixture experiments" below), and later to be
cross-compared with PencilKit's newer public API against its private
counterparts, as was done for `StrokeData`.

Companions: `envelope.md` (the sqlite envelope), `proto-schema.md` (Swift
metadata -> `.proto`), `coherence-framework.md`. Code and schemas:

- `../../tools/apple-notes-paper.proto` - generic Coherence wire messages
  (proto3; corrected by this session, see "Corrections")
- `../../tools/apple-notes-paper-drawing.proto` - Ink, Color, StrokeData and
  the object-graph map of what lives where (new)
- `../../tools/apple-notes-paper-drawing-dump.pl` - walks a bundle, prints the
  drawing / ink / strokes structure including tombstones; `--check`
  re-verifies every claim marked CONFIRMED below (new)

## Evidence base, and what "CONFIRMED" means here

One account's bundles: 52 bundles under `Accounts/<acct>/Paper/Bundles/`,
**114,290 `Reference` rows**, 28,496 live set members, 28,508 strokes (28,467
top-level + 41 inside groups), 542,158 points. Notes dated 2024-09 to 2026-08.
All `-wal` files are empty, so `?immutable=1` is a faithful view of them.

Method: schema-less wire parse first (Python scratch scripts, not kept), then
`apple-notes-paper.proto` + `apple-notes-paper-drawing.proto` through
`Google::ProtocolBuffers::Dynamic`. Dynamic **discards unknown fields on
decode**, so `encode(decode(row)) eq row` is a strict test that the schema
covers every field present in that row. `apple-notes-paper-drawing-dump.pl
--check` runs it on everything:

```
Document decode/encode exact:  114290 / 114290
Version column round-trips:    114238 / 114238     (as a Document)
ChildRetainCounts round-trips: 57245 / 57245       (as RetainCounts)
StrokeData round-trip exact:   28508 / 28508
StrokeData stride exact:       28508 / 28508
Ink round-trip exact:          28508 / 28508
unreachable from root:         0
grammar/consistency exceptions: 0
```

**CONFIRMED = holds for every observed row of this corpus, checked by
bytes.** It is a statement about the data, not about Apple's source: nothing
was disassembled this session and no PencilKit/Coherence API was called. A
single account (10 of the 52 drawings show more than one replica in their
sequence versions); rows or features it never exercised are not covered. Field *semantics* are separate from field
*numbers/types*, and only the latter are mostly confirmed.

## The object graph (all CONFIRMED unless marked)

A bundle is a graph of `Reference` rows keyed by `CRKeyPath`. Almost every
row's `Data` is `crdt` + u32 version (6 on all 114,201 prefixed rows) + a
proto3 `Document`; the 52 root rows (`Id` = `0xFF`) and 37 content rows have
no prefix. A "Struct" row is self-describing: the row's own
`Document.constants.strings` names its properties, `Struct.keys` index it.

```
0xFF root row   Document{ reference -> "\x01default", version, constants.uuids, version_uuid }
  "default"     Struct{ bounds, frame, drawing }
    bounds, frame : Register(Value.data)  4 x float64 BIG-endian   (CGRect)
    drawing       : Register(Optional(Reference)) -> row D
  D  drawing    Struct{ canvasBounds, strokes }
    canvasBounds  : Register(Value.data)  4 x float64 BE; (+inf,+inf,0,0) = CGRectNull in ALL 52 bundles,
                    including ones with strokes - so it is not a content extent here
    strokes       : CRDT.ordered_set  (OrderedSet: Set + Sequence + timestamps)
      Set.insertions[i].key = Value.reference -> row E
      E  Register(TaggedValue{ tag, wrapped = Reference -> row S })
           tag 0 -> S is a stroke        (28,467)
           tag 1 -> S is a group:  CRDT.sequence whose `elements` are references
                                   to more E rows (stroke or nested group)   (29 + 9 nested)
  S  stroke     Struct{ inherited, properties }
    inherited   : Register(Reference) -> ink row K            (top-level strokes: 0 hops)
                  or -> Struct{ inherited } -> ... -> K       (group members: 1-3 hops)
    properties  : Register(Value.custom = CustomCRValue{ tag[], value[] })
                    tag 0 uint   tag 2 sint(=0)   tag 3 Reference -> row P
                    [tag 4 data] [tag 6 data(48 B)] [tag 7 uuid(varint)]
                    sets: {0,2,3} 28,422 | {0,2,3,7} 32 | {0,2,3,6} 13 | {0,2,3,4} 41 (= exactly the group members)
  K  ink        Struct{ ink } (27,048) or Struct{ ink, transform } (1,448)
    ink         : Register(Optional(Value.data)) = a serialized `Ink`
    transform   : Register(Value.data) 6 x float64 BE (a, b, c, d, tx, ty)
  P  strokedata CRDT.custom_crdt, exactly one Sibling{ version, payload = StrokeData }
```

Row populations that tie it together: 28,496 ink rows = 28,496 `custom_crdt`
rows = 28,496 set members (top-level strokes + groups reach one of each).
28,488 ink rows are reached by exactly one stroke chain; **8 are shared** by
2-4 group-member strokes. Every row is reachable from the root (checked two
ways: structurally, and through each row's `ChildRetainCounts`).

The string names in the constants tables (all real Swift property names, not
invented): `bounds frame drawing canvasBounds strokes inherited properties
ink transform`.

## Stroke payload vs `drawing.2`: byte-compatible (verdict)

`Sibling.payload` is **a serialized `drawing::StrokeData`, byte-compatible
with `apple-notes-drawing-2.proto`'s `StrokeData`** (and so with
`java/apple-notes-drawing/src/main/proto/StrokeData.proto`). On all 28,496:

- wire types identical to the drawing.2 message; field numbers 1-9 only
  (`start_index`, 10, never occurs); no duplicate fields;
- `len(points_data) == points_count * stride` exactly, with the stride from
  the drawing.2 channel table (`8 + sum of channel sizes for flag bits 1..10`);
- `stroke_data_uuid` is 16 bytes on all; the "1000 / 32767" sentinel bytes
  `e8030000ff7f0000803f` are `points_constant_values` on 7,093 (longer tables
  appear with other `points_constant_flags`, not analysed).

This extends `proto-schema.md`'s earlier single-sample finding to the whole
corpus. Two things drawing.md could only prove "exist" are now seen:
`created_with_finger` (field 8) is present on **2,840** strokes, always 1;
`immutable_points_count` (9) on **6,303**, always **< points_count**.
`points_data` decoding was not redone: the drawing.2 pipeline applies
unchanged, and stride arithmetic is the only check made here.

`Ink` is likewise the drawing.2 message verbatim. Fields seen: 1 color, 2
identifier, 3 version (=3 on all), 4 variant, 5/6 six-channel colors, 8 weight.
Identifiers: `com.apple.ink.pen` 28,451 / `.marker` 32 / `.fountainpen` 13;
variants `linear` and `fixed-width` 32 each; weights -0.0 / -0.5 / 0.5. Note
`Color` carries explicit zero floats on the wire (opaque black is
`r=g=b=0,a=1`), hence `optional` in the proto3 file.

**Differences from drawing.2's `Stroke`/`Drawing`**: no `StrokeID`
(`sortID`/`version`) on a stroke, no `Drawing.inks[]`/`ink_index`
indirection, no `replicaUUIDs`/`versionVector`/`boundsVersion` on the drawing.
Identity, order and causality come from the Coherence machinery around the
stroke instead (below). Where drawing.2 had `transform` (7) and `inkTransform`
(15) on the stroke, the data here has a `transform` on the **ink row** (1,448)
and an unexplained 48-byte `properties` tag 6 (13) - which maps to which is OPEN.

## Byte order: Coherence `Value.data` is big-endian

`bounds`, `frame`, `canvasBounds` (32 B) and `transform` (48 B) are float64
**big-endian**: `0x4088000000000000` = 768.0, `0x7ff0000000000000` = +inf
(the empty `CGRectNull`), `0x3ff0...` = 1.0 (identity `a`,`d`). The floats
inside `StrokeData.points_data` are little-endian as in drawing.2. Both
appear in the same bundle; mixing them up is the obvious trap.

## Version vectors, `Constants.uuids`, RLE (CONFIRMED structure)

- Every row's `Constants.uuids` is a table of 16-byte replica UUIDs.
  `Timestamp.uuid`, `Version.uuids` and `Value.uuid` are **indices into that
  table**, per row. Index 1 is the all-zero UUID in the sampled drawings; it
  is the replica of the two sequence sentinels.
- `Reference.Version` (sqlite column) is a `Document` whose root is
  `Value.version` plus its own `Constants.uuids`: the row's version vector.
  `ChildRetainCounts` is `RetainCounts` with only `reference_keys` (2) and
  `reference_retain_counts` (3), i.e. a redundant list of the rows this row
  refers to.
- `RLE.runs` is **field 2**, packed int64, read as alternating `(gap,
  length)` pairs over counters from 0: `[0,24]` = {0..23}; `[38,1]` = {38}.
  Evidence: `Sequence.version` for replica 2 is `[0,24]` and the runs'
  lengths sum to 24 in the sample; element `added_by` sets are single
  counters. The gap/length reading fits every row; no independent decoder
  confirms it (the one thing that would be wrong silently).

## TOMBSTONES (the user's explicit requirement)

**What the data shows.** A deleted stroke is not stored as a deleted
object. The strokes `OrderedSet` is a `Set` (live members) plus a `Sequence`
(an RGA-style tree of position runs). A `Subsequence` is a run:

| field | meaning (evidence) |
|---|---|
| 1 `added_by` | `Timestamp` (replica idx, counter): id of the run's first position; positions are `counter .. counter+length-1` of that replica |
| 2 `length` | absent on the two sentinels |
| 3 `sequence_start_id` | `Timestamp`; **present on exactly the runs holding live content** |
| 4 `child` | packed indices into `Sequence.subsequence`; a tree rooted at run 0 in 51/52 bundles, <= 2 children |

Rule, CONFIRMED in all 52 bundles (and re-checked as an exception test): the
summed `length` of runs that **have** field 3 equals the number of live set
members; the runs that **lack** it are positions that exist (they are all
covered by `Sequence.version`) but whose element is gone. Those are the
tombstones. Totals: 42,778 positions = **28,496 live + 14,282 tombstoned in
5,340 tombstone runs**; 49 of 52 bundles contain tombstones; groups (38
sequences) contain none.

`ContentOptions.noContent` exists in Swift's metadata but is never on the
wire; absence of field 3 is the representation. (My first reading - field 3 =
"removed" - was the opposite of the data and was refuted by the count above.)

**What does NOT survive.** Nothing but the tombstone run: not the stroke, not
its ink/StrokeData rows (every row is reachable and the `custom_crdt` row
count equals the live member count), and `Set` never carries a removed-element
list (no field 3/5/6 on any `Set`). There is no recoverable payload for a
deleted stroke in these files. "Preserving tombstones" therefore means
keeping, per run: replica index + resolved replica UUID, counter range,
length, parent/children, live/tombstone state, plus the version vectors
(`Set.version`, `Sequence.version`, `Sequence.added_by_version`, each
element's `added_by`).

**How the tooling keeps them.** `apple-notes-paper-drawing-dump.pl
--tombstones` lists every run, live and tombstoned, in `Subsequence` order;
`--json FILE` writes all of it (sentinels, tombstone runs, version ranges,
group sequences) with hex ids. Nothing is dropped.

**Corroboration, partial.** `Set.version` covers exactly `positions +
tombstoned positions` counters in 30 of 52 bundles (as if each add and each
delete consumed one tick) and `Sequence.added_by_version` has the same size
as `Set.version` in 49 of 52. In the other 22 bundles the excess is always
**even** (2, 4, ... 284). Unexplained; see experiments E6/E7.

## Corrections made to `apple-notes-paper.proto`

All backed by the round-trip sweep. Swift's struct property order
(`coherence-proto-types.txt`) is **not** the field-number order for these:

| message | before | now |
|---|---|---|
| `RLE` | `runs = 1` | `runs = 2` (this was already known to be wrong) |
| `Set` | uuid1 insertions2 removed3 version4 ... | `uuid=1`, `version=2`, `insertions=4`; removed/timestamp/min_version undeclared (never seen) |
| `Sequence` | string1 elements2 subsequence3 attribute_runs4 version5 ... | `elements=2`, `subsequence=3`, `version=4`, `added_by_version=5`; the rest undeclared |
| `Subsequence` | length1 added_by2 child3 removed_added_by4 ... | `added_by=1`, `length=2`, `sequence_start_id=3`, `child=4` |
| `CustomCRDT.Sibling` | `data` (bytes) = 1, version_or_payload = 2 | `version` (a `Version`) = 1, `payload` = 2 |
| `RetainCounts` | reference_keys 1, ... | `reference_keys=2`, `reference_retain_counts=3` |
| `Document` | `value = 2` assumed | validated (root of the `Version` column) |

Resolved open items from `envelope.md`/`proto-schema.md`: `Sibling`'s second
field is **always** a `StrokeData` and its first **always** a `Version` (28,496
of 28,496; the earlier "half don't parse as Version" was a mis-typed field);
the 38 hard failures `proto-schema.md` traced to `Set.Element.key` came from
the old `Set` numbering (wire field 2 is a `Version`, not an `Element`). They
are almost certainly the drawing rows' `strokes` ordered sets; that earlier
21-bundle sample was not re-run, so the identification is an inference.
`Subsequence` field 3's *name* is a probable, not confirmed: it is Swift's
only singular `Timestamp` (`sequenceStartID`), and its presence rule matches
"has content"; `removedAddedBy` (Swift, repeated `Timestamp`) was never seen.

## OPEN: semantics not evidenced

1. **Stroke z-order / which position belongs to which member.** The `Set`
   order is not necessarily draw order. Each member's `added_by` counter
   matches a live run's content counter exactly in 29 of 52 bundles, with a
   constant offset in one more, and with varying offsets (or several
   replicas) in the rest. Rank-pairing was not tried. There is no `sortID`.
   Order the `child` lists give (document order = DFS with children as listed?)
   is untested.
2. `properties` tag 0 (a 38-bit-plus uint, bit 37 always set, distinct on
   28,471 of 28,508 - not a stroke counter) and tag 2 (always sint 0).
   drawing.2's `style_flags` outliers also had bit 37; resemblance only.
3. `properties` tags 4 (variable data, only on group members), 6 (48 B), 7
   (a varint 2): compare `maskPoints`/`centerlineSlices`/`transform`.
4. What a **group** is (tag 1 + `CRDT.sequence`) and what makes the 9 nested.
5. `Set.version` even excess (see above); whether `Subsequence` children
   order and attach points mean RGA "insert after".
6. `OrderedSet.timestamps` - one `Timestamp` per member whose `uuid` indexes
   `Constants.uuids` (a per-member identity UUID?), `counter` absent.
7. Never observed, so unspecified: `CRDT` cases 2/3/5/7/8, `Value` cases
   2/3/6(beyond tag 7)/10/11/13/15-17, `Sequence.string`/`attribute_runs`,
   `Set.removed`, `Assets`, `Document.references/assets/min_version`, any
   `custom_crdt` with more than one sibling, `ContentOptions` on the wire.
8. `0xFF` root row: `version_uuid` (field 8) and root `Version`; not analysed.

## Dead ends, for the record

- Treating Swift's struct order as the field order for `Set`/`Sequence`/
  `Subsequence`: wrong (see Corrections). Declaration order only held for the
  messages `proto-schema.md` listed as validated.
- "Subsequence field 3 = removed": refuted (it marks the live runs).
- Reading `RetainCount` as a tombstone flag: value 0 is exactly the 52 root
  and 52 `default` rows and nothing else; 114,168 rows have 1, and 18 other
  rows have 2 (14), 3 (2) or 4 (2) - not analysed (the 8 shared ink rows are
  candidates). No row signals deletion through it.
- A first schema-less shape-printer treated short packed byte strings as
  nested messages; superseded by the schema round-trip.
- Not done: no disassembly, no ipsw, no PencilKit/PaperKit/Coherence API call,
  no on-device capture, no check against a second account or an older Notes
  version; `points_data` decoding not repeated.

## Fixture experiments (what to draw in Notes, what each answers)

Each needs its own note with one `com.apple.paper` drawing; compare the new
bundle against the dump (`--bundle ID --strokes --tombstones --json`).

- **E1 baseline** - empty drawing, then one pen stroke with finger, then one
  with Pencil: `created_with_finger`, flags/stride, `properties` tag 0 (does it
  track wall time? make two strokes ~1 s apart, then minutes apart).
- **E2 z-order** - three overlapping strokes of different colours A, B, C,
  drawn in that order: which of Set order / sequence DFS order / counter order
  reproduces the stacking? Then draw D *between* by selecting and "send
  backward" if the UI allows (answers OPEN 1).
- **E3 stroke erase** - erase B with the whole-stroke eraser: expect one new
  tombstone run over B's position, the `Set` member and rows E/S/K/P gone;
  `Set.version`/`added_by_version` +1? (answers the tombstone model and the even
  excess).
- **E4 pixel erase** - partially erase one stroke: does it become two strokes
  (new members, tombstone), set `properties` tag 4/6, or change `StrokeData`
  (`immutable_points_count`, `start_index`)? Compare drawing.md's `centerlineSlices`.
- **E5 undo/redo** - erase then undo, then redo: does the tombstone run become
  live again with the same position id (the data would show the same run with
  field 3 back) or get a fresh position?
- **E6 move/resize** - lasso a stroke and drag it: `properties` tag 6 (48 B)
  or ink-row `transform` appears? Counters consumed (even excess)?
- **E7 recolour/re-ink** - select a stroke and change colour or pen: new ink
  row, edited `Ink`, or new stroke? Does `Ink.version`/`inherited` change?
- **E8 groups** - hold to snap a shape, or select-and-group, or draw a long
  stroke the tool splits: find what creates a tag-1 `sequence` and the
  `inherited` chain; check why exactly 8 ink rows are shared.
- **E9 concurrency** - edit the same drawing offline on two devices, then
  sync: a second `Sibling`, multi-replica versions, whether tombstones now
  differ per replica (all 52 existing bundles have exactly one sibling).
- **E10 canvas size** - make the drawing taller/wider, add strokes outside
  the visible area, delete everything: in the corpus `bounds` is `(0,0,768,h)`
  with varying h, `frame` is `(0,0,768,240)`-shaped (width 768 on all) and
  `canvasBounds` is the null rect on all 52 - what moves each of them, and
  how do they relate to `paperContentBounds*Key` in `ZMETADATADATA`?
- **E11 ink catalogue** - one stroke each of pen, marker, fountain pen,
  pencil, crayon, monoline, watercolour: `identifier`, `variant`, `weight`,
  the six-channel colours (only 3 identifiers are in the corpus).

## Reproducing this

```
B=.../Accounts/<acct>/Paper/Bundles
perl ai/tools/apple-notes-paper-drawing-dump.pl --bundles $B                 # one line per bundle + totals
perl ai/tools/apple-notes-paper-drawing-dump.pl --bundles $B --check          # every row, every claim above
perl ai/tools/apple-notes-paper-drawing-dump.pl --bundles $B --bundle FEFCA7F9 --strokes --tombstones
perl ai/tools/apple-notes-paper-drawing-dump.pl --bundles $B --json out.json  # all runs, tombstones included
```

`FEFCA7F9` (23 rows, 5 live strokes, 3 tombstone runs of 10, 6 and 3
positions) is the smallest bundle that shows tombstones; `3957472B` (7 rows)
is the smallest with a stroke; `4823AA6A` (3 rows) is an empty drawing.
The scripts need `Google::ProtocolBuffers::Dynamic`, `DBI`+`DBD::SQLite`,
`JSON::XS` (default `perl`, 5.44 here).

## Provenance

Data: the `cloud-ethan-1` account's `Paper/Bundles` (52 bundles), opened
read-only with `immutable=1`; nothing in the original was modified. Tooling:
`Google::ProtocolBuffers::Dynamic` 0.43, Perl 5.44; Python 3 stdlib only for
throwaway scratch analysis. Written 2026-10-05.
