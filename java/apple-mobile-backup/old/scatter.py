"""redistribute files

basically does this:
  xx/sha1 -> objects/xx/(truncated sha1).(ext)
          -> domains/(domain)/(relative path)

(i am writing this! and i am Ethan...! :3
neah... you probably already knew that)
"""

import argparse
import json
import os
import sqlite3
import subprocess
import sys

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

# stolen code O_O
from dirhash import hashdirmixed

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
    # (cursors are iterators!)
    # Martijn thank you QvQ
    # https://stackoverflow.com/a/29582757
    for (fid, domain, path, flags, info) in cur:
        print('extracting', (fid, domain, path))
        dinfo = nd.deserialize_plist_from_string(info)
        dirmain = os.path.join(dst, 'domains', domain)
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
        name = get_filename(domain, path)
        fullpath = os.path.join(dirmain, name).rstrip(os.sep)
        if flags == 2:
            # create the directory anyways even though git will not
            # track it, this is so that we can get warned about
            # duplicate names
            os.makedirs(fullpath, exist_ok=True)
        else:
            os.makedirs(os.path.dirname(fullpath), exist_ok=True)
        # ... so that i can do this >:)
        if flags == 1:
            origpath = os.path.join(src, fid[:2], fid)
            os.link(origpath, fullpath)
        with (
          open (
            fullpath + '.log', 'x',
            encoding='utf-8',
            newline='',
          )
        ) as fp:
            w = rdks.plist.writer(fp)
            filename = os.path.basename(path) or domain
            w.write('index for', filename + '?*/?@'[flags])
            w.write('FileId', fid)
            w.write('Domain', domain)
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

# we make a few exceptions for these bastards.... (see guilty)
def get_filename(domain, path):
    # never ask me how i name my variables
    many_fish = [
        ('HomeDomain',
            'Library/Preferences/com.apple.', 'SpringBoard', '.plist'),
        ('HomeDomain',
            'Library/Preferences/com.apple.', 'Messages', '.plist'),
        ('HomeDomain',
            'Library/Preferences/com.apple.', 'Preferences', '.plist'),
        ('HomeDomain',
            'Library/Preferences/com.apple.', 'MobileSMS', '.plist'),
        ('AppDomain-com.tencent.xin',
            'Documents/f1f2ed2df335e8d7f8747d83cee50ac2/', 'Kara', ''),
        ('AppDomain-com.tencent.xin', 'Documents/', 'Log', ''),
        ('AppDomain-com.tencent.qqmail', 'Documents/', 'Log', ''),
    ]
    for duolingo, prefix, TunaFish, suffix in many_fish:
        if domain != duolingo:
            continue
        if path == prefix + TunaFish + suffix:
            return TunaFish + '_camel' + suffix
        tuna_fish = TunaFish.lower()
        # it technically is not snake case but
        # using animal names is funny... X)
        if path == prefix + tuna_fish + suffix:
            return TunaFish + '_snake' + suffix
    return path

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
DIRMIXED_CHARS = '0123456789zqjxkmvwgpfZQJXKMVWGPF'

# once we have created our Object MAP, it is trivial to
# add them to git-annex
def create_objects(db, src, dst):
    ANNEX_OBJECTS_DIR = os.path.abspath(os.path.join(
      dst,
      subprocess.run(
        ['git', 'rev-parse', '--git-path', 'annex/objects'],
        cwd=dst, capture_output=True, text=True,
      ).stdout.rstrip()
    ))

    # common options
    BATCH = ['--batch', '--json', '--json-error-messages']
    annex_cmd = ['git-annex', 'add', '--force-large', *BATCH]
    # XXX: GIT-ANNEX-ADD BATCH MODE DOESNT WORK W/O
    # --NO-CHECK-GITIGNORE????
    annex_cmd.append('--no-check-gitignore')
    # examinekey isn't so talkative >:c i'll hash it myself...
    #exam_cmd = ['git-annex', 'examinekey', '--force-large', *BATCH]

    omap = {}

    def talk_to(proc, line):
        proc.stdin.write(line)
        proc.stdin.write('\n')
        proc.stdin.flush()
        resp = proc.stdout.readline()
        if not resp:
            if proc.returncode:
                # the traceback should be enough to tell
                # which process it was, so we're just gonna...
                raise ValueError(f'process terminated with '
                                 f'code {proc.returncode}')
            raise ValueError('stdout closed when i am '
                             'expecting a line')
        if resp == '\n':
            return {}
        return json.loads(resp)

    with subprocess.Popen(
        annex_cmd, cwd=dst, text=True,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
    ) as annex_proc:
        cur = db.cursor()
        cur.execute(BASE_QUERY + ' WHERE flags = 1')
        for (fid, domain, path, flags, info) in cur:
            print('annexing', (fid, domain, path))
            name = get_filename(domain, path)
            dirmain = os.path.join('domains', domain)
            # we shouldn't have to rstrip(os.sep) since
            # we know these are all files
            file_virt = os.path.join(domain, name)
            file_real = os.path.join(dirmain, name)
            response = talk_to(annex_proc, file_real)
            if not response:
                #print('ERROR: git-annex rejected', file_real,
                #      file=sys.stderr)
                raise RuntimeError(f'git-annex rejected {file_real}')
                continue
            if not response['success']:
                #print('ERROR: git-annex failed for',
                #      file_real + ':',
                #      '\n'.join(response['error_messages']),
                #      file=sys.stderr)
                raise RuntimeError(
                    'ERROR: git-annex failed for {}: {}'.format(
                        file_real, '\n'.join(response['error_messages'])
                    ),
                )
                continue
            key = response['key']
            file_norm = file_virt.lower()
            assert file_norm not in omap
            omap[file_norm] = key

    cur = db.cursor()
    cur.execute(BASE_QUERY + 'WHERE flags = 1')
    for (fid, domain, path, flags, info) in cur:
        # recreate the same file path
        print('linking', (fid, domain, path))
        name = get_filename(domain, path)
        dirmain = os.path.join(dst, 'domains', domain)
        file_virt = os.path.join(domain, name)
        file_real = os.path.join(dirmain, name)
        key = omap[file_virt.lower()]
        frag, ment = fid[:2], fid[2:]
        bucket = os.path.join(dst, 'objects', frag)
        pointy = os.path.join(dst, 'objects', frag, ment)
        obpath = os.path.join(ANNEX_OBJECTS_DIR,
                              hashdirmixed(key.encode('ascii')),
                              key, key)
        os.makedirs(bucket, exist_ok=True)
        os.symlink(
            os.path.relpath(obpath, bucket),
            pointy)
        os.unlink(file_real)
        os.symlink(
            os.path.relpath(pointy,
            os.path.dirname(file_real)),
            file_real)

if __name__ == '__main__':
    main()
