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
| `main/data/Backups/Backup-2020-12-*` (MBP) | pre-Big Sur, most likely Mojave 10.14.6 (see below) | `VRpS` | 1044 |

Same model on 14.1.1 and 14.2.1, with the lower build on the earlier point
release: consistent.

The 2020 backups are inference. The MBP history has 10.14.6 (Security
Update 2019-001, 2019-11) and then "macOS Big Sur 11.1 **Update**" on
2021-01-03. "Update" implies 11.0.x was already installed, and that install
is not listed, so it went through some other route (e.g. the App Store
installer). Major upgrades through Software Update *are* listed (Sonoma
14.0, Sequoia 15.4 on the MBP; Tahoe 26.5.2 on `cooper`). So the
`Backup-2020-12-24`/`-26` directories are most likely Notes' pre-migration
copies taken while upgrading to Big Sur, and `VRpS` is the model of the
OS before it: Mojave 10.14.6 if nothing else was installed unlisted
(Catalina never appears, but an unlisted installer upgrade cannot be ruled
out). Four backups over three days suggest the migration ran more than
once.

Resulting lineage: `VRpS` (Mojave, through 2020-12) -> `jaiz` (Sonoma
14.1-14.2) -> `eFiI` (Sequoia 15.8.1; the MBP is on 15.7.7, not examined).
No Big Sur through Ventura store was archived, and none from Tahoe (`cooper`
is on Tahoe now).

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
