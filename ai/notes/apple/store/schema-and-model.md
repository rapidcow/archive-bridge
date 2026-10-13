# NoteStore.sqlite: metadata, model, and how the ZIC tables relate

Scope: the Core Data layer of `NoteStore.sqlite`: where the schema version
and the object model are stored, and how entities, tables, columns and
relationships map onto each other. Blob payloads (note protobuf, drawings,
paper) are covered elsewhere.

Samples, observed 2026-10-12:

- `ethan/db/notes`: a freshly set-up store with no notes (6 objects: account,
  account data, 2 device-migration states, default + trash folder).
- `cloud-ethan-1/db/notes`: 51 notes, 52 attachments (all `com.apple.paper`).

Each was turned into one standalone file with
`sqlite3 'file:<orig>?mode=ro' "VACUUM INTO '<copy>'"`; the WAL contents are
included (cloud: 51 notes, matching the WAL-applied count in
`com.apple.paper-envelope.md`). **Caveat:** `mode=ro` did not leave the
writable original untouched: SQLite rewrote `NoteStore.sqlite-shm` (the
WAL index; main file and `-wal` stayed byte-identical). The read-only
`cloud-ethan-1` files were untouched, since SQLite falls back to a heap WAL
index when `-shm` is not writable. So "open read-only" alone does not keep
an original pristine. See `wal-and-working-copies.md`.

## Where the metadata is

Three Core Data bookkeeping tables:

| table | content |
|---|---|
| `Z_METADATA` | one row. `Z_VERSION` = 1 (version of the *metadata format*, not of the model), `Z_UUID` = store UUID, `Z_PLIST` = binary plist (below). |
| `Z_MODELCACHE` | one row, `Z_CONTENT` = **raw DEFLATE** (no zlib/gzip header; `Inflater(nowrap=true)`) of an `NSKeyedArchiver` bplist of the full `NSManagedObjectModel`. ~35 KB to ~70 KB. |
| `Z_PRIMARYKEY` | one row per entity: `Z_ENT`, `Z_NAME`, `Z_SUPER`, `Z_MAX` (the last `Z_PK` handed out). |

`Z_METADATA.Z_PLIST` (identical in both samples):

```
NSPersistenceFrameworkVersion     1448            # Core Data build, i.e. OS era
NSStoreModelVersionHashes         {entity: 32-byte SHA-256, ...}   # 20 entities
NSStoreModelVersionHashesDigest   "eFiI+OaI3aU7..."  # 64-byte digest over the above
NSStoreModelVersionChecksumKey    "ht8N6a10dwqS..."
NSStoreModelVersionHashesVersion  3
NSStoreModelVersionIdentifiers    [""]            # Apple sets no human label
NSStoreType                       "SQLite"
_NSAutoVacuumLevel                "2"
```

So **there is no human-readable schema version**. The version identity is the
`NSStoreModelVersionHashesDigest` (or the per-entity hashes, which say
*which* entities changed). `NSPersistenceFrameworkVersion` dates the OS but is
not a schema key. Per-row `ZMINIMUMSUPPORTEDNOTESVERSION` (0 on everything
except attachments: 9, 10, 15 here) is a feature level for sync
compatibility, **not** a schema version.

### The model hashes do not cover everything

