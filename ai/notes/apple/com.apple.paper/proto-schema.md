# The `Proto_*` schema, from `Coherence`'s own Swift metadata

Follow-up to `envelope.md` (empirical byte-level findings) and
`coherence-framework.md` (confirms `Proto_*` Swift-protobuf types exist in
`Coherence.framework`, but stopped short of field numbers/names). This file
closes most of that gap: real field **names and types** for essentially the
whole `Proto_*` message set, plus **empirically validated field numbers**
for the parts of the schema that actually show up in real Paper bundle data.

Result lives as `../../tools/apple-notes-paper.proto` — read that file's
header comment for the validated/assumed convention used per field. This
file is the evidence trail behind it.

## The unlock: `ipsw swift-dump` against the *shared cache*, not the extract

`coherence-framework.md` tried `nm`/symbol-table inspection and concluded
field numbers would need real disassembly. There's a shortcut:
`ipsw`(blacktop/ipsw)'s `swift-dump` command walks Swift's own type/field
metadata sections (`__swift5_types`, `__swift5_fieldmd`, protocol
conformance records) and prints each type's stored properties with real
names and types — no disassembly needed, because SwiftProtobuf-generated
types keep this metadata for reflection (`Mirror`, Codable-style APIs, KVC),
even when line-by-line decode logic isn't individually symbol-named.

Critical gotcha: **running it against the standalone `ipsw dyld extract`ed
Mach-O fails**:

```
$ ipsw swift-dump ~/re-notes/Coherence --type 'Proto_Reference$' --demangle
⨯ failed to precache swift fields/types: ... failed to get pointer for
  indirect context descriptor: failed to read pointer @ 0x29a63f9c8:
  address 0x29a63f9c8 not within any segment's adress range
```

That address is in a *different* image (`InternalSwiftProtobuf.framework`,
which several `Proto_*` types' field descriptors point into for shared
generic layout info). The extracted single-Mach-O file has no way to
resolve it. Fix: pass the **full shared cache** plus the in-cache dylib
path, not the extracted file:

```
DSC=/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e
DYLIB=/System/Library/PrivateFrameworks/Coherence.framework/Versions/A/Coherence
ipsw swift-dump "$DSC" "$DYLIB" --type 'Proto_.*' --demangle
```

This works and resolves cross-image pointers correctly (ipsw loads the
whole cache and can chase them). Simple structs print fully inline; structs
above SwiftProtobuf's field-count threshold get a copy-on-write
`_StorageClass` indirection instead (a `class` with the same stored
properties) — get those with `--type 'StorageClass'` (matches every
`_StorageClass` in the binary; grep the output by outer type name).
Nested enums/oneofs sometimes don't show up under `--type` at all (their
metadata is only reachable as an associated/nested type) — `--type
'<ShortNestedName>$'` (anchored, no module/outer-type qualifier) usually
finds them directly; `--headers -o DIR` dumps everything to per-type files
but **collides on nested-type basenames** (e.g. every message's own
`OneOf_TaggedOneOf` enum gets written to the same `OneOf_TaggedOneOf.swift`,
last-write-wins) — fine for browsing, not for capturing everything; prefer
plain stdout dumps with a distinguishing filter for anything you need to
keep.

Full raw captures (all `Proto_*`/`Peer_*` types, ~300 lines total, curated
from the deduped `--headers` dump) are saved alongside this file as
`coherence-proto-types.txt` for future reference — this took real
wall-clock time (a slow SSH hop, big binary, single-purpose remote Mac) and
shouldn't need re-doing.

## What this reveals beyond `Proto_*`

Two things worth knowing about but **not** part of the Paper decoding
work below:

- **`Proto4_*`**: a second, different schema (`Proto4_Value` has an
  `int`/`date: Google_Protobuf_Timestamp` shape, not `Proto_Value`'s
  `uint`/`double`/`float`/reference-counting shape) — looks like either an
  older or a parallel/future schema version. Not seen in any sampled
  bundle byte.
