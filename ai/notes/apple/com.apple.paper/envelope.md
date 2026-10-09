# Newer PencilKit/"Paper" bundle format — is it protobuf?

Personal-data-recovery notes on the on-disk format of the newer ("Coherence")
Apple Notes drawing storage, as found in `*.bundle/Database/data.sqlite3`
files under `Accounts/<account-uuid>/Paper/Bundles/` in a Notes data
directory. This supersedes `com.apple.drawing.2` (see
`../com.apple.drawing.2/`) for drawings created/edited on a device running
the newer Notes storage engine ("Coherence").

This directory does not yet have a confirmed UTI string for this format —
the containing folder is just named `Paper`, no `ZTYPEUTI` value has been
read for it yet. `com.apple.paper` here is a placeholder name for this notes
directory, not a confirmed identifier.

## Question asked this session: is it protobuf?

**Yes**, empirically, by direct inspection of real bundle data (21 bundles,
45,170 `Reference` rows total) — no disassembly was done this session, this
is purely "does the byte layout parse as valid protobuf wire format."
See Methodology below for exactly how this was checked.

This is a different, and less thorough, kind of evidence than
`com.apple.drawing.2`'s envelope notes (which are grounded in reading
PencilKit's actual parsing code) — treat everything below as "consistent
with protobuf, confirmed by successful parse," not "confirmed by reading the
decoder."

## What a bundle is

Each `*.bundle` is a directory containing exactly `Database/data.sqlite3`
(plus `-wal`/`-shm`). The sqlite3 file has two tables:

```sql
CREATE TABLE Reference(
  Id BLOB PRIMARY KEY NOT NULL,
  Version BLOB,
  RetainCount INT,
  ChildRetainCounts BLOB,
  Data BLOB NOT NULL);
CREATE TABLE Assets(
  Id BLOB PRIMARY KEY NOT NULL,
  RetainCount INT,
  Data BLOB);
```

`Assets` was empty in every one of the 21 bundles sampled — not yet
characterized at all.

This two-table, `Id`/`Data`-blob shape plus the `Reference`/`allReferences`
naming is consistent with the prior session's finding (see
`../../../our/ai/claude/archives/.../decode_paper_fixed.swift`, reached via
Apple's private `Coherence` framework by live reflection, not by reading
this database directly) that the in-memory object graph is a
`Capsule<Paper>` whose `capsuleReference.references.allReferences` is a
`TreeDictionary<CRKeyPath, CapsuleReference>` — i.e. this sqlite `Reference`
table *is* that dictionary's on-disk form, one row per `CRKeyPath` →
`CapsuleReference` entry, and `Coherence` is Apple's name for whatever CRDT
engine reads it.

## `Id` encoding

`Id` is `CRKeyPath`'s raw bytes. First byte observed to take exactly three
values, over all 45,170 rows sampled:

| first byte | count  | meaning (inferred)                                         |
|------------|--------|-------------------------------------------------------------|
| `0x01`     | 21     | followed by an ASCII name, only `"default"` seen (7 bytes)  |
| `0x02`     | 45,128 | followed by a 16-byte UUID (matches `NSUUID` byte layout)   |
| `0xFF`     | 21     | no further bytes; one such row per bundle                   |

Exactly one `0x01 "default"` row and one `0xFF` row per bundle (21 bundles →
21 of each), so these look like fixed, well-known keys: `0xFF` is a
per-bundle root/manifest record, `"default"` is presumably the name of the
root `Paper` object itself (referenced generically, before/without knowing
its UUID).

## `Data` encoding

Three shapes were found, keyed by how `Data` starts:

