# Optional private Concepts sample

`drawing-2.concepts` is an unmodified Android/Windows `.concepts` archive
(one layer, 58 strokes, no images). It belongs in git-annex, not ordinary Git;
tests that need it skip when it is absent or not yet fetched.

Source annex key (byte-identical copy):

```
SHA256E-s39660--afb409e0c1663db181b8a6d22e2962a9ff3d247565482590a12c768713f41520.zip
```

Tests find it at `<fixtureRoot>/concepts/drawing-2.concepts`, where
`fixtureRoot` defaults to `src/test/fixtures` and can be overridden with
`-Porg.rapidcow.archive.bridge.fixtureRoot=/path/to/fixtures`.
