"""redistribute files

basically does this:
  xx/sha1 -> objects/xx/(truncated sha1).(ext)
          -> domains/(domain)/(relative path)

(i am writing this! and i am Ethan...! :3
neah... you probably already knew that)
"""

import argparse
import os
import subprocess
import sqlite3

# stumbled upon this by pure chance :o
# (digital forensics... so cool...)
# https://github.com/ydkhatri/nska_deserialize
import nska_deserialize as nd
# ... and after all i think i like my plist better >v>
import rdks.plist

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src', help='backup root dir (i.e. '
                    '~/Unictool_Full_Backup/*/*/)')
    ap.add_argument('dst', help='target repository path '
                    '(i.e. corresponding path in archive/backup)')
    args = ap.parse_args()

    # sqlite3 connection as a context manager does not
    # close upon __exit__, so we have to resort to our
    # old try/finally idiom...
    db = sqlite3.connect(os.path.join(args.src, 'Manifest.db'))
    db.row_factory = sqlite3.Row
    try:
        omap = extract_paths(db, args.src, args.dst)
        create_objects(db, args.src, args.dst)
    finally:
        db.close()

BASE_QUERY = """\
SELECT
  fileId       AS id,
                  domain,
  relativePath AS path,
                  flags,
  file         AS info
FROM Files
"""

def extract_paths(db, src, dst):
    import pprint
    cur = db.cursor()
    cur.execute(BASE_QUERY)
    # (cursors are iterators!)
    # Martijn thank you QvQ
    # https://stackoverflow.com/a/29582757
    for (fid, domain, path, flags, info) in cur:
        import time
        time.sleep(1)
        dinfo = nd.deserialize_plist_from_string(info)
        dirmain = os.path.join(dst, domain)
        # if path is empty we'd get a trailing slash
        # so... get rid of it if it's the case
        fullpath = os.path.join(dirmain, path).rstrip(os.sep)
        os.makedirs(os.path.dirname(fullpath), exist_ok=True)
        # ... so that i can do this >:)
        with (
          open (
            fullpath + '.log', 'w',
            encoding='utf-8',
            newline='',
          )
        ) as fp:
            w = rdks.plist.writer(fp)
            for k, v in sorted(dinfo.items(), key=_info_priority):
                if isinstance(v, (str, int)):
                    w.write(k, str(v))
                else:
                    w.write(k, repr(v))

_INFO_PRI_LIST = [
    'RelativePath',             # we should know this, still eh
    'Size', 'InodeNumber',      # this is in gnu stat order btw
    'Mode', 'Flags',            # os mode like 755/600?
    'UserID', 'GroupID',        # UID and GID
    'Birth',                    # creation time
    'LastModified',             # st_mtime
    'LastStatusChange',         # st_ctime
    'ProtectionClass',          # ????
]

def _info_priority(pair):
    k, v = pair
    try:
        ranking = _INFO_PRI_LIST.index(k)
    except ValueError:
        # effectively infinity
        ranking = 0x3f3f3f3f
    return (ranking, k, v)

# once we have created our Object MAP, it is trivial to
# add them to git-annex
def create_objects(db, src, dst):
    query = BASE_QUERY + ' WHERE fileId = :file_id'
    cur = db.cursor()
    def get(file_id):
        return cur.execute(query, {'file_id': file_id})

if __name__ == '__main__':
    main()