- **`Peer_*`**: a whole sync wire protocol (`Peer_Message` /
  `Peer_MergeRequest` / `Peer_MergeResponse` / `Peer_Heartbeat` /
  `Peer_KeyPaths` / `Peer_RequestAssets` / `Peer_RespondAsset` /
  `Peer_RequestVersion`), all built out of `Proto_Document`/`Proto_Reference`
  — this is presumably how Coherence peers exchange deltas (CloudKit sync,
  or device-to-device). Out of scope for local-file decoding.

## Field names/types: hard fact

Every `struct`/`class`/`enum` declaration under `Proto_*` in
`coherence-proto-types.txt` is read directly from Swift's compiler-emitted
field metadata — same confidence level as `coherence-framework.md`'s
"confirmed" bar (real binary sections, not inference), just one level
deeper (field-level, not just type-level). The full set is in that file;
`../../tools/apple-notes-paper.proto` is the curated, field-numbered
version of it.

One name is not a protobuf field at all: `Proto_Document.consumeReferences`
demangles to something ipsw couldn't fully resolve
(`Coherence.Proto_Document _$szKcSg ->`) — the `->` and stray mangling
fragment indicate a closure/function-typed stored property, not a `var`
SwiftProtobuf's decoder would visit. Excluded from the `.proto`.

## Field numbers: validated by a real 7-level nested decode