The two samples share the same digest but their `Z_MODELCACHE` blobs differ
(980 vs 1020 archived objects). Decoded, the only difference is that the
empty store's archive has no `NSClassNameForEntity` and the cloud one has
it. Entities, properties, types and relationships are identical. Core Data's
version hash ignores class names, indexes, user info and similar. Also, the
empty store's `ATRANSACTIONSTRING` contains `com.apple.coredata.schemamigrator:
lightweight migration from model with digest { eFiI+OaI3aU7... }`, a
"migration" from the digest it is still on. Uninterpreted; possibly a
migration triggered by a non-hashed model change. Consequence: equal digest
means equal *table layout*, not byte-equal model cache.

## Z_MODELCACHE content (what can be loaded)

Archived classes and the keys that matter:

- `NSManagedObjectModel`: `NSEntities` (an `NSKnownKeysDictionary1`: values
  in `values` -> `NS.objects`), `NSVersionIdentifiers`, `NSFetchRequestTemplates`.
- `NSEntityDescription`: `NSEntityName`, `NSSuperentity` (UID of the
  parent), `NSSubentities`, `NSIsAbstract`, `NSProperties`
  (`NSKnownKeysDictionary1`, **includes inherited properties**),
  `NSUniqueTuples`, `NSVersionHashModifier`, `NSRenamingIdentifier`,
  `NSClassNameForEntity` (may be absent).
- `NSAttributeDescription`: `NSPropertyName`, `NSAttributeType` (100 int16,
  200 int32, 300 int64, 500 double, 600 float, 700 string, 800 bool,
  900 date, 1000 binary, 1800 transformable, ...), `NSIsOptional`,
  `NSValueTransformerName`, `NSAttributeValueClassName`, `NSDefaultValue`.
- `NSRelationshipDescription`: `NSPropertyName`, `_NSDestinationEntityName`,
  `_NSInverseRelationshipName`, `NSMinCount`, `NSMaxCount` (1 = to-one,
  0 = to-many), `NSDeleteRule` (1 nullify, 2 cascade).

Decoding it takes a bplist reader, UID dereferencing, and handling of
`NSArray`/`NSDictionary`/`NSKnownKeysDictionary1`; nothing is
Objective-C-specific. The decoded model is in `model-eFiI-entities.txt`.

## Entities -> tables (single-table inheritance)

Each *root* entity owns a table named `Z` + upper-cased name; every
subentity's rows live in its root's table, told apart by `Z_ENT`.

| table | root entity | subentities (Z_ENT) |
|---|---|---|
| `ZICCLOUDSYNCINGOBJECT` | ICCloudSyncingObject (2) | AccountData 3, Attachment 4, AttachmentPreviewImage 5, DeviceMigrationState 6, Hashtag 7, InlineAttachment 8, LegacyTombstone 9, Media 10, Note 11, NoteContainer 12 (abstract) -> Account 13, Folder 14 |
| `ZICCLOUDSTATE` | ICCloudState (1) | |
| `ZICINVITATION` | ICInvitation (15) | |
| `ZICLOCATION` | ICLocation (16, abstract) | AttachmentLocation 17 |
| `ZICNOTEDATA` | ICNoteData (18) | |
| `ZICNOTEPARTICIPANT` | ICNoteParticipant (19) | |
| `ZICSERVERCHANGETOKEN` | ICServerChangeToken (20) | |
| `ACHANGE`, `ATRANSACTION`, `ATRANSACTIONSTRING` | Core Data persistent history (16001-16003) | |

`Z_ENT` numbering is a pre-order walk of the hierarchy with roots and
siblings in alphabetical order. `Z_PK` is unique per *table* (all 13
`ZICCLOUDSYNCINGOBJECT` entities share one counter; `Z_PRIMARYKEY.Z_MAX` is
kept on the root row, 226 = max(Z_PK) in cloud).

## Properties -> columns (the suffix rule)

**Rule, verified exactly against `pragma table_info` on both stores (every
predicted column exists, no column unexplained, 252 mappings):**

1. Walk entities in `Z_ENT` order.
2. For each entity take only the properties it *declares* (drop names
   that its superentity also has).
3. Skip to-many relationships; they have no column (the inverse to-one
   holds the FK). There are no many-to-many join tables in this model.
4. Column base = `Z` + upper-cased property name. Within one table, the
   k-th declaration of a base (0-based, in the walk order) gets suffix `k`
   (none for k = 0).
5. A to-one relationship whose destination has subentities also gets
   `Z<dest Z_ENT>_<NAME>` holding the target's `Z_ENT`
   (`ZICCLOUDSTATE.Z2_CLOUDSYNCINGOBJECT`, `ZICINVITATION.Z2_ROOTOBJECT`).

Hence for this model, e.g.:

| entity.property | column |
|---|---|
| ICAccountData.account / ICAttachment.account / DeviceMigrationState / Hashtag / InlineAttachment / LegacyTombstone / Media / **ICNote.account** / ICFolder.account | `ZACCOUNT`, `1`, `2`, `3`, `4`, `5`, `6`, **`7`**, `8` |
| ICAttachment.note / ICInlineAttachment.note | `ZNOTE` / `ZNOTE1` |
| ICAttachment.title / ICNote.title / ICFolder.title | `ZTITLE` / `ZTITLE1` / `ZTITLE2` |
| ICAttachment.creationDate / Hashtag / InlineAttachment / ICNote | `ZCREATIONDATE` / `1` / `2` / `3` |

This is exactly why the suffixes "move between OS versions": adding one
entity or property that sorts earlier shifts every later suffix. ciofeca
(`AppleNoteStore.rb#guess_ios_version`) handles it by sniffing marker
columns (`ZATTRIBUTEDSNIPPET` => iOS 26, `ZUNAPPLIEDENCRYPTEDRECORDDATA`
=> 18, ...) and hard-coding a per-version suffix table. That table can
lag reality: his note query still selects `ZACCOUNT2`, which in this model
is `ICDeviceMigrationState.account` and is NULL on all 51 notes. The full
map for this model is in `model-eFiI-columns.tsv`.

