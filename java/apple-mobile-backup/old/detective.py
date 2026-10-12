import argparse
import os
import sys
import sqlite3

from stat import S_ISDIR
from stat import S_ISREG
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
    args = ap.parse_args()

    db = sqlite3.connect(os.path.join(args.src, 'Manifest.db'))
    db.row_factory = sqlite3.Row
    try:
        AAAAAAAAAAA(db, args.src)
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

def AAAAAAAAAAA(db, src):
    cur = db.cursor()
    cur.execute(BASE_QUERY)
    seen = {}
    for (fid, domain, path, flags, info) in cur:
        dinfo = nd.deserialize_plist_from_string(info)
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
        fullpath = os.path.join(domain, path).rstrip(os.sep)
        if fullpath.lower() in seen:
            print('ERROR', fullpath.lower())
            print('previously:')
            for k, v in seen[fullpath.lower()].items():
                sys.stdout.write('  ')
                rdks.plist.writer(sys.stdout).write(k, oct(v) if k ==
                'Mode' else v.hex() if isinstance(v, bytes) else
                str(v) if isinstance(v, (str, int)) else repr(v))
            print('now:')
            print('  FileId', fid)
            print('  Domain', domain)
            for k, v in dinfo.items():
                sys.stdout.write('  ')
                rdks.plist.writer(sys.stdout).write(k, oct(v) if k ==
                'Mode' else v.hex() if isinstance(v, bytes) else
                str(v) if isinstance(v, (str, int)) else repr(v))
        dinfo['FileId'] = fid
        dinfo['Domain'] = domain
        seen[fullpath.lower()] = dinfo

if __name__ == '__main__':
    main()
