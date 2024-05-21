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

# test for directory (filemode matches 040xxx)
from stat import S_ISDIR
# test for regular files (filemode matches 100xxx)
from stat import S_ISREG
# test for symlinks (filemode matches 120xxx)
from stat import S_ISLNK

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
        extract_paths(db, args.src, args.dst)
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
    cur = db.cursor()
    cur.execute(BASE_QUERY)
    omap = {}
    # (cursors are iterators!)
    # Martijn thank you QvQ
    # https://stackoverflow.com/a/29582757
    for (fid, domain, path, flags, info) in cur:
        dinfo = nd.deserialize_plist_from_string(info)
        dirmain = os.path.join(dst, domain)
        # basic sanity check
        assert dinfo['RelativePath'] == path
        # we can do a switch-case / if-else to avoid multiple
        # comparison but these seem to be stronger mathematical
        # assertion of their equivalence really...
        #
        # plus, 3 isn't a lot, unless you are talking about years ;)
        assert S_ISREG(dinfo['Mode']) == (flags == 1)
        assert S_ISDIR(dinfo['Mode']) == (flags == 2)
        assert S_ISLNK(dinfo['Mode']) == (flags == 4)
        # if path is empty we'd get a trailing slash
        # so... get rid of it if it's the case
        fullpath = os.path.join(dirmain, path).rstrip(os.sep)
        if flags == 2:
            # create the directory anyways even though git will not
            # track it, this is so that we can get warned about
            # duplicate names
            os.makedirs(fullpath, exist_ok=True)
        else:
            os.makedirs(os.path.dirname(fullpath), exist_ok=True)
        #if fullpath == 'HomeDomain/Library/Preferences/com.apple.Messages.plist':
        print(domain, ':', path, '=', dinfo)
        if os.path.exists(fullpath):
            print('???')
            continue
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
                # write filemode in octal (e.g. 100644, 040755)
                if k == 'Mode':
                    w.write(k, format(v, '06o'))
                elif isinstance(v, (str, int)):
                    w.write(k, str(v))
                # md5 digest?
                elif k == 'Digest':
                    w.write(k, v.hex())
                else:
                    w.write(k, repr(v))
        if flags == 1:
            origpath = os.path.join(src, fid[:2], fid)
            os.link(origpath, fullpath)
            omap[fid] = os.path.join(domain, path)

_INFO_PRI_LIST = [
    'RelativePath',             # we should know this, still eh
    'Target',                   # symlinks have this
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
    annex_cmd = ['git-annex', 'add', '--batch', '--force-large']
    leftover = [] # do we do this???
    cur = db.cursor()
    cur.execute(BASE_QUERY)
    with subprocess.Popen(annex_cmd, cwd=dst) as proc:
        for (fid, domain, path, flags, info) in cur:
            file = os.path.join(domain, path)
            size = os.path.getsize(os.path.join(dst, file))
            if size < 32: # ... for zero reason
                leftover.append(fid, file)
                continue
            proc.stdin.write(file)
            proc.stdin.write('\n')
            # if successful...
            bucket = os.path.join('objects', frag)
            ladder = os.path.relpath(file, bucket)
            os.symlink(ladder, os.path.join(dst, bucket, ment))
    index_cmd = ['git', 'update-index', '--stdin']
    with subprocess.Popen(index_cmd, cwd=src) as proc:
        for fid, file in leftover:
            frag, ment = fid[:2], fid[2:]
            proc.stdin.write(file)
            proc.stdin.write('\n')
            # if successful...
            bucket = os.path.join('objects', frag)
            ladder = os.path.relpath(file, bucket)
            os.symlink(ladder, os.path.join(dst, bucket, ment))

if __name__ == '__main__':
    main()
