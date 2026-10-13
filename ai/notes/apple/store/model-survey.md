# Z_MODELCACHE across the archive: should the model be hard-coded?

Question: is the Core Data model (and so the property-to-column map; see
`schema-and-model.md`) stable enough to hard-code, or must a reader derive
it from each store?

Surveyed 2026-10-12: every `NoteStore.sqlite` under
`~/archive/backup/takeout/apple/notes/` (one snapshot, `2024-03-29/`) plus
the two current stores (`ethan/db/notes`, `cloud-ethan-1/db/notes`).
Reproduce with `ai/tools/apple-notes-store-model.py STORE --meta --check`.

## Result: three models in ten stores

| store | Core Data build | model digest | entities | columns derived | WAL |
|---|---|---|---|---|---|
| `main/data/Backups/Backup-2020-12-24_18-58-42` | 1044 | `VRpS7RxDLS4Z...` | 16 (+3 history) | 168 | mismatched, main alone used |
| `main/data/Backups/Backup-2020-12-24_19-45-34` | 1044 | `VRpS...` | | 168 | mismatched |
| `main/data/Backups/Backup-2020-12-24_19-47-28` | 1044 | `VRpS...` | | 168 | mismatched |
| `main/data/Backups/Backup-2020-12-26_01-12-31` | 1044 | `VRpS...` | | 168 | mismatched |
| `ethan/data` (2024-03-29) | 1333 | `jaizlrxAvTJE...` | 20 (+3) | 240 | merged, ok, model unchanged |
| `main/data` (2024-03-29) | 1338 | `jaiz...` | 20 (+3) | 240 | merged, ok, model unchanged |
| `ethan/db/notes` (2026-10) | 1448 | `eFiI+OaI3aU7...` | 20 (+3) | 251 | VACUUM INTO |
| `cloud-ethan-1/db/notes` (2026-10) | 1448 | `eFiI...` | 20 (+3) | 251 | VACUUM INTO |
| `*/output/NoteStore.sqlite` | | `jaiz...` | | | ciofeca's working copies, see below |

- **The derived column map matched `pragma table_info` exactly on all eight
  Apple stores** (all three models). The rule is not specific to one model.
- The two `output/` copies fail the check by exactly two columns,
  `ZICNOTEDATA.ZDECOMPRESSEDDATA` and `ZPLAINTEXT`, which ciofeca's parser
  adds to its copy. They are not Apple stores; the check correctly flags
  them.
- The same digest occurred with different Core Data builds (1333 vs 1338),
  and with different `Z_MODELCACHE` bytes (2026: with and without class
  names). Build and cache bytes do not identify the schema; the digest does.
- None of the WALs that could be applied touch `Z_MODELCACHE`/`Z_METADATA`
  (only `Z_PRIMARYKEY.Z_MAX`). Two of the mismatched 2020 backup WALs do
  write them, but those WALs belong to some other, freshly created
  database (see `wal-and-working-copies.md`), so they are not evidence of a
  fourth model.

## What changed between models

**2020 -> 2024** (+4 entities, +72 properties, none removed):
new entities `ICHashtag` (7), `ICInlineAttachment` (8), `ICInvitation`,
`ICNoteParticipant` (collaboration, tags, mentions); new properties such as
`ICAttachment.fallbackImageGeneration`, `paperBundleGeneration`,
`ICFolder.smartFolderQueryJSON`, `ICNote.widgetSnippet`. Because
`ICHashtag`/`ICInlineAttachment` sort before `ICNote` and declare `account`
and `creationDate`, every later `Z_ENT` shifted (ICNote 9 -> 11) and five
existing properties changed column:

| property | 2020 | 2024, 2026 |
|---|---|---|
| ICNote.account | `ZACCOUNT3` | `ZACCOUNT7` |
| ICNote.creationDate | `ZCREATIONDATE1` | `ZCREATIONDATE3` |
| ICFolder.account | `ZACCOUNT4` | `ZACCOUNT8` |
| ICDeviceMigrationState.account | `ZACCOUNT1` | `ZACCOUNT2` |
| ICLegacyTombstone.account | `ZACCOUNT2` | `ZACCOUNT5` |

**2024 -> 2026** (same entities, +12 properties, 1 removed, 0 moved):
`unappliedEncryptedRecord` renamed to `unappliedEncryptedRecordData`
(a remove + add; ciofeca's iOS 18 marker); added
`ICAttachment.hasPaperForm`, `didRunPaperFormDetection`,
`needsTranscription`, `temporaryTranscriptData`; `ICNote.attributedSnippet`
(ciofeca's iOS 26 marker), `attributedTitle`, `hasEmphasis`,
`outlineStateData`, `hostApplicationIdentifier`, ...;
`ICCloudSyncingObject.hasMissingKeychainItem`.

Stable across all three: `ICNote.title` `ZTITLE1`, `modificationDate`
`ZMODIFICATIONDATE1`, `folder` `ZFOLDER`, `noteData` `ZNOTEDATA`;
`ICAttachment.note` `ZNOTE`, `typeUTI` `ZTYPEUTI`, `mergeableData`
`ZMERGEABLEDATA1`, `media` `ZMEDIA`; `ICFolder.title` `ZTITLE2`;
`ICNoteData.data` `ZICNOTEDATA.ZDATA`.

Every column move is explained by the naming rule; there is no unexplained
churn. But the moves hit exactly the columns a reader needs (a note's
account and creation date), and nothing in the column names signals that
they moved: `ZACCOUNT3` exists in both models, holding a note's account in
2020 and a hashtag's account in 2024.

## Answer (input for the design decision)

- **Hard-coding one model** would be wrong for the archive itself: the
  archive holds 2020 stores, and on those it reads `ZACCOUNT7` (absent) or,
  worse, `ZACCOUNT3` (present, meaning something else in later models).
- **Hard-coding a table per digest** (three entries today) works for what
  is in the archive but needs a new entry for each new Notes release, and a
  missing entry is only noticed if the reader checks for it.
- **Deriving from `Z_MODELCACHE`** has worked on every store seen, needs no
  per-release data, and comes with a built-in self-check against
  `pragma table_info`.
- A combination is possible: derive at open time, but keep the three known
  maps (or just their digests and a few key columns) as test fixtures, so
  a change in Core Data's naming rule shows up as a failing test.

## Limits

- One archive snapshot; only three models, all from 2020 or later. No iOS
  11-era store (ciofeca's `Z_11NOTES` many-to-many case) was available.
- The 2020 models were read from the backup main files only (their WALs
  are mismatched). Those main files pass `quick_check` and the column check.
- Mostly layouts were compared, not row contents. One spot check: in
  `Backup-2020-12-26`, 996 of 999 `ICNote` rows (`Z_ENT` 9) have
  `ZACCOUNT3` pointing at an `ICAccount` row (`Z_ENT` 11), and 961 have
  `ZCREATIONDATE1` set, as the derived map says.