The rule is inferred from one model (two stores). Untested: models with
many-to-many relationships (ciofeca's iOS 11 `Z_11NOTES` suggests older
models had one), ordered relationships (`Z_FOK_*`), and stores where the
model cache is missing.

## Relationship graph (the parts that matter for reading notes)

```
ICAccount(13) ──accountData──> ICAccountData(3)          [ZACCOUNTDATA]
    ^  ^
    |  └── ICFolder(14).account [ZACCOUNT8]; ICFolder.parent [ZPARENT] -> ICFolder
    |                                       ICFolder.owner [ZOWNER] -> ICAccount
    └───── ICNote(11).account  [ZACCOUNT7]
           ICNote.folder       [ZFOLDER]  -> ICFolder
           ICNote.noteData     [ZNOTEDATA] <-> ZICNOTEDATA.ZNOTE   (1:1, both sides set)
           ICNote.titleSourceAttachment [ZTITLESOURCEATTACHMENT] -> ICAttachment
ICAttachment(4).note           [ZNOTE]    -> ICNote
ICAttachment.media             [ZMEDIA]   -> ICMedia(10)   (ICMedia.attachment [ZATTACHMENT1])
ICAttachment.parentAttachment  [ZPARENTATTACHMENT] -> ICAttachment   (galleries etc.)
ICAttachment.location          [ZLOCATION] -> ZICLOCATION
ICAttachmentPreviewImage(5).attachment [ZATTACHMENT] -> ICAttachment
ICInlineAttachment(8).note [ZNOTE1], .parentAttachment [ZPARENTATTACHMENT1]  (hashtags, mentions, links in text)
every ICCloudSyncingObject.cloudState [ZCLOUDSTATE] -> ZICCLOUDSTATE
every ICCloudSyncingObject.invitation [ZINVITATION] -> ZICINVITATION
```

Observed invariants (cloud sample): every `ICNote.ZACCOUNT7` points at an
ICAccount row, every `ZFOLDER` at an ICFolder, every note's `ZNOTEDATA`
round-trips, every attachment's `ZNOTE` is a live note, every preview's
`ZATTACHMENT` is set.

**A 1:1 relationship is not always stored on both sides.** `cloudState`:
`ZICCLOUDSYNCINGOBJECT.ZCLOUDSTATE` is set on all 214 objects, but the
inverse `ZICCLOUDSTATE.ZCLOUDSYNCINGOBJECT` is NULL on all 226 cloud-state
rows (12 of which no object references, presumably left over from deleted
rows). In contrast, `noteData` is set on both sides. A reader should follow
the side that is known to be populated, and should not assume Core Data
kept the inverse.

Folder rows carry `ZFOLDERTYPE` (0 default, 1 trash) and identifiers
`DefaultFolder-CloudKit` / `TrashFolder-CloudKit`. Trash is a folder, not a
flag.

## Persistent history (ACHANGE / ATRANSACTION)

Standard `NSPersistentHistory` tables. `ACHANGE.ZENTITY` = `Z_ENT`,
`ZENTITYPK` = `Z_PK`, `ZCHANGETYPE` 0/1/2 = insert/update/delete,
`ZCOLUMNS` = a bitmask blob of updated properties (not decoded),
`ZTRANSACTIONID` -> `ATRANSACTION`, whose author, bundle and context
columns index into `ATRANSACTIONSTRING`. In the cloud sample all 463
transactions fall within 2026-10-04 14:23-14:28 UTC, i.e. one sync session.
History is pruned and does **not** contain an edit history of notes. It is
not worth archiving beyond the store itself.

## Implications for apple-notes-store (for the human to decide)

- Everything above can be loaded into memory at open time cheaply:
  `Z_METADATA` (one bplist), `Z_PRIMARYKEY` (already read by
  `NoteStore.readEntities`), and `Z_MODELCACHE` (one inflate plus a keyed
  archive walk, about 1000 objects). A Java reader needs a bplist parser
  (none in `libs.versions.toml` today; e.g. dd-plist, or a small
  hand-written one, since bplist00 is simple) and `java.util.zip.Inflater(true)`.
- With the model loaded, `NoteStoreRow.get("ZTITLE1")` could become
  `get("title")` resolved per entity. The rule above is checkable at open
  time: derive the map, compare with `pragma table_info`, fail loudly on
  any mismatch. That turns an unknown future layout into an error rather
  than a wrong column.
- `NSStoreModelVersionHashesDigest` is the natural key for "have we seen
  this schema" (fixtures, test expectations); the iOS-version guess is
  only a label.
