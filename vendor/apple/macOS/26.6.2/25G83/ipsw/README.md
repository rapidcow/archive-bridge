These are framework/dylib binaries extracted with [`ipsw`](https://github.com/blacktop/ipsw)
from the dyld shared cache on my iMac ("cooper"), for reverse-engineering the
`com.apple.drawing.2` / PencilKit / Notes formats.

`sw_vers` on the source machine:

```
ProductName:		macOS
ProductVersion:		26.6.2
BuildVersion:		25G83
```

Source dyld shared cache:

```
/System/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e
```

Each binary was pulled out with `ipsw dyld extract <DSC> <framework-binary-path>`,
e.g.:

```
ipsw dyld extract /System/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e \
    /System/Library/PrivateFrameworks/NotesEditor.framework/Versions/A/NotesEditor
```

| File | Source path (in-cache image path) |
| --- | --- |
| `Coherence` | `/System/Library/PrivateFrameworks/Coherence.framework/Versions/A/Coherence` |
| `NotesEditor` | `/System/Library/PrivateFrameworks/NotesEditor.framework/Versions/A/NotesEditor` |
| `NotesHTML` | `/System/Library/PrivateFrameworks/NotesHTML.framework/Versions/A/NotesHTML` |
| `NotesShared` | `/System/Library/PrivateFrameworks/NotesShared.framework/Versions/A/NotesShared` |
| `NotesSupport` | `/System/Library/PrivateFrameworks/NotesSupport.framework/Versions/A/NotesSupport` |
| `PencilKit` | `/System/Library/Frameworks/PencilKit.framework/Versions/A/PencilKit` |