SwiftProtobuf codegen always emits stored properties in the same order as
the source `.proto`'s field declarations. The working hypothesis —
**declaration order == field number** — was tested against a real sample
row (the `"default"`-keyed row from `envelope.md`'s example bundle) via one
concrete 7-level chain, below. Every hop in it matched. That's `CRDT`
(cases 1/4), `Register` (1/2), `Value` (cases 8/9), `Optional` (1),
`Reference` (5), `KeyPath` (2), and — from the separate root-row chain
right after it — `Struct` (1/2), `Timestamp` (2), and `Constants` (1/2).
That is real coverage for those specific fields, on those specific
messages, not a claim that *every* field of *every* message below was
exercised — most fields on most messages here were never hit by either
chain, and their `// assumed` markers in the `.proto` say so honestly.
`Proto_Document` is the one message that demonstrably breaks the
hypothesis (see below) rather than just being unexercised.

```
Struct.values[2]                                  (a Proto_CRDT)
  CRDT.register = 1        → Register
    Register.value = 2     → Value
      Value.optional = 9   → Optional
        Optional.wrapped = 1 → Value
          Value.reference = 8 → Reference
            Reference.keyPath = 5 → KeyPath
              KeyPath.data = 2 = <16 raw bytes, uuid-shaped>
```

i.e. a weak/optional reference to another row, stored as
`Register{value: Optional{wrapped: Reference}}`. Six levels of nesting,
each hop's field number matching pure declaration order, landing on a
16-byte UUID exactly where `KeyPath.data` (= the sqlite `Reference.Id`
encoding from `envelope.md`) should be. This is not the kind of thing that
lines up by chance.

Independently, the `0xFF`-keyed root/manifest row (also from `envelope.md`)
confirms the *outer* envelope:

```
protoc --decode_raw on the stripped 0xFF row:
3 { 5 { 2: "\001default" } }      →  Document.root(reference=3) → Reference.keyPath(5) → KeyPath.data(2) = "\x01default"
5 { 1: "\000" 2 { 2: "\000\344\007" } }   →  Document.version(5) = Version{...}  (partially understood, see below)
6 { 1: <16B> }                    →  Document.constants(6) = Constants{uuids: <16B>}
8: <16B>                          →  Document.version_uuid(8), a bare scalar here
```

`3 { 5 { 2: "\001default" } }` is the same 6-level-deep style of
confirmation: `Document.root`'s 3rd oneof case (`reference`) wraps a
`Reference` whose `keyPath`(5) wraps a `KeyPath` whose `data`(2) is
literally the bytes `"\x01default"` — the `Id` of the bundle's own
`"default"`-named row. envelope.md described this manifest row as
"embeds another row's raw `Id`" purely from bytes; this is that, named.

### `Proto_Document`'s numbering breaks the pattern — and that's expected

Declaration order gives `root`(oneof, 1-3), then
`consumeReferences`(excluded, not a field), `references`, `assets`,
`versionUuid`, `deltaReferences`, `_version`, `_constants`, `_minVersion` —
which would put `constants` at field 10, not the empirically-observed 6.
Every *other* message matched declaration order exactly, so this isn't a
general failure of the hypothesis — `Document` is the root/manifest
envelope, i.e. the single message most likely to have been edited
(fields added/removed/renumbered) over the format's lifetime, and
protobuf's whole design point is that removed/reordered fields don't
recycle numbers. Observed field numbers (`root`=1-3, `version`=5,
`constants`=6, `version_uuid`=8) are kept in the `.proto`; every
`Document` field that never appeared in the 45,170-row sample is commented
out rather than guessed at.

## The "sharp test": `protoc --decode=Document`, not eyeballing `decode_raw`

Wrote `../../tools/apple-notes-paper.proto` from the above, then ran
`protoc --decode=Document` against every row across all 21 bundles.

First pass (2,247-row sample, `CustomCRDT.Sibling.version` still typed as
`Proto_Version`) gave a clean split: **100% success** on rows whose payload
is `CRDT.register`(1) or `CRDT.composite`(4) (the common cases: property
registers and the self-describing struct/dictionary shape), and a
**partial hard failure** localized entirely to `CRDT.custom_crdt`(10) rows.
That failure was real, not cosmetic: `protoc` threw `invalid varint` deep
inside `CustomCRDT.Sibling`'s second field. On roughly half of sampled
`custom_crdt` rows that position is a genuine `Proto_Version`; on the other
half the same wire position holds what looks like a large embedded binary
payload (point data-shaped, plausibly a drawing) that does not parse as a
`Version` at all. Fixed by declaring `Sibling`'s second field `bytes
version_or_payload` instead (never hard-fails; re-decode as `Version` by
hand when the shape looks right). This is likely where PencilKit/PaperKit
drawing content actually lives in the Coherence-era format (a
`Proto_CRDT.custom_crdt` escape hatch for non-generic payloads), matching
`com.apple.drawing.2`'s existence as the *pre*-Coherence format for the
same content — a natural next investigation, not done this session.

**Full sweep, after that fix, over all 45,170 rows in all 21 bundles
(`crdt`-prefixed and bare alike — an earlier pass of this sweep
accidentally skipped every row *without* the prefix, which is all 21
`0xFF` root rows; re-run to include them):**

- `protoc --decode=Document` hard-fails (nonzero exit) on exactly **38**
  rows — 0.08% of the total, all `"default"`- or bundle-self-UUID-keyed
  manifest-adjacent rows whose payload touches `CRDT.orderedSet`(9). See
  below for the traced cause.
- A softer, more important check: even a *successful* decode can be
  silently wrong if a real field number just isn't in the `.proto` yet —
  `protoc` skips unrecognized field numbers rather than failing, and
  prints them back in raw numbered form (`N: ...` / `N { ... }`) instead of
  by name. Grepping every successful decode's text output for that raw
  form found **zero** unmapped fields across all 45,109 ordinary
  `crdt`-prefixed content rows and all 19 bare outlier rows — i.e. every
  field number those rows actually exercise (`Document.root` cases 1/3,
  `constants`=6, and everything reachable under them) is accounted for in
  the `.proto`, not just "didn't crash." The **21 root rows are the one
  exception** — every single one has an unmapped field, always inside
  `Document.version(5)`'s `index_sets`: `Version.uuids`(1) decodes cleanly
  as a real value, but the nested `Proto_RLE` entry shows its payload under
  the raw numbered field `2`, not the `.proto`'s `RLE.runs = 1`. So
  `Document.version = 5`'s *field number* is solid (seen on all 21 root
  rows, nothing else fits there), but `RLE`'s own field number is
  confirmed wrong, not just "unconfirmed" — downgraded accordingly in the
  `.proto`.

**All 19 bare-protobuf outlier rows** (not just the one spot-checked
earlier) decode cleanly as `Document` with **zero** unmapped fields, each
one landing on a `Register`/`Optional`/`Reference` shape structurally
identical to ordinary content rows — closing `envelope.md`'s open question
for real, not from a single example.

**The 38 hard failures**, all confined to `CRDT.orderedSet`(9), are
exactly the "Possible ordered-sequence CRDT structure" `envelope.md`
flagged from raw bytes alone (position-pair keys + monotonic tie-break
byte + a `4294967295` sentinel). Traced one down to the actual break:
`OrderedSet.set`(field 1, typed `Proto_Set` per Swift metadata) decodes its
first `Element`, and `Element.key` — typed `Proto_Value?` in Swift — holds
a **bare 1-byte payload** (`"\x07"`, one of the tie-break tag bytes) with
no inner oneof-case wrapper. A real serialized `Proto_Value` needs at
least a case tag + length around any payload, so this is provably not a
`Value` message on the wire here, even though Swift's static type says
`Value?`. Most likely explanation: this particular row is using `Proto_Set`
for the ordered-list tie-break bookkeeping (a different logical use than
the hash-keyed dictionary use `Proto_Set` gets elsewhere), and
`Element.key`'s type declaration doesn't capture that usage's real shape —
or the field-number mapping inside `Set`/`Element` itself is simply off
(all marked `// assumed, unobserved` already for exactly this reason). Not
resolved this session; the `.proto`'s existing uncertainty markers on
`Set`, `Sequence`, `Subsequence`, and `OrderedSet`'s own field numbers
already cover this — this sweep is confirmation they were right to be
marked uncertain, not a new gap.

## Two `envelope.md` open questions closed

- **"Is there a global name table per bundle, per record, or inline every
  time?"** — per record. `Proto_Struct.keys` (field 1, packed `uint64`) are
  indices into that *same record's* `Document.constants.strings` (field 6
  → field 2, repeated). Confirmed directly: the `"default"` row's sample had
  three keys `[0, 1, 2]`, three `Struct.values` entries, and exactly three
  strings (`"bounds"`, `"frame"`, `"drawing"`) in its `constants` table —
  index-for-index.
