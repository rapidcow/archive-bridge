# WAL files, JDBC, and working copies

Applies to both `NoteStore.sqlite` and every `Paper/Bundles/*.bundle/Database/
data.sqlite3` (the `com.apple.paper` bundles are separate SQLite databases
with their own `-wal`/`-shm`, unlike `com.apple.drawing.2`, whose payload is
a column of `NoteStore.sqlite`). So the WAL question is per bundle, not once
per archive.

Established 2026-10-05 by experiment, using `org.xerial:sqlite-jdbc`
3.53.4.0 (the version in `java/gradle/libs.versions.toml`) against a small
synthetic WAL database whose rows existed only in the `-wal`. It was NOT
tested on Apple's own files, and every bundle in the sampled account has a
0-byte `-wal` (see Limits).

## Does the JDBC driver flush the WAL?

There is no Java-specific strategy: the driver embeds SQLite, so the
behavior is SQLite's.

| Case                                                         | Result |
|--------------------------------------------------------------|--------|
| read-only open, full trio (`db`, `-wal`, `-shm`)             | WAL contents visible; nothing written |
| read-only open, `db` + `-wal`, no `-shm`                     | WAL contents visible; **a `-shm` was created next to the original** |
| read-only open, main file alone (`-wal` not copied)          | **silently** missing data (`no such table` here) |
| `?immutable=1`                                               | WAL ignored: same silent loss |
| normal open of a copy, `wal_checkpoint(TRUNCATE)`, `journal_mode=DELETE` | WAL folded in; only the main file remains; it then reads correctly read-only on its own |

Consequences:

- Reading needs no flush, provided the `-wal` travels with the database.
- The dangerous failures are silent: a `-wal` that was not copied, or
  `immutable=1`. Neither raises an error.
- Even a read-only open touches the directory of the original (`-shm`), and
  a normal open can checkpoint and delete the original's `-wal`. Both are
  wrong for an archive and fail on read-only media (e.g. git-annex objects).
  So never open originals in place.

## `VACUUM INTO` is not needed per bundle

`VACUUM INTO` rewrites the entire file. Checkpointing a copy gets the same
standalone result more cheaply:

1. copy the database and, if present, its `-wal` to a temp directory
   (`-shm` is optional; the experiment dropped it and still read correctly);
2. open the copy normally; `PRAGMA wal_checkpoint(TRUNCATE)`, then
   `PRAGMA journal_mode=DELETE`;
3. use the resulting single file read-only; delete the temp copy afterwards.

The originals are never opened, so they are never modified. Bundles are small
(the 52 sampled ones total about 46 MB, ~0.9 MB each), so doing this lazily,
once per bundle, when it is first opened is cheap and invisible to a user.

## Where this lives in the library

- `NoteStore.open(Path)` stays as it is: it assumes a standalone file,
  opens it read-only, and never looks for or creates sidecars (a test
  enforces this). It does not know WALs exist.
- A separate preparation step ("working copy") is the only code that knows,
  and does the three steps above for either a store or a bundle.
- For the large `NoteStore.sqlite` (hundreds of MiB) the copy costs real
  time and disk, so that is where the application should let the user
  choose: *make a working copy* / *abort* / *use as is (may miss recent
  changes)*. `VACUUM INTO` becomes an optional compaction, not a requirement.
  The explicit choice is passed down; the library does not decide.

## Opening in place is never safe (added 2026-10-12)

Two more ways an in-place open goes wrong, both seen on real stores:

- **`mode=ro` still writes `-shm`.** Opening `ethan/db/notes` (writable
  files) read-only for `VACUUM INTO` rewrote its `NoteStore.sqlite-shm`;
  the main file and `-wal` stayed byte-identical. Only the read-only
  `cloud-ethan-1` files were left untouched (SQLite then keeps the WAL
  index in heap memory). Where `-shm` is missing, an open in a writable
  directory creates one.
- **git-annex symlinks hide the WAL.** In `~/archive/backup/takeout/...`
  every file is a symlink to `.git/annex/objects/.../SHA256E-...`. SQLite
  resolves the symlink *before* deriving the WAL name, so it looks for
  `SHA256E-...-wal` next to the annex object, which never exists, and
  silently reads the main file alone. The real `NoteStore.sqlite-wal`
  symlink is never consulted.

Copying the trio (as the probes do: `File::Copy` follows symlinks) avoids
both. `ai/tools/apple-notes-wal-merge.py` avoids both without copying the
WAL: it reads main + WAL as plain files and writes one merged database
(checked against SQLite: identical `.dump` to `VACUUM INTO` on
`cloud-ethan-1`).

**A valid WAL may not belong to its database.** The WAL header refers to
nothing in the main file, so SQLite (and the merge tool) replay any WAL
whose own salts and checksums are intact. All four
`main/data/Backups/Backup-2020-12-*` trios are mismatched: replaying gives
`integrity_check` failures; two of the WALs build a fresh 55-page database
(checkpoint sequence 0) that relies on pages not present in the 108 MB main
file; and two backups share a byte-identical main file (annex key
`m7/pm/...777c1e...`) with different WALs. Their main files alone are
consistent (`quick_check` ok). So run `integrity_check` after folding in a
WAL, and fall back to the main file alone, with a warning, when it fails.

## Limits

- One small synthetic database, not Apple's files.
- All 52 sampled bundles (and the `Reference` row counts read from them with
  `immutable=1`) had 0-byte `-wal` files. A bundle copied while Notes was
  running may well not; nothing here shows what that looks like in practice.
- The preparation step cannot tell "WAL was checkpointed" from "WAL file was
  lost". Apple's own databases carry the WAL-mode header (bytes 18-19 of the
  file = `02 02`) even with an empty `-wal`, so a missing `-wal` is not
  evidence of lost data. It may warn when the header says WAL and no `-wal`
  exists, but cannot prove anything is missing.
- Tooling in `ai/tools/` already follows this for the main store
  (`apple-notes-store-probe.pl` and `apple-notes-paper-probe.pl` copy the
  trio and open the copy; `--immutable` warns on a non-empty WAL). The
  per-bundle opens in `apple-notes-paper-probe.pl` still use `immutable=1`,
  with a warning when a bundle's `-wal` is non-empty.
