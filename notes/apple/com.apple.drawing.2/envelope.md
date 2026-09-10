# `com.apple.drawing.2` blob envelope

Personal-data-recovery notes on the wire format of the drawing blob stored in
`ZICCLOUDSYNCINGOBJECT.ZMERGEABLEDATA1` for attachments with
`ZTYPEUTI = 'com.apple.drawing.2'` in a pre-Coherence Apple Notes
`NoteStore.sqlite`. Apple OSes no longer produce this UTI (superseded by the
newer "Coherence" drawing format), so this is purely about reading old data,
not interoperating with a live system.

See `tools/apple-notes-drawing.proto` and
`tools/apple-notes-drawing-decode.pl` for the decoder that consumes this.

## What's confirmed here

The blob is: an optional envelope, followed by a `proto2`-encoded `Drawing`
message (schema in the `.proto` file, itself inferred separately via
`protoc --decode_raw` against real sample blobs).

Envelope layout, all integers little-endian:

| offset | size | meaning |
|---|---|---|
| 0 | 4 | magic constant `0xF0647277` |
| 4 | 1 | format version (`uint8`); rejected if `>= 6` |
| 5 | 1 | not read by the function examined; unconfirmed |
| 6 | 2 | header length (`uint16`); must satisfy `8 <= n < blob.length` |

The protobuf `Drawing` message starts at byte offset *header length*, not
unconditionally at byte 8 - every sample seen so far just happens to carry
`header length == 8`. If the magic constant at offset 0 doesn't match, the
parser instead treats the *entire* blob, starting at offset 0, as a raw
envelope-less `Drawing` message - an apparently even-older fallback format
with no envelope at all.

Not yet examined: the variable-stride "extra" bytes following the X/Y
float32 pair in each point record of `Geometry.points` (believed to hold
force/azimuth/altitude/time channels). The function that reads those,
`-[_PKStrokeConcrete(PKStrokePersistenceAdditions)
readPointFromLegacyArchive:deltaFrom:]`, was located but not yet
disassembled.

## Methodology

Goal: get definitive evidence for the envelope's structure by reading the
machine code that actually parses it, rather than continuing to guess from
sample blobs alone.

1. **Identify the source binary.** The drawing UTI's obvious owner is
   PencilKit. Since macOS Big Sur, system framework binaries no longer exist
   as standalone files on disk - `otool -L` against `/System/Applications/
   Notes.app/Contents/MacOS/Notes` was used first to confirm which
   frameworks it links against (PencilKit, PaperKit, NotesEditor,
   NotesShared, etc.), and listing each framework's bundle directory on disk
   confirmed the Mach-O binary itself is absent - only `Resources` and
   `_CodeSignature` remain. The actual code only exists inside Apple's dyld
   shared cache (`/System/Cryptexes/OS/System/Library/dyld/
   dyld_shared_cache_arm64e`, macOS 26.6.2, build 25G83, Apple Silicon
   T8103).

2. **Extract single dylibs from the shared cache.** Installed
   [`ipsw`](https://github.com/blacktop/ipsw) (`brew install
   blacktop/tap/ipsw`), a shared-cache-aware reverse engineering CLI, and
   ran `ipsw dyld extract --objc -o . <cache-path> <framework-path>` for
   PencilKit, PaperKit, NotesEditor, NotesShared, NotesSupport, and
   NotesHTML, producing standalone Mach-O files with ObjC metadata restored
   and slide relocations applied.

3. **Find candidate symbols.** Ran `nm -U` against each extracted binary,
   grepping the demangled Objective-C selector names for keywords like
   `legacy`, `archive`, `mergeable`, `drawing`. This surfaced a family of
   selectors on `PKDrawingConcrete` and `_PKStrokeConcrete` whose names
   describe exactly this task: `initWithLegacyArchive:`,
   `initWithData:loadNonInkingStrokes:error:`,
   `_initWithUnzippedData:loadNonInkingStrokes:error:`,
   `readPointFromLegacyArchive:deltaFrom:`.

4. **Disassemble and read.** Used `ipsw macho disass <binary> -s
   <selector> --demangle --force` to get ARM64 disassembly of each candidate
   function, then traced it by hand:
   - `initWithData:loadNonInkingStrokes:error:` turned out to only handle a
     gzip-magic check (`1f 8b`) and inflate-if-gzipped, deferring to
     `_initWithUnzippedData:loadNonInkingStrokes:error:` for the real work.
   - That function is where the envelope fields above were read: it issues
     `NSData` `getBytes:range:` calls at fixed offsets/lengths (4, 1, 2
     bytes) into small stack buffers, then does integer comparisons against
     an immediate constant (the magic) and small bounds (the version and
     header-length checks), branching to calls to two distinctly-named
     error-constructing functions on failure - one of which is
     `PKProtobufUtilitiesFormatTooNew`, the other
     `PKProtobufUtilitiesMalformedError` (multiple call sites, one per
     failed precondition). On success it computes a pointer/length pair
     offset by the header length and hands it to a virtual call that parses
     the protobuf `Drawing` message from it.
   - `initWithLegacyArchive:` (the next stage, consuming the *parsed*
     protobuf fields rather than raw bytes) was also read; it's how the
     `.proto`'s `Drawing`/`Stroke`/`BBox` fields were cross-checked against
     what the framework itself does with them (e.g. confirming the 4 floats
     read for canvas bounds are consumed as an x/y/width/height-shaped
     struct). Incidentally, this function also contains a *separate*,
     already-parsed-protobuf version check (comparing an in-message version
     field against a expected constant) whose failure path logs a
     printf-style diagnostic string - not the same check as the envelope's
     byte-4 version gate above, and not reproduced here since it's a literal
     string constant rather than an identifier.

No debugger/live-process attach was used (SIP is enabled on the source Mac,
which would block attaching to Apple-signed processes anyway) - purely
static disassembly of extracted binaries.

## Provenance

- Source Mac: macOS 26.6.2 (build 25G83), Apple Silicon (T8103), examined
  2026-09-11.
- Tooling: `otool` (Xcode Command Line Tools), `ipsw` v3.1.714
  (blacktop/ipsw), `nm`.
- Binary: `PencilKit.framework` (public framework), extracted from
  `dyld_shared_cache_arm64e`.
- Method/class identifiers referenced above are exact, verbatim symbol names
  from that binary's symbol table. No disassembly, raw bytes, or extracted
  binary content is reproduced here or committed to this repo.