- **"What are the 19 bare-protobuf-but-UUID-keyed outlier rows?"** — plain
  `Proto_Document` blobs, structurally and semantically identical to the
  `crdt`-prefixed rows, just **missing the 8-byte `crdt`+u32-version
  prefix**. Confirmed on all 19, not a sample: every one decodes cleanly as
  `Document` with zero unmapped field numbers (see the sweep below), each
  landing on a `Register`/`Optional`/`Reference`-shaped payload like
  ordinary content rows. Why some rows skip the header wasn't investigated
  further — worth checking whether it correlates with `RetainCount`, row
  age, or something about the write path that produced them.

## Not yet done / open questions

- `OrderedSet`/`Set`/`Sequence`/`Subsequence`'s real internal field numbers
  — the 38-row sweep failure above pins the exact break (`Set.Element.key`
  isn't really a `Proto_Value` on the wire, at least not for the ordered-
  list tie-break usage) but doesn't resolve it. This is very likely the
  same "position key + monotonic tie-break tag + `4294967295` sentinel"
  structure `envelope.md` found from raw bytes alone, now attached to a
  real type name (`Proto_OrderedSet` → `Proto_Set`) but not a confirmed
  field-by-field mapping.
- `custom_crdt`(10)'s `Sibling.version_or_payload` — when it doesn't decode
  as `Version`, what is it? Point-data-shaped bytes were seen in one
  example (`0125AD5D-....bundle`, a `.5MB`-ish row) — worth cross-checking
  against `com.apple.drawing.2`'s known point-channel layout
  (`../com.apple.drawing.2/point-decode-pipeline.md`) to see if it's the
  same encoding reused, or something new.
