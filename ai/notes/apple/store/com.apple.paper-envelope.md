# com.apple.paper in NoteStore.sqlite — the envelope

Scope: how a `com.apple.paper` attachment is represented *inside
`NoteStore.sqlite`* and how it reaches its payload. The payload itself
(`Paper/Bundles/*.bundle/Database/data.sqlite3`, the Coherence/CRDT format)
is covered in `../com.apple.paper/`; nothing here decodes drawings.

Sample: the `cloud-ethan-1` account (`Accounts/7AB45D65-...`), observed
2026-10-04. Cross-checked against ciofeca's `apple_cloud_notes_parser`
(`lib/AppleNotesEmbeddedObject.rb`, `lib/AppleNotesEmbeddedDrawing.rb`).

## Headline: the DB row is a pointer, not a container

Unlike `com.apple.drawing.2` (strokes in `ZMERGEABLEDATA1`), a
`com.apple.paper` row carries **no payload blob**. Across all 52 rows,
`ZMERGEABLEDATA`, `ZMERGEABLEDATA1`, `ZMERGEABLEDATA2`, `ZMARKUPMODELDATA` are
all NULL, and `ZPAPERBUNDLEGENERATION` is NULL for every row in the table
(the column exists but this account never populates it). The Coherence data
lives only on disk. A reader of `NoteStore.sqlite` therefore needs just:
identify the row, resolve the file paths, hand the bundle to the Paper decoder.

## Row shape

`ZICCLOUDSYNCINGOBJECT`, `Z_ENT = 4` (`ICAttachment`), `ZTYPEUTI =
'com.apple.paper'`. Note `ZTYPEUTI` (not `ZTYPEUTI1`, which belongs to the
media entity). 52 rows, 1:1 with 52 notes (`ZNOTE` -> the owning
`ICNote`, `Z_ENT = 11`). Columns that matter:

| column | content |
|---|---|
| `ZIDENTIFIER` | attachment UUID (uppercase). **Is the bundle directory name** and the `FallbackImages` directory name. |
| `ZNOTE` | `Z_PK` of the owning note. |
| `ZFALLBACKIMAGEGENERATION` | e.g. `3_BAFE55B6-...`: generation dir for the rendered PNG. |
| `ZMETADATADATA` | UTF-8 JSON, see below. |
| `ZSIZEWIDTH`/`ZSIZEHEIGHT` | e.g. 616 x 1539 (display size, not content bounds). |
| `ZSUMMARY`/`ZHANDWRITINGSUMMARY`/`ZTITLE` | recognized handwriting text (OCR-ish; useful as free searchable text). |
| `ZSERVERRECORDDATA` | NSKeyedArchiver bplist of a `CKRecord` (record type `Attachment`, zone `Notes`, owner `__defaultOwner__`). Sync metadata only. |
| `ZREPLICAIDTONOTESVERSIONDATA` | protobuf (per-replica notes-version map). Sync metadata only; not needed. |
| `ZHASPAPERFORM`, `ZHASMARKUPDATA` | 0 on all rows seen; do not use as a "is this a paper attachment" test. |
| `ZMINIMUMSUPPORTEDNOTESVERSION` | 9..15 in this sample. |

`ZMETADATADATA` example (UTF-8 JSON, not a plist):

```json
{"paperContentBoundsWidthKey":722,"paperContentBoundsOriginYKey":11,
 "paperContentBoundsHeightKey":1876,"hasEnhancedCanvasKey":true,
 "paperContentBoundsOriginXKey":48}
```

The content bounds are the canvas rectangle in paper coordinates, so they are
likely the right crop/viewport for rendering. Treat the key set as open
(unknown keys must be preserved, not rejected).

## Path resolution (all under `Accounts/<account-uuid>/`)

```
Paper/Bundles/<ZIDENTIFIER>.bundle/Database/data.sqlite3   (+ -wal, 0 bytes here)
FallbackImages/<ZIDENTIFIER>/<ZFALLBACKIMAGEGENERATION>/FallbackImage.png
Previews/<ZIDENTIFIER>-1-768x768-<n>
```

Verified: bundle dir <-> `ZIDENTIFIER` is an exact 1:1 match for all 52 rows
(set difference empty); the fallback image path exists for all 52. This
contradicts nothing in ciofeca (he only ever fetches the fallback image;
`com.apple.paper` is routed to `AppleNotesEmbeddedDrawing` alongside
`com.apple.drawing(.2)` and he does not read the bundle at all), so
bundle reading is new ground.

## Note -> attachment link

The note body (`ZICNOTEDATA.ZDATA`, gzip, protobuf per ciofeca's
`notestore.proto`) holds one `U+FFFC` per attachment with an `AttributeRun`
whose `attachment_info` is `{attachment_identifier = ZIDENTIFIER, type_uti =
'com.apple.paper'}`. Verified on note `Z_PK=38` (a note consisting solely of
a drawing: text is exactly `￼`). So resolution is by UUID, not by
`ZNOTE` ordering; a note may hold several.

## Gotchas

1. **`immutable=1` ignores the WAL, and the WAL here is not empty.**
   `NoteStore.sqlite-wal` is 1.5 MB against a 0.9 MB main file. Reading with
   `?immutable=1` returned 52 notes and 52 `ZICNOTEDATA` rows; the same file
   with the WAL applied (copied to scratch, normal open) returned **51**.
   The immutable view is a stale snapshot. For archival, always copy
   `NoteStore.sqlite` + `-wal` + `-shm` together and open the copy
   read-write-capable (SQLite then checkpoints it; `integrity_check` = ok).
   The per-bundle `data.sqlite3-wal` files, by contrast, are all 0 bytes in
   this sample, so `immutable=1` is fine there, but should be re-checked per
   archive, not assumed.
2. `ZTYPEUTI` is NULL on 162 of 214 rows (other entities); filter on
   `Z_ENT = 4` as well so the query stays valid if another entity reuses the
   column.
3. `ZPAPERBUNDLEGENERATION` is in ciofeca's generation fallback chain but is
   NULL here; do not make bundle location depend on it.
4. Column names are positional-suffixed by Core Data (`ZNOTE`/`ZNOTE1`,
   `ZTYPEUTI`/`ZTYPEUTI1`, `ZMERGEABLEDATA`/`...1`/`...2`). The suffix assignment
   differs between OS versions; resolve columns by `pragma table_info` and
   `Z_PRIMARYKEY`, never hard-code an index.

## Implication for apple-notes-store

- `NoteStoreDrawings` (currently a drawing.2-only stub) needs a sibling or
  generalization: a query by `Z_ENT = 4 AND ZTYPEUTI IN (...)` returning a
  small value type {identifier, noteId, uti, size, metadata JSON, summary,
  fallback image path, bundle path}, with bundle bytes opened lazily.
- Snapshot handling should be a first-class, explicit step (copy trio to a
  temp dir), not a connection flag.
- JSON metadata needs a JSON dependency (Jackson or Gson); sqlite needs
  `org.xerial:sqlite-jdbc`; the note-body protobuf can reuse `protobuf-java`
  (already in `libs.versions.toml`) with ciofeca's `notestore.proto`.

## Open

- Not yet checked against the larger account (other UTIs, `ZTYPEUTI1`,
  encrypted/locked notes: `ZISPASSWORDPROTECTED` is 0 on all rows here, and
  the encrypted layout of the bundle is unknown).
- Whether a trashed/deleted attachment leaves an orphan bundle; here the sets
  matched exactly, so no evidence either way.
- `ZREPLICAIDTONOTESVERSIONDATA` and `ZSERVERRECORDDATA` are untouched beyond
  confirming their container types.