1. **`0xFF`-keyed rows (21 total):** `Data` is *bare* protobuf — no header,
   `protoc --decode_raw` parses it directly. Contents: a small tree whose
   field 3 → field 5 → field 2 holds the bytes `\x01default` (i.e. embeds
   another row's raw `Id`), plus what look like a 2-byte length/version pair,
   a 16-byte value, and a second, different 16-byte value (uuid-shaped) —
   not further decoded. Read as: this is the bundle's own manifest, pointing
   at the `"default"`-keyed root entry.

2. **Most rows (45,130 total, both `"default"`- and UUID-keyed): `Data`
   starts with the literal ASCII bytes `crdt`, then a 4-byte little-endian
   integer, always observed as `6` in every sample, then a normal protobuf
   message.** Stripping the 8-byte `crdt`+`u32` prefix and feeding the rest
   to `protoc --decode_raw` parses cleanly every time this was tried. Example
   (the `"default"` row from one bundle, after stripping):

   ```
   1 {
     4 {
       1: "\000\001\002"
       2 { 1 { 1 { 2: 11 } 2 { 4: "...32 bytes (4x float64)..." } } }
       2 { 1 { 1 { 1: 1  2: 1 } 2 { 4: "...32 bytes..." } } }
       2 { 1 { 1 { 1: 2  2: 1 } 2 { 9 { 1 { 8 { 5 { 2: "...16 bytes, uuid-shaped..." } } } } } } }
     }
   }
   6 {
     1: "...29 raw bytes (hash? not decoded)..."
     2: "bounds"
     2: "frame"
     2: "drawing"
   }
   ```

   Two things worth flagging:
   - The 32-byte strings under field `4` decode cleanly as four
     little-endian IEEE-754 `float64`s each — e.g. one is
     `0, 0, 812.0, 88.0` (bytes `40 88...` / `40 58...` as big-endian-style
     doubles once you account for protoc printing raw big-endian octal
     escapes) — shaped like a `CGRect` (`x, y, width, height`), matching the
     `_bounds` field the prior Swift attempt read off `Paper` via reflection.
     This double-as-4-floats convention for rects is the same one already
     confirmed (by disassembly) for `com.apple.drawing.2` — see
     `../com.apple.drawing.2/point-decode-pipeline.md` — so it's plausible,
     not newly proven, that the underlying rect/point primitives are shared
     between the two formats even though the enclosing envelope differs.
   - Top-level field `6` is a **literal string table** — `"bounds"`,
     `"frame"`, `"drawing"` are exactly the property names you'd expect for
     a `Paper` object. This means (at least some of) these records are
     self-describing: small integers seen elsewhere in the message
     (`1: 1`, `1: 2`, ...) are plausibly indices into this table, i.e. a
     schema-free/self-describing property dictionary rather than a
     compiled-in field-number scheme. Not yet confirmed which indices map
     to which names — the table above only has 3 of however many entries a
     larger record needs.

   The 4-byte version integer being `6` in literally every sample (across
   21 different bundles/documents) makes it a good candidate for "current
   CRDT wire format version" rather than something that varies per-record;
   no record with a different version was found in this sample.

3. **19 outlier rows, all UUID-keyed (`0x02`), that are bare protobuf like
   the `0xFF` rows — no `crdt` prefix at all — despite being keyed the same
   way as the 45,109 that *do* have it.** Sizes ranged from 55 to 7,707
   bytes. Not yet characterized; open question whether these are a
   different CRDT node type (e.g. an immutable/non-versioned leaf that
   doesn't need CRDT bookkeeping) or something unrelated. `protoc
   --decode_raw` parses all of them without the prefix strip.

## Possible ordered-sequence CRDT structure (not confirmed)

`com.apple.drawing.2` turned out to need real disassembly to learn that
stroke paint order is *not* array order but an explicit `StrokeID`
(`sortID`, clock/replicaIndex/subclock) — see
`../com.apple.drawing.2/point-decode-pipeline.md`. The obvious follow-up
question for a from-scratch CRDT format: does it even *have* an implicit
array order to be suspicious of, or is ordering explicit by construction?

One data point, from the `0xFF`-keyed root/manifest record of a bundle
(`44B21FC9-.../Bundles/44B21FC9-....bundle`, its own self-referential
UUID-keyed row, after stripping the `crdt` header):

```
2 { 9 { 1 { 1: 4  2 { 1: "\005"  2 { 2: "\000," } } }
        2 { 3 { 1 { 1: 1 }              4: "\001" }
            3 { 1 { 1: 2  2: 8 } 2: 14  4: "\002" }
            3 { 1 { 1: 2  2: 7 } 2: 1   4: "\003" }
            3 { 1 { 1: 2  2: 1 } 2: 6   4: "\004" }
            3 { 1 { 1: 2 }       2: 1   4: "\005" }
            3 { 1 { 1: 1  2: 4294967295 } }
            4 { 1: "\002"  2 { 2: "\000\026" } }
            5 { 1: "\003"  2 { 2: "\000," } } } } }
```

Repeated `field 3` entries, each carrying a `(N[, M])` position-pair key
plus a single, sequentially-incrementing tie-break byte (`\001`…`\005`),
and one entry whose position pair is `(1, 4294967295)` —
`4294967295 = 0xFFFFFFFF`, a plausible sentinel/boundary value. This shape
(position key + monotonic tie-break tag + a max-value sentinel entry) is
consistent with an RGA/Fugue-style ordered-list CRDT encoding, i.e. an
*explicit* ordering primitive, structurally distinct from the
hash-keyed `Reference` table itself. Not confirmed what this particular
list orders (this sample is from the bundle-root manifest, not obviously a
stroke list) or whether it's the same primitive `Paper.strokes` (if that's
even how strokes are collected) would use. Worth checking for this same
`field3`-repeated-with-incrementing-tag shape wherever an actual ordered
collection (a `Paper`'s child elements, a stroke's `substrokes`, etc.) turns
up once more records are decoded.

## Methodology

1. Located real sample data: `~/archive/backup/takeout/apple/notes/
   2024-03-29/ethan/data/Accounts/<uuid>/Paper/Bundles/*.bundle/Database/
   data.sqlite3` — 21 bundles, reachable via `git-annex` (content was
   already fetched for these specific files, unlike most of the rest of that
   backup tree).
2. `sqlite3` refused to open these files in place (`unable to open database
   file`, code 14) — a sandboxed-write restriction on creating
   `-journal`/`-wal` siblings next to a read-only annex object, not a
   problem with the files themselves (`file(1)` and reading the SQLite
   magic bytes directly both confirmed they're intact); confirmed sqlite3
   itself wants a lock, not a Claude-Code-sandbox-specific issue. Worked
   around at first by copying each `data.sqlite3`(`-wal`) pair to a scratch
   directory. `apple-notes-paper-dump.pl --immutable` (added later this
   session) opens the annex objects directly via sqlite's `immutable=1` URI
   parameter and needs no copy — prefer that going forward.
3. Dumped every `(Id, Data)` row from every bundle via `sqlite3`/Python
   (`sqlite3` stdlib module), histogrammed `Id`'s first byte and `Data`'s
   first 4 bytes, and — for the `crdt`-prefixed rows — verified the 4-byte
   version field is a stable little-endian `6` and that the remainder of the
   blob is a plausible protobuf submessage by feeding it to `protoc
   --decode_raw` (protoc 36.1, no message-type definitions needed, since
   `decode_raw` free-parses wire format without a `.proto`).
4. No `.proto` schema was written yet, and no disassembly was performed this
   session — this is meant to be a jumping-off point for that, not a
   finished decoder.

## Update (later session): field names, numbers, and a real `.proto`

See `proto-schema.md` for the full story and
`../../tools/apple-notes-paper.proto` for the schema itself - both new this
session. Highlights, resolving several items below:

- **Field-name resolution: answered.** `ipsw swift-dump` against the live
  dyld shared cache (not the standalone extract) reads real field
  names/types straight out of Swift's own metadata for every `Proto_*`
  type, no disassembly needed. The `crdt`-wrapped messages' outer envelope
  is `Proto_Document`; the string table seen at field 6 is
  `Proto_Constants`.
- **"Is there a global name table per bundle, per record, or inline every
  time?": answered — per record.** `Proto_Struct.keys` are indices into
  that same record's `Document.constants.strings`, confirmed index-for-
  index on the `"default"` row's sample (3 keys ↔ 3 strings).
- **The 19 bare-protobuf-but-UUID-keyed outlier rows: answered.** Ordinary
  `Proto_Document` blobs, just missing the 8-byte `crdt`+u32-version
  prefix — not a different type.
- **A `.proto` file now exists**: `../../tools/apple-notes-paper.proto`,
  field-numbered and checked with `protoc --decode` against real rows
  across all 21 bundles (partial failures localized to `CRDT.custom_crdt`
  rows — see `proto-schema.md`, likely where drawing/rich content actually
  lives in this format).
- The two opaque byte strings (16-byte and 29-byte) are now understood
  structurally: the 16-byte one is `Document.version_uuid` (root rows) or a
  `KeyPath.data` (a referenced row's Id); the 29-byte one is
  `Constants.uuids`, whose exact semantics (hash? concatenated uuids of a
  different count?) are still unconfirmed.

## Not yet done / open questions

- What `Assets` holds (empty in every sample so far).
- What `CustomCRDT.Sibling`'s second field actually is on the ~half of
  sampled rows where it doesn't parse as `Proto_Version` — likely where
  drawing/PaperKit content lives; see `proto-schema.md`.
- `Proto_Version`/`Proto_RLE`'s exact field numbers (one sample's nested RLE
  entry didn't match declaration-order expectations - see `proto-schema.md`).
- The UTI string for this format has not actually been read off any
  `ZICCLOUDSYNCINGOBJECT` row yet — `com.apple.paper` is this repo's working
  name for the notes directory, not a confirmed constant.