- `Proto_Version`/`Proto_RLE`'s own field numbers don't cleanly fit
  declaration order either: one root-row sample's `Document.version(5)`
  decoded as `{1: "\000", 2: {2: "\000\344\007"}}` — the outer `Version`
  message matches (`uuids`=1, `indexSets`=2), but the inner `Proto_RLE`
  entry showed content at tag *2*, not `RLE.runs`'s expected tag 1. Marked
  `// assumed` with this caveat in the `.proto`; not resolved.
- `Proto_Document`'s unobserved fields (`references`, `assets`,
  `deltaReferences`, `minVersion`) — never appeared in this sample (an
  entirely reasonable outcome: `Assets` the sqlite *table* was empty in
  every bundle too, per `envelope.md`, and per-row `references`/
  `deltaReferences` may only populate on specific write paths, e.g. actual
  merges). Field numbers unknown until one turns up.
- `Proto_CRDT` cases 2/3/5/6/7/8/9 (`set`, `dictionary`, `string`,
  `sequence`, `array`, `counter`, `ordered_set`) — never observed. Numbers
  are declaration-order guesses only.
- The `Peer_*` sync protocol and `Proto4_*` schema (see above) — noted, not
  investigated.
- Real disassembly (walking `__swift5_proto` witness tables to
  `decodeMessage(decoder:)`, per `coherence-framework.md`'s original plan)
  is still the only way to get 100%-certain field numbers for everything
  above marked `// assumed`. `swift-dump` made it unnecessary for the
  common-path messages; it may still be worth it for `Document`'s
  unobserved fields and the `CRDT` cases that never showed up in this
  21-bundle sample.

## Methodology

1. Same source Mac as `coherence-framework.md` (macOS 26.6.2, build 25G83,
   Apple Silicon), reached via SSH (`ethan@<LAN IP>` this session — slower
   link than the primary working environment, budget for it).
2. Located the live shared cache:
   `/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e`
   (the cryptex-mounted one, not a bare `/System/Library/dyld/...` path,
   which doesn't exist standalone on this OS version).
3. `ipsw swift-dump "$DSC" "$DYLIB" --type 'Proto_.*' --demangle
   --headers -o ~/re-notes/coherence-headers` — dumped everything, worked
   around the filename-collision issue (above) by also concatenating every
   `.swift` file it wrote and grepping the concatenation for the handful of
   nested types `--headers` clobbered, re-fetching those individually with
   a tighter `--type` regex.
4. Real sample data: the same 21-bundle set as `envelope.md`
   (`archive/backup/takeout/apple/notes/2024-03-29/ethan/data/Accounts/
   .../Paper/Bundles/*.bundle/Database/data.sqlite3`), opened locally
   (already `git-annex`-fetched) via Python's `sqlite3` module with the
   `immutable=1` URI parameter (same reasoning as
   `apple-notes-paper-dump.pl --immutable`).
5. Validated field numbers by hand-tracing `protoc --decode_raw` output
   against the swift-dump'd field lists (see the two decode chains above),
   then wrote `apple-notes-paper.proto` and ran `protoc --decode=Document`
   against real rows as the actual correctness test — a message that
   doesn't match the true field types throws a hard parse error (invalid
   varint / invalid UTF-8 / wire-type mismatch), unlike `decode_raw`, which
   never fails and will happily mis-render anything.

## Provenance

- Source Mac: macOS 26.6.2 (build 25G83), Apple Silicon, reached via SSH
  this session (same box as `coherence-framework.md`, different network
  path).
- Tooling: `ipsw` (blacktop/ipsw, v3.1.714 per its generated-file
  boilerplate), `protoc` 36.1, Python 3's `sqlite3` stdlib module.
- Sample data: the same 21 `*.bundle/Database/data.sqlite3` files as
  `envelope.md`, all already present locally via `git-annex`.
