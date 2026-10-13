# Where the model comes from, and what Core Data does with Z_MODELCACHE

Scope: the compiled Notes model shipped in macOS, and how CoreData itself
writes and reads `Z_MODELCACHE`. Established 2026-10-12 on the Mac mini,
macOS 15.8.1 (24H32, arm64), by reading the model files and disassembling
CoreData in the dyld shared cache with `ipsw` 3.1.728. Background:
`schema-and-model.md` (format), `model-survey.md` (models in the archive).

## The compiled model: NotesShared.framework

```
/System/Library/PrivateFrameworks/NotesShared.framework/Versions/A/Resources/
    NoteData.mom                     current model (the IC* entities)
    NoteDataOld.momd/NoteData1.1.mom older generation (+ NoteData1.1.omo)
```

The same files are also in `XPCServices/com.apple.Notes.datastore.xpc/
Contents/Resources/` (byte-identical `NoteData.mom`). A `.mom` is the same
`NSKeyedArchiver` binary plist as `Z_MODELCACHE`, just not compressed and
listing only each entity's *own* properties (the cache repeats inherited
ones). It is not a "seed" that generated the database: Notes hands this
model to Core Data, Core Data creates the tables from it, and stores a
copy in `Z_MODELCACHE`.

- **`NoteData.mom` on macOS 15.8.1 = the `eFiI` model**, structurally
  identical (entities, declared properties, types, relationships) to both
  current stores (`ethan/db/notes`, `cloud-ethan-1/db/notes`). Check:
  `apple-notes-store-model.py STORE --compare .../NoteData.mom`.
- The 2024-03 stores (`jaiz`) lack 12 of its properties and have the old
  `unappliedEncryptedRecord`. The 2020 backups (`VRpS`) lack
  `ICNoteParticipant` and 60 properties.
- `NoteData1.1.mom` is an older lineage: it has `NextId` and
  `ICSearchIndexTransaction`, and no `ICAccountData`, `ICInvitation`,
  `ICHashtag`, `ICInlineAttachment`, `ICNoteParticipant`. None of the
  archived stores uses it. Presumably it is kept to migrate very old stores.

**ciofeca's "iOS 26" marker fires on macOS 15.** `ICNote.attributedSnippet`
(`ZATTRIBUTEDSNIPPET`, which `guess_ios_version` maps to iOS 26) is in
Sequoia 15.8.1's model. It is at least not exclusive to 26; it probably
arrived in an 18.x / 15.x point release.

## Store provenance (from the archive's git log, with .mailmap applied)

Use `%aN <%aE>` (mailmap-aware) rather than `%an <%ae>`: `.mailmap` maps
`ethan@yizz.me` to `rapidcow@RCs-MacBook-Pro.local`.

| store | committed | host | model |
|---|---|---|---|
| `2024-03-29/ethan/data` | 2024-03-29 (`2a51c89c8512`) | `cooper` (iMac; Tahoe now) | `jaiz`, build 1333 |
| `2024-03-29/main/data` | 2024-03-29 (`52125875333a`) | MacBook Pro (Intel; never past Sequoia) | `jaiz`, build 1338 |
| `main/data/Backups/Backup-2020-12-*` | inside the above | MacBook Pro: Notes' own pre-migration backups, Dec 2020 | `VRpS`, build 1044 |
| `usr/ethan/db/notes`, `usr/cloud-ethan-1/db/notes` | 2026-10 | this Mac mini, macOS 15.8.1 | `eFiI`, build 1448 |

### Matched against each machine's update history

From `info/macOS/<host>.txt` (`TZ=UTC softwareupdate --history`, added by
the user 2026-10-12; all dates below are UTC). `NSPersistenceFrameworkVersion` is the CoreData build
that last wrote the store's metadata, so it matches the OS installed when
the store was last saved, not necessarily the one that created it.

| store | OS installed on that date | model | CoreData build |
|---|---|---|---|
| `2024-03-29/main/data` (MBP) | Sonoma 14.2.1 (installed 2023-12-30; next 15.4 on 2025-04-12) | `jaiz` | 1338 |
| `2024-03-29/ethan/data` (`cooper`) | Sonoma 14.1.1 (installed 2023-11-19; next 14.5 beta on 2024-05-05) | `jaiz` | 1333 |
| `usr/*/db/notes` (Mac mini) | Sequoia 15.8.1 (installed 2026-10-02; stores copied out 2026-10-04) | `eFiI` | 1448 |
| `main/data/Backups/Backup-2020-12-*` (MBP) | Big Sur 11.0.1 (installed 2020-11-14; 11.1 on 2021-01-03), see below | `VRpS` | 1044 |

Same model on 14.1.1 and 14.2.1, with the lower build on the earlier point
release: consistent.

