#!/usr/bin/env python3
"""Fold a SQLite -wal into its database, writing one new file, without SQLite.

  apple-notes-wal-merge.py DB WAL OUT

Reads DB and WAL (never opens them with SQLite, so no -shm is created or
rewritten next to them) and writes OUT: the database as SQLite would see
it after replaying the WAL, i.e. a manual checkpoint into a copy. Frames
count only up to the last commit frame whose salts and cumulative checksum
are valid, which is what SQLite's WAL recovery does when there is no -shm.
OUT's header is switched from WAL to rollback mode (bytes 18-19 = 1) so that
opening it never looks for a -wal.

Why not just let SQLite do it: in a git-annex tree NoteStore.sqlite is a
symlink to .git/annex/objects/.../SHA256E-..., and SQLite resolves the
symlink before deriving the WAL name, so it looks for SHA256E-...-wal,
which never exists, and silently ignores the real NoteStore.sqlite-wal.

Validated on cloud-ethan-1: `.dump` of OUT is identical to SQLite's own
`VACUUM INTO` of the same trio. Prints (frames, commits, uncommitted frames
ignored, database pages after replay).

Caution: a valid WAL is not necessarily one that belongs to DB. The WAL
header binds to nothing in the main file, so a mismatched pair replays
"successfully" into a corrupt result; always run `pragma integrity_check`
on OUT. (The Notes Backups/ trios of 2020-12 are such pairs.)
"""

import struct
import sys

WAL_MAGIC = {0x377f0682: '<2I', 0x377f0683: '>2I'}


def checksum(data, s1, s2, fmt):
	for i in range(0, len(data), 8):
		a, b = struct.unpack(fmt, data[i:i + 8])
		s1 = (s1 + a + s2) & 0xffffffff
		s2 = (s2 + b + s1) & 0xffffffff
	return s1, s2


def merge(db_path, wal_path, out_path):
	with open(db_path, 'rb') as f:
		main = f.read()
	with open(wal_path, 'rb') as f:
		wal = f.read()
	if len(wal) < 32:
		raise ValueError('WAL shorter than its header (empty WAL: nothing to merge)')
	magic, _version, page_size, _ckpt_seq, salt1, salt2, c1, c2 = struct.unpack('>8I', wal[:32])
	if magic not in WAL_MAGIC:
		raise ValueError('not a WAL file: magic %#x' % magic)
	fmt = WAL_MAGIC[magic]
	s = checksum(wal[:24], 0, 0, fmt)
	if s != (c1, c2):
		raise ValueError('WAL header checksum mismatch')

	committed, pending = {}, {}
	frames = commits = 0
	db_pages = None
	off = 32
	while off + 24 + page_size <= len(wal):
		page, commit, fs1, fs2, fc1, fc2 = struct.unpack('>6I', wal[off:off + 24])
		data = wal[off + 24:off + 24 + page_size]
		if (fs1, fs2) != (salt1, salt2):
			break
		s = checksum(wal[off:off + 8] + data, s[0], s[1], fmt)
		if s != (fc1, fc2):
			break
		pending[page] = data
		frames += 1
		if commit:
			committed.update(pending)
			pending = {}
			db_pages = commit
			commits += 1
		off += 24 + page_size

	buf = bytearray(main)
	if db_pages is not None:
		size = db_pages * page_size
		buf = buf[:size] + bytes(max(0, size - len(buf)))
		for page, data in committed.items():
			if page <= db_pages:
				buf[(page - 1) * page_size:page * page_size] = data
	buf[18:20] = b'\x01\x01'
	with open(out_path, 'wb') as f:
		f.write(buf)
	return frames, commits, len(pending), db_pages


if __name__ == '__main__':
	if len(sys.argv) != 4:
		sys.exit(__doc__)
	print(merge(*sys.argv[1:]))
