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
note bodies, attachments, and the Notes folder hierarchy are not decoded.

View > Sort By preserves selection and expanded groups. Dark Mode, Deselect
(Cmd/Ctrl+Shift+A), and View in File System follow the Joplin viewer's interface.
View in File System reveals the database, since rows have no individual files.

An optional integration test opens a real store read-only:

```sh
./gradlew :apple-notes-script:test -PnotesViewerFixture=/path/to/NoteStore.sqlite
```