The MBP ran Catalina, never Mojave in practice (the user wiped it to
Catalina: System Information shows Catalina 10.15.1 installed 2019-10-29,
10.15.7 on 2020-10-24, Big Sur 11.0.1 on 2020-11-13/14; local times
UTC-5/-6). The 2020 backups were **last written on Big Sur 11.0.1**, not
before the upgrade: their newest note edits are 2020-12-24 10:13 and
2020-12-25 15:23 UTC, and their history has 11-14k transactions per month
through Nov-Dec 2020. Earlier versions of this note called them
"pre-Big Sur, Mojave", which was wrong.

### The stores log their own migrations

Core Data's persistent history records every schema migration as a
transaction whose author is the string `com.apple.coredata.schemamigrator:
lightweight migration from model with digest { <SOURCE DIGEST> }`, with
bundle `com.apple.Notes` or `com.apple.accountsd`. List them with:

```sql
SELECT s.ZNAME, datetime(t.ZTIMESTAMP + 978307200, 'unixepoch')
FROM ATRANSACTION t JOIN ATRANSACTIONSTRING s
  ON s.Z_PK IN (t.ZAUTHORTS, t.ZCONTEXTNAMETS, t.ZBUNDLEIDTS, t.ZPROCESSIDTS)
WHERE s.ZNAME LIKE '%migration%' ORDER BY t.ZTIMESTAMP;
```

Every entry found coincides with an OS update in `info/macOS` (all UTC):

| store | migrated from | at | OS update |
|---|---|---|---|
| MBP backups | `Dv3/j6Ce...` | 2019-11-01 14:39 | Catalina 10.15.1 (2nd install) at 12:21, then Safari 13.0.3 12:48 and "Security Update 2019-001 (10.14.6)" 13:41 |
| MBP backups | `VRpS` | 2020-11-14 06:36:56 | "macOS 11.0.1", installed 2020-11-14 ~06:36 |
| MBP `main/data` | `Ouhkd...` | 2021-06-17 05:17 | Big Sur 11.4, 05:00 |
| MBP `main/data` | `Yxs2z...` | 2022-06-20 08:32 | macOS 12.4, same morning |
| MBP `main/data` | `G4IyF...` | 2023-05-16 04:01 | Ventura 13.3.1, ~03:46 |
| MBP `main/data` | `jaiz` | 2023-10-04 01:22 | Sonoma 14.0 (2023-10-03 21:00) |
| `cooper` `ethan/data` | `G4IyF...` | 2023-08-12 11:16 | Ventura 13.5, 11:03 |
| `cooper` `ethan/data` | `jaiz` | 2023-10-04 01:29 | Sonoma 14.0, 01:25 |
| Mac mini empty store | `eFiI` | 2026-10-02 06:42 | Sequoia 15.8.1, 06:36 |

No migration string lacks its transaction (none were pruned).

Catalina on the MBP, from System Information (UTC, converted by the
user): 10.15.1 installed 2019-10-29 23:04 and again 2019-11-01 12:21.
The store itself starts on **2019-10-25 05:22:23**: its first transaction
is by `com.apple.accountsd` (the iCloud account being added), and this
Mac's own `ICDeviceMigrationState` row has the same timestamp (the other
six rows, dated 2018-08 to 2019-11, are other devices, synced down). The
initial sync follows on 10-26 (1,317 transactions), and writes continue
daily across both Catalina installs. So the store was created on this
Mac on 10-25, after the reset the user describes, and was never erased
afterwards.

The store's schema always comes from the Notes installed locally, never
from iCloud: CloudKit carries records, not the Core Data model. So `Dv3`
is the model of whatever OS ran on 2019-10-25. The install history does
not say which: its first Catalina entry is 10-29, but the reset may well
have installed an OS that the history does not list. The user recalls the
Mac being on Catalina right after the reset; if so, `Dv3` is a Catalina
model too, and the 2019-11-01 migration is Catalina to Catalina (possibly
same-digest, like those below). No migration is logged at the 10-29
install.

Read as "model in use before this update", the log gives a chain:

| digest | in use (MBP unless noted) |
|---|---|
| `Dv3/...` | from store creation (2019-10-25) to 2019-11-01; OS not recorded, likely Catalina (see above) |
| `VRpS` | Catalina (to 2020-11-14) and, per the backups, Big Sur 11.0.1 through 2020-12-25 |
| `Ouhkd...` | before Big Sur 11.4 (2021-06-17) |
| `Yxs2z...` | before macOS 12.4 (2022-06-20), i.e. Big Sur 11.4-11.6.2 (the MBP skipped 12.0-12.3) |
| `G4IyF...` | Monterey (before Ventura 13.3.1 on the MBP, 13.5 on `cooper`) |
| `jaiz` | Ventura 13.3.1/13.5 through Sonoma 14.2.1 at least |
| `eFiI` | Sequoia 15.8.1 (and the eFiI -> eFiI migration says 15.x before it) |

