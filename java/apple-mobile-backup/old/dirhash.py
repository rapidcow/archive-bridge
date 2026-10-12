"""
git-annex directory hashing algorithms implemation,
written by Giovanni Mascellani (@giomasce) in 2015.
the mixed case was driving me insane, so i gave up trying to understand

but it works!!!! and that's all i care really :)

linkies:
    https://git-annex.branchable.com/internals/hashing/#comment-faa1b65b88900b930d074e54e2440d01
    https://gist.github.com/giomasce/a7802bda1417521c5b30

the code is said to be based on Annex/DirHash.hs but lord
do i not understand a single bit of Haskell (pun intended)
"""

import hashlib
import struct

# we are in Python 3, so xrange is just range
xrange = range

def hashdirlower(key):
    hasher = hashlib.md5()
    hasher.update(key)
    digest = hasher.hexdigest()
    return "%s/%s/" % (digest[:3], digest[3:6])

def hashdirmixed(key):
    hasher = hashlib.md5()
    hasher.update(key)
    digest = hasher.digest()
    first_word = struct.unpack('<I', digest[:4])[0]
    nums = [first_word >> (6 * x) & 31 for x in xrange(4)]
    letters = ["0123456789zqjxkmvwgpfZQJXKMVWGPF"[i] for i in nums]
    return "%s%s/%s%s/" % (letters[1], letters[0], letters[3], letters[2])
