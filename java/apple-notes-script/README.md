# Apple Notes Viewer

Run from the Java bridge directory:

```sh
./gradlew :apple-notes-script:run --args='/path/to/NoteStore.sqlite'
```

Without an argument, use File > Open (Cmd/Ctrl+O). The store must be a
standalone database with its WAL already consolidated.

The left tree groups rows by their Core Data entity, with titles where
available and primary keys in brackets. Select a row to inspect its non-null
raw columns; blobs are shown as byte counts. This is a store inspector:
note bodies and the Notes folder hierarchy are not decoded.

View > Sort By preserves selection and expanded groups. Dark Mode, Deselect
(Cmd/Ctrl+Shift+A), and View in File System follow the Joplin viewer's interface.
View in File System reveals the database, since rows have no individual files.

An optional integration test opens a real store read-only:

```sh
./gradlew :apple-notes-script:test -PnotesViewerFixture=/path/to/NoteStore.sqlite
```

Double-click an `ICAttachment` row with `ZTYPEUTI = com.apple.drawing.2`
and nonempty `ZMERGEABLEDATA1` to play its drawing in a separate window.
Strokes appear in sort-ID order, one every 50 ms. Space pauses/resumes;
after completion, Space replays. Closing the preview stops its timer.
The preview uses the existing Trivnote geometry rendering (fixed-width
colored paths and stroke transforms), not PencilKit's full brush/eraser rendering.

The bundled `apple-notes-drawing-decode.pl` reads the selected database row
with `--immutable --sexp --sort`; its stdout is streamed directly into
`libexpr` 0.0.0's `DataLoader`. No protobuf is ported to Java and no intermediate
S-expression file is written. The build obtains `libexpr` from the Rapidcow
HTTPS artifact repository.

Runtime requires `perl` on PATH and the decoder's Perl modules:
`DBI`, `DBD::SQLite`, `Google::ProtocolBuffers`, `URI`, `Math::BigRat`, and
`Data::Float`. Missing dependencies and unsupported attachments are reported
in the viewer. The optional archive test above also exercises this pipeline.