**Unresolved:** the backups say the store was migrated from `VRpS` at
11.0.1 yet is still `VRpS` six weeks later (a same-digest migration, as
above). The new `main/data` store, whose history starts 2020-12-25 17:12
(right when the backups were taken, presumably a rebuild), first logs a
migration "from `Ouhkd`" at 11.4, with no "from `VRpS`" ever. So either
that store was created as `Ouhkd` by 11.0.1 (contradicting `VRpS` as 11.0.1's
model), or a `VRpS -> Ouhkd` step happened without a log entry, e.g. at
11.1 (2021-01-03). The archive cannot tell which.

Only `VRpS`, `jaiz` and `eFiI` models are in the archive; `Dv3`, `Ouhkd`,
`Yxs2z`, `G4IyF` are known by digest only. No Tahoe store exists (`cooper`
is on Tahoe now, the MBP on 15.7.7).

## What CoreData does with Z_MODELCACHE (macOS 15.8.1 CoreData)

Symbols (all in the CoreData image; addresses are this build's):

| function | role |
|---|---|
| `-[NSSQLiteConnection saveCachedModel:]` | `DELETE FROM Z_MODELCACHE`, or `CREATE TABLE Z_MODELCACHE (Z_CONTENT BLOB)` if absent; compress (libcompression, hence raw DEFLATE); `INSERT`. Logs "Saving new model cache". |
| `-[NSSQLiteConnection fetchCachedModel]` | `SELECT Z_CONTENT FROM Z_MODELCACHE` if the table exists; decompress; unarchive. |
| `-[NSSQLCore cachedModelWithError:]`, `+[NSSQLCore cachedModelForPersistentStoreWithURL:options:error:]`, `+[NSPersistentStore cachedModelForPersistentStoreWithURL:options:error:]` | public-ish wrappers: "give me the model stored in this file". |
| `+[NSPersistentContainer(CoreDataSPI) persistentContainerUsingCachedModelWithPath:]` | private API: open a store using *its own cached model*, no app model needed. This is what our reader does. |

**Writes** (the three callers of `saveCachedModel:`):

1. `-[NSSQLiteConnection connect]`, on the **store creation** path: right
   after `CREATE TABLE Z_METADATA (...)` and `saveMetadata:`, it saves the
   coordinator's model. A new store gets its metadata and cache together.
2. `-[NSSQLCore _ensureDatabaseMatchesModel]`, on **every open of an
   existing store**: if the store is not read-only and has **no**
   `Z_MODELCACHE` table, it back-fills one from the current model. An
   existing cache is not refreshed here.
3. `-[_NSSQLiteStoreMigrator performMigration:]`, **after a migration**:
   the cache is replaced with the destination model.

So the cache always describes the model the tables were last built for,
not the model of whichever app opened the store last. That is the property
our reader relies on. (One consequence: the 2026 empty store's cache lacks
class names while the cloud store's has them, same digest. The empty store
records a lightweight migration, so its cache was written by path 3 and the
cloud one presumably by 1 or 2. Which model object each path archives was
not traced; this explanation is a guess.)

**Reads.** `-[NSStoreMigrationPolicy sourceModelForStoreAtURL:metadata:error:]`
finds the *source* model for a migration by matching the store's metadata
hashes against candidate models, in this order (taken from its debug-log
strings):

1. bundles in `_NSAutomaticMigrationResourceBundlesOption`
2. the main bundle
3. **"model cached in database file"** ("Choosing model from cache table in db")
4. all bundles
5. all frameworks
6. "Last ditch review of models with skewed hashing"
7. otherwise "Can't find model for source store"

`-[NSPersistentStoreCoordinator migrateStoreWithContext:error:]` also calls
`cachedModelForPersistentStoreWithURL:`. Other readers include Apple's own
CloudKit debugging CLI (`NSCloudKitMirroringDelegate(__CLI)
printMetadataForStoreAtURL:...`, `printRepresentativeSchemaForModelAtURL:
orStoreAtURL:...`). So reading a store through its cache is what Apple's
tooling does as well.

Why it exists, in short: migration needs the *old* model, and an app that
has been updated may no longer ship it. The cache makes every store
self-describing, and Core Data keeps it current at exactly the points where
the table layout changes.

## Not established

- What `connect` / `_ensureDatabaseMatchesModel` do with a cache whose
  digest disagrees with `Z_METADATA` (presumably impossible in normal use).
- Whether `NoteDataOld.momd` is ever used as a migration source on 15.x.
- Builds other than 15.8.1. The model in an iOS 18 Notes was not examined.
