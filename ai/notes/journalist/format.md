# Journalist archive structure

These notes record structural observations only. Personal archive contents,
filenames, titles, identifiers, text, and images are deliberately omitted.
The observations are not a complete application schema.

`.journalist` files are ZIP archives. The root `journal.xml` is XML with namespace
`http://www.charette.com/journalist/1.0` and a `Journal` root element. Root attributes
include `Version`, `AppVersion`, `Id`, `Title`, `Author`, `DateCreated`, and
`ImageEntryName`; child elements include `DateUpdated`, `FileToken`, `Pages`, and
`PrototypePage`.

Actual pages occur at `Journal/Pages/JournalPage`, in document order. Page
attributes include `Id`, `DateCreated`, `DateUpdated`, `Width`, `Height`, and
`XOffset`. Page children include `Background`, `CanvasType`, `Layout`, `Palette`,
`ThumbnailImage`, and `Items`. `PrototypePage` and the page's `Typical*` elements
are templates; a descendant-wide search would mistakenly include their data.

Actual items are direct element children of a page's `Items`. Observed types are
`Sketch`, `Text`, and `ShapePageItem`. Common attributes include `Guid`, `Layer`,
`Opacity`, and `IsEffectComposite`. Common child elements include position,
dimensions, rotation, scale, translation, transform, lock state, timestamp,
nullable latitude/longitude, and `Effects`. Preserve XML order and explicit
layer information without assuming how the application combines them.

Text content lives in the `Text` child of a `Text` item, alongside font and
foreground/background information. Shapes carry geometry and stroke/fill data.
Brush colors can be represented as separate A/R/G/B children. Effects have
nested operations such as `DrawItem`, `DrawShadow`, and `DrawDirectionalBlur`.
The generic DOM keeps these and unknown metadata rather than silently dropping
fields not yet interpreted.

A `Sketch` can contain an `Ink` attribute naming a ZIP entry and an `InkBase64`
child containing encoded ink. In the reference files examined, both representations
are present and decode to the same bytes. The bytes begin with `GIF89a`: this is
the Windows Ink fortified GIF envelope, not bare ISF. See the separate Windows
Ink notes for envelope and stroke decoding. The initial reader gives the entry
reference precedence, uses base64 only when no reference is present, and treats
a missing referenced entry as an error. It preserves raw ink as well as offering
the Windows Ink decoder integration.

`JournalArchive.open(Path)` opens the ZIP read-only. `pages()` and each page's
`items()` expose ordered navigation; `xml()` and item/page `xml()` retain full
metadata. `inkBytes(item)` reads the original payload, `decodeInk(item)` decodes
it, and `readAttachment(name)` reads any other exact entry name. Use
try-with-resources. The DOM is mutable caller metadata, and page/item lists are
snapshots from open time. This is a reader, not an exporter or rendering engine.

Default limits are 32 MiB decompressed XML, 64 MiB per attachment, and 10,000 ZIP
entries; limits are configurable. Reads never extract archive paths to disk.
Duplicate ZIP names are rejected. XML DTDs, external entities, and external
schemas are disabled, and XML nesting is limited to 256 levels. XML parse errors
do not echo private content. The byte limits bound each read, not cumulative
memory retained by callers or every allocation made by the DOM parser.

Reader tests construct synthetic ZIP/XML documents in temporary directories.
An optional integration test wraps the small private annex-managed ink fixture
in synthetic XML and exercises both reference and base64 decoding. It skips when
the fixture is missing, including a dangling annex symlink. No complete personal
archive or personal XML is used as a committed test fixture.
