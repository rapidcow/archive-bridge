# Concepts file format — research notes

Scope: figure out enough of the `.concept` (iOS/macOS) and `.concepts`
(Android/Windows) binary formats to hand-roll our own Perl decoders,
independent of any existing library. This file is notes only — no code yet.

Sources consulted (all cloned locally):

- `~/net/github.com/romangarms/Concepts-File-Viewer` — TS/React viewer,
  cleanest and most current parser code (`src/parsers/plistParser.ts`,
  `src/parsers/messagePackParser.ts`, `src/parsers/shared/transformUtils.ts`).
  Also ships **pre-converted JSON dumps** of a real file's plists under
  `example files/Converted-PLISTS/*.json` — this was the single most useful
  thing found, since it lets you `grep`/`python -m json.tool` a real object
  graph without writing any parsing code first.
- `~/net/github.com/maxliebscher/ConceptsAppiOSFileViewer` — older vanilla-JS
  viewer + a standalone Python converter script
  (`converter/conceptsapp_ios_to_json_v1_4_stable.py`). Useful as a second,
  independently-written implementation to cross-check field offsets against.
- `~/net/github.com/modulovalue/concepts_reader` — Flutter/Dart viewer with
  its own from-scratch `bplist00` parser
  (`lib/parsers/binary_plist_parser.dart`) — this is a complete, readable
  spec of Apple's binary plist format with no dependencies, and is the best
  reference for our own bplist decoder.
- `~/my/art/export/{iPad,p9p}` — **our own real files**, both formats:
  - `iPad/**/*.concept` — genuine iOS exports (some via git-annex there too)
  - `p9p/Concepts{,-OLDER}/*.concepts` — genuine desktop (Android/Windows
    codebase, "p9p" build) exports
  - Confirmed by unzipping: `Strokes.plist` etc. really are `bplist00`
    (`file` reports "Apple binary property list"); `.concepts` really
    contains `tree.pack` starting with msgpack fixarray byte `0x92`.

All three viewers agree closely on the low-level byte layouts below, which
gives fairly high confidence in the parts marked "confirmed". Parts no
existing viewer actually decodes are marked "open question" — that's
where our own reverse-engineering adds value.

---

## 1. Container formats

Both `.concept` and `.concepts` are **zip archives** (regular zip, not
proprietary) — `unzip`/`Archive::Zip`/`IO::Uncompress::Unzip` all read them
fine directly. So the only formats we actually need to hand-roll are the
two *payload* formats inside: Apple's binary plist (NSKeyedArchiver) and
MessagePack. Perl's `Compress::Raw::Zlib` / `IO::Uncompress::Unzip` are core
and available in this environment — no need to reinvent DEFLATE or the zip
central-directory format too.

### 1a. `.concept` (iOS / macOS / iPadOS)

```
Drawing.concept  (zip)
├── Strokes.plist        — bplist00, NSKeyedArchiver-ish; strokes/images/text
├── Drawing.plist        — bplist00, archiver "THFormatObjCEncoder"; doc-level
│                          transform, canvas/grid/paper settings, view state
├── Resources.plist      — bplist00; maps imported-image UUID → file extension
├── metadata.plist       — bplist00, archiver "NSKeyedArchiver"; creationDate,
│                          majorMinorVersion, bundleIdentifier, placeholderColor
├── Thumb.jpg            — optional, capital T
└── ImportedImages/      — raw image/PDF files, named by UUID (+ext)
    └── <uuid>.<ext>
```

Note the **two different `$archiver` values** seen across plists in the same
file (`NSKeyedArchiver` for metadata.plist, `THFormatObjCEncoder` for
Drawing.plist) — both use the identical bplist00 + `$objects`/`$top`/`$class`
keyed-archive shape though, so one decoder handles both; the archiver name
is basically just metadata, not a format switch.

### 1b. `.concepts` (Android / Windows — the "p9p" codebase)

```
Drawing.concepts  (zip)
├── tree.pack        — MessagePack; the actual drawing tree (layers/strokes)
├── resource.pack     — MessagePack; image resource table (often tiny/empty)
├── workspace.pack    — MessagePack wrapping a **UTF-8 JSON string** (!) —
│                       saw `0x9e 0x0f da 01 d5 7b 22 ..` = fixarray, then a
│                       str-16 (`0xda`) whose payload is literally
│                       `{"color_palette":"Copic","layers":{...}}` JSON text.
│                       So this one file mixes both encodings.
├── metadata.json     — plain JSON (not msgpack, not plist!):
│                       {backgroundSolidColor, creationTime, formatVersion,
│                        identifier, modificationTime}
├── thumb.jpg         — optional, lowercase t (vs. capital `Thumb.jpg` on iOS)
└── (image resources, referenced from resource.pack)
```

`formatVersion` seen in a real file: `20250319` (looks like a YYYYMMDD build
stamp, not a semantic version — don't assume small integers).

---

## 2. Apple binary plist (`bplist00`) — confirmed, full spec

This is Apple's public format (used system-wide, not Concepts-specific), and
`modulovalue`'s `binary_plist_parser.dart` is a clean, complete,
dependency-free implementation of it we can port almost line-for-line to
Perl. Layout:

```
[8 bytes]  magic "bplist00"
[...]      object table (variable-length records, byte offsets given by
           the offset table below)
[N * offsetSize bytes]  offset table: N = numObjects, each entry is a
                         big-endian uint of `offsetSize` bytes, giving the
                         byte offset of object[i] in the file
[32 bytes] trailer, at end of file:
    bytes 0-5   unused
    byte  6     offsetSize      (bytes per offset-table entry)
    byte  7     objectRefSize   (bytes per object-reference, used inside
                                 arrays/dicts/sets)
    bytes 8-15  numObjects      (big-endian uint64)
    bytes 16-23 topObject       (index of the root object)
    bytes 24-31 offsetTableOffset (byte offset where the offset table starts)
```

To parse: read trailer → read offset table → look up `topObject`'s offset →
recursively parse objects. Every object starts with a 1-byte marker:
high nibble = type, low nibble = "objectInfo" (usually an inline small
length, or `0xF` meaning "read a following int-object for the real length").

| type nibble | meaning | payload |
|---|---|---|
| `0x0` | null/bool/fill | objectInfo `0x0`=null, `0x8`=false, `0x9`=true, `0xF`=fill |
| `0x1` | int | `2^objectInfo` bytes, big-endian, two's-complement if ≥8 bytes |
| `0x2` | real | 4 bytes → float32 BE, or 8 bytes → float64 BE |
| `0x3` | date | 8-byte float64 BE = seconds since 2001-01-01T00:00:00Z |
| `0x4` | data (raw bytes) | length = objectInfo, or extended (see below) |
| `0x5` | ASCII string | length = objectInfo or extended; 1 byte/char |
| `0x6` | UTF-16BE string | length = objectInfo or extended; 2 bytes/char |
| `0x8` | UID (archiver ref) | `objectInfo + 1` bytes, big-endian uint |
| `0xA` | array | length = objectInfo or extended; then that many `objectRefSize`-byte object-index refs |
| `0xC` | set | identical layout to array |
| `0xD` | dict | length = objectInfo or extended; then N key-refs **followed by** N value-refs (keys block, then values block — not interleaved) |

**Extended length** (`objectInfo == 0xF`): immediately following the marker
byte is *another* object marker that must be an int-type (`0x1_`), whose
decoded integer value is the real length; the data starts right after that
int object.

**UID vs plain int**: the `0x8` type is exactly how `$objects`/`$top`/`$class`
references are encoded — this is what makes it a "keyed archive" rather than
a plain plist. Every `{"UID": N}` you see in the JSON dumps is really one of
these — dereference by indexing into `$objects[N]`.

Practical note: `bplist-parser` (npm) apparently returns UID objects as
`{UID: n}`; the plist.ts types in romangarms's code also account for
`{data: n}` shape from some other/older parser version. When we write our
own, we control the representation, so this is a non-issue — just document
it so future-us isn't confused when comparing against JS debug output.

---

## 3. NSKeyedArchiver object graph shape (confirmed against real files)

Once bplist is decoded you get: `{$version, $archiver, $top: {root: UID},
$objects: [...]}`. `$objects[0]` is always the string `"$null"`.

Walking a real `Strokes.plist` (via the JSON dump), the shape is:

```
root  (class StrokeManager : DrawingItemGroup : DrawingItemLayer : DrawingItem)
  .rootGroupLayers → NSMutableArray, NS.objects = [ <layer>, <layer>, ... ]

each <layer>  (class DrawingItemGroup)
  .name        → e.g. "Pen", "Marker", "Image", "Text"  (a real user/
                 auto-assigned layer name — existing viewers throw this away
                 when flattening to one big stroke list; worth keeping!)
  .diIdentifier, .diTimeStamp, .groupTypeObject
  .groupItems  → NSMutableArray/Set, NS.objects = [ <item>, <item>, ... ]

each <item> is one of (distinguished by $class.$classname):
  - Stroke              → see §4
  - ImageItem           → see §5
  - TiledPDFPage        → (not in our sample, but referenced by all 3 viewers;
                           same shape as ImageItem plus `page`/`resourceIdentifier`)
  - TextItem            → has `.text` (UID→string), `.properties`
                           (brushColor/brushAlpha), `.diSavedTransform`
  - DrawingItemGroup    → nested sub-groups are possible (didn't confirm
                           depth > 1 in our sample, but the recursive
                           "$classes" ancestry chain implies it's supported —
                           treat groupItems as recursively walkable, not a
                           fixed 2-level tree)
  - MeasurementLayerItem → seen in class list, not decoded by anyone; open
                           question, probably ruler/measurement-tool overlay
```

Every `$classname` seen in our one real sample file's `$objects`:
`BrushProperties`, `DrawingItemGroup`, `ImageItem`, `ImageProperties`,
`KeyPoint`, `MeasurementLayerItem`, `NSDate`, `NSMutableArray`,
`NSMutableSet`, `Stroke`, `StrokeManager`, `TextItem`, `TextProperties`,
`UIColor`. No `EllipseItem`/`RectItem`/shape-tool classes and no explicit
eraser class showed up in this sample — the Python/Dart converters both
*guess* at fields like `isEraser`, `mirrorX`/`mirrorY`, `blendMode` that we
never actually observed in a real object. **Open question**: get a sample
file that uses the eraser and an auto-shape tool, and check what class/keys
actually show up, rather than trusting the guessed field names.

---

## 4. `Stroke` objects — confirmed fields + one open question

```
Stroke:
  $class → Stroke : DrawingItem : NSObject
  diIdentifier, diTimeStamp
  brushProperties → BrushProperties (see below)
  diSavedTransform  → 64-byte buffer, optional (only present if moved/rotated)
  diInitialSavedTransform → 64-byte buffer (seen on ImageItem; may also
                            appear on Stroke — not fully confirmed)
  closed            → bool, present at least on some items (shape/polygon
                      closure — exact semantics not pinned down since our
                      sample doesn't have a closed stroke to inspect)
  strokePointsNonOptionalAngles → UID → raw byte buffer, THE point data
    (older/alternate field name seen in code: strokePoints45 — try both)
  keyPoints         → UID → NSMutableArray of KeyPoint objects, each with
                      a `glPosition` field (8-byte buffer = 2×float32 x,y).
                      This looks like a decimated/simplified control-point
                      version of the same stroke (used as a fallback by all
                      3 viewers when the main point buffer is absent/short).
  containsPressure, altitudeAngleAvailable, azimuthAngleAvailable → bool
                      flags describing what's packed into each point record
                      (see §4a — these flags are the reason we suspect the
                      per-point buffer carries more than x/y, even though no
                      existing viewer bothers to decode it)
  initialAltitudeAngle → float, single value for the whole stroke
  smoothing, smoothingAlgorithm, useSymmetricSmoothing,
  useEndpointMidpointSmoothingZeroSmoothness/NonZeroSmoothness,
  startTaper, endTaper, scale, version → stroke-shaping metadata, not
                      needed for a basic renderer but easy wins if we want
                      closer-to-original rendering later
```

`BrushProperties`:
```
brushToolNew   → int, tool-type id (16 in our pen sample; 10 showed up as
                 the tool id embedded in an ImageItem's imageProperties —
                 so this is a shared tool-id enum across item types, not
                 stroke-only. No id→name table found anywhere; open question.)
brushWidth     → float, in canvas units
brushSmoothness→ float
brushAlpha     → float 0-1 (separate from the color's own alpha!)
brushColor     → UID → UIColor:
    UIRed, UIGreen, UIBlue, UIAlpha → floats 0-1 (this is what every viewer
        actually reads)
    NSRGB     → a `Data` blob whose bytes are literally the **ASCII text**
                of a decimal string like "0.957 0.369 0.345" — legacy/
                redundant encoding of the same RGB, safe to ignore
    NSColorSpace → int (seen value: 2)
```

### 4a. Per-point buffer encoding — confirmed x/y, open question on the rest

`strokePointsNonOptionalAngles` decodes (in every viewer, and confirmed
against the raw bytes ourselves) as **16 bytes per point**:

```
offset 0:  float32 LE   x
offset 4:  float32 LE   y
offset 8:  8 bytes       — NOT decoded by any of the 3 existing viewers.
                           JS/Dart both just say "padding/angle data" in a
                           comment and drop it; Python's struct format
                           `<ffII` reads it as two uint32s and discards them.
```

We poked at those trailing 8 bytes ourselves on a real stroke (28 points,
`containsPressure=true`, `altitudeAngleAvailable=true`,
`azimuthAngleAvailable=true`) by treating them as 4× `uint16` LE:

```
byte  8- 9: varies 222..2605           → plausible raw pressure sample
byte 10-11: varies ~32000..49600       → plausible altitude angle (raw,
                                          pre-scaling)
byte 12-13: CONSTANT 0xAAA9 across every point in the stroke  → unclear;
                                          maybe a reserved/sentinel field,
                                          maybe format-version dependent
byte 14-15: varies ~63200..64300, and goes flat for the last several
            points → plausible azimuth angle, flattening near pen-lift
            (consistent with iOS sometimes freezing azimuth when pressure
            drops toward 0)
```

This is a **hypothesis, not a confirmed decode** — half-float (`float16`)
interpretations of the same bytes gave nonsense, so raw `uint16` samples
(needing an unknown scale factor to turn into real pressure 0..1 / angle
radians) is the current best guess. Before trusting this in a decoder we
should: (a) find/produce a file with known extreme pressure (very light vs.
very hard) and see which field tracks it monotonically, (b) same for a
stroke drawn at a known tilt angle, (c) check whether the constant
`0xAAA9` changes at all across *different* strokes/files (it might encode
`brushToolNew` or a coordinate-space flag rather than being truly
"reserved"). Low priority since x/y alone is enough to render — but if we
want pressure-sensitive line width (nicer output than the existing
viewers!), this is the thing to crack.

`keyPoints` fallback buffer (`glPosition`) is simpler and fully confirmed:
8 bytes = 2×float32 LE = (x, y), no pressure/angle at all.

---

## 5. Transform buffers — confirmed layout, watch the row/col-major framing

Every `diSavedTransform` / `transform` field is a **64-byte buffer = a 4×4
float32 matrix**, always with the same 2D-affine sub-structure. All three
viewers agree on which 6 of the 16 floats matter, but describe the
row/column-major framing differently — don't let that confuze you, the byte
offsets agree:

```
float32 index (LE):  [0]=a  [1]=b  ...  [4]=c  [5]=d  ...  [12]=tx [13]=ty ...
byte offset:           0     4            16    20            48     52
```

i.e. it's really a CGAffineTransform `(a, b, c, d, tx, ty)` padded out to a
4×4 matrix (with 1s on the remaining diagonal, 0s elsewhere) so it round-trips
through whatever generic 3D matrix type the app uses internally. Standard
2D affine semantics: `x' = a*x + c*y + tx`, `y' = b*x + d*y + ty`. An
identity-ish transform (a≈1,b≈0,c≈0,d≈1,tx≈0,ty≈0, within float epsilon) is
typically treated as "no transform" by romangarms's code — worth doing the
same so we don't clutter output with no-op transforms.

Concepts uses a **bottom-left-origin coordinate system** (confirmed by
romangarms's CLAUDE.md and consistent with the y-values we saw). Any
renderer needs a global Y-flip; a bare decoder just needs to know this so it
doesn't "fix" it by accident.

---

## 6. Images / PDFs

`ImageItem`:
```
imageIdentifier → UID → string UUID; matches a file under ImportedImages/
                  (iOS) by filename stem, extension looked up via
                  Resources.plist's importedResourceMap (legacy-id → ext)
size            → UID → string like "{397.5, 393}" (NOT a binary struct —
                  parse with a regex: `\{([-\d.]+),\s*([-\d.]+)\}`)
crop            → string like "{{0, 0}, {1, 1}}" (a CGRect-as-string,
                  normalized 0..1 crop box) — nobody currently applies this
diSavedTransform, diInitialSavedTransform → 64-byte transform buffers;
                  position = (tx, ty) of diSavedTransform
imageProperties → { brushAlpha, brushToolNew } (opacity essentially)
```
PDF pages (`TiledPDFPage`, per all 3 viewers' code even though absent from
our one sample) look the same but with `resourceIdentifier` instead of
`imageIdentifier`, plus a 1-indexed `page` field (subtract 1 for normal
PDF-page-array indexing), and a `pdfProperties` blob with `brushAlpha`
instead of `imageProperties`.

`Resources.plist` maps `imageIdentifier` → real file extension:
`root.importedResourceMap` is an NS.keys/NS.objects pair (dict-shaped, but
stored the "keyed archiver dictionary" way — keys array + values array in
parallel, not literally a `0xD` bplist dict) → each value has
`resourceLegacyId` + `resourceExtension`.

---

## 7. Android/Windows `.concepts` — MessagePack payload (`tree.pack`)

MessagePack itself is a public spec (unlike bplist it's not Apple-specific,
but we're still hand-rolling the decoder rather than pulling in
`Data::MessagePack`, since that's not installed and the point is to not
depend on it). Need at minimum: fixarray/array16/array32, fixmap/map16/32,
fixstr/str8/16/32, fixint/uint8/16/32/64, int8/16/32/64, float32/64, bin8/16/32,
**ext types** (msgpack "extension" = 1 type-byte + N data bytes; Concepts
uses these to smuggle in fixed-size binary blobs — color/transform — with a
custom marker byte rather than plain `bin`).

Observed root shape: `tree.pack` = msgpack array of 2 elements:
`[formatVersion, rootData]`. `rootData[4]` = array of **layers**, each layer
= `[layerType, layerInfo, items]` (items at index 2). Each item in a layer:

```
item = [4, [0, strokeData]]      -- outer wrapper, "4" = item-kind marker?
strokeData[0]  == 6              -- "6" = stroke-kind marker (only kind
                                    anyone has decoded so far; other markers
                                    almost certainly exist for images/text/
                                    shapes on this platform too — open
                                    question, we don't have a sample)
strokeData[1]  = brushInfo (deeply nested array; color/transform buried at
                 brushInfo[1][1][1] — see below)
strokeData[3]  = displayWidth (float, the actually-rendered stroke width)
strokeData[5]  = closed (bool)
strokeData[17] = pointsBuffer (msgpack bin, raw bytes — see below)
```

The magic-number indices (`4`, `6`, position `17` for points, etc.) were
found by trial-and-error/fuzzing in romangarms's commit history, not derived
from any spec — treat every numeric index here as "true for the one sample
file someone tested against", not as guaranteed stable across app versions.
**Open question**: get 2-3 more `.concepts` samples from different app
versions/tool types and check the indices still hold; also `rootData[4]`
being "the layers array" specifically (vs. some other rootData slot) is
exactly the kind of thing that could shift between `formatVersion`s — we
already know formatVersion `20250319` behaves this way, but our own sample
files may be older/newer.

Color buffer: extension type `4`, 16 bytes = 4×float32 LE = **RGBA** (order
differs from iOS's `UIColor` which stores R/G/B/A as separate named dict
keys rather than a packed buffer — same 4 values, different container
shape). There's also a separate scalar "alpha multiplier" sibling field
that gets multiplied into the buffer's alpha — so don't just trust the
4th float in isolation.

Transform buffer: extension type `7`, 64 bytes — **same 4×4-float32-with-
2D-affine-in-the-corner layout as iOS** (§5), same byte offsets for a/b/c/d/
tx/ty. Good: one transform decoder serves both platforms.

Point buffer: also 16 bytes/point, but a **different layout than iOS**:
```
offset  0: float32 LE  x
offset  4: float32 LE  y
offset  8: uint16 LE   pressure_raw   (÷65535 per romangarms's code →
                                        normalized 0..1 pressure; this one
                                        IS decoded/confirmed, unlike iOS's
                                        mystery bytes above)
offset 10: (6 bytes unused/undecoded — presumably angle/tilt data, same
            "nobody's decoded it yet" situation as iOS bytes 8-15, but
            we have even less signal here since no one has poked at the
            raw bytes on this platform the way we did for iOS in §4a)
```

`resource.pack` — same msgpack ext-type scheme presumably, for image
resources; our real sample's `resource.pack` was only 3 bytes
(`92 00 80` = `[0, {}]`) i.e. empty — need a file with actual embedded
images to reverse-engineer this one.

`workspace.pack` — msgpack wrapper around a **JSON string** (`layers`,
`color_palette`, etc. — this is where Android/Windows layer *names* and
*visibility* probably live, analogous to iOS's `DrawingItemGroup.name`).
Worth fully dumping one of these since — unlike everything else in this
document — it's just JSON once you peel one msgpack string wrapper off, no
bit-twiddling required.

---

## 8. Practical differences to remember when writing one decoder vs two

- Thumbnail filename case differs: `Thumb.jpg` (iOS, capital T) vs.
  `thumb.jpg` (Android/Windows, lowercase) — case-sensitive on most
  filesystems, don't assume one spelling.
- iOS metadata lives in a bplist (`metadata.plist`); Android/Windows
  metadata is plain JSON (`metadata.json`) — no binary parsing needed there
  at all, nice.
- Color as 4 named floats (iOS `UIColor` dict) vs. color as a 16-byte packed
  buffer (Android ext type 4) — same semantic value, different container;
  our internal `Color{r,g,b,a}` struct is fine as the unification point,
  exactly like romangarms's `types/drawing.ts` already does in TS.
  Same story for points/transforms — one internal `Stroke`/`Point`/
  `Transform` shape, two format-specific decoders that both populate it.
- Pressure is confirmed-decodable on Android (§7) but only
  hypothesized/undecoded on iOS (§4a) — asymmetric confidence, don't assume
  parity between platforms just because the byte-count-per-point matches.

---

## 9. Suggested Perl approach (still just notes, not committing to this yet)

- Use `IO::Uncompress::Unzip` (core-ish, confirmed installed) for the zip
  container — no reason to hand-roll DEFLATE.
  `Data::MessagePack` and `Mac::PropertyList` are **not installed** here
  (confirmed by `perl -M...` failing) — consistent with wanting to hand-roll
  those two ourselves rather than reach for CPAN.
- bplist00 decoder: direct, fairly mechanical port of the table in §2 —
  read trailer → offset table → recursive `parse_object($index)` with a
  memoization cache (objects can be referenced from multiple places via
  UID, exactly like `modulovalue`'s `_objectCache`) to avoid re-parsing and
  to correctly reproduce shared-object identity if that ever matters.
- MessagePack decoder: only need the subset actually used (see §7's type
  list) — a full spec-complete implementation is more than we need, but
  ext-type support is *not* optional since Concepts leans on it for
  color/transform blobs.
- Keep the "make sense of $class UIDs → classname" step separate from the
  raw bplist decode, mirroring how the object graph in §3 is really two
  layers: (1) generic bplist/NSKeyedArchiver decode → nested
  Perl data structure with `UID` markers, (2) a Concepts-specific walker
  that knows about `StrokeManager`/`DrawingItemGroup`/`Stroke`/etc. and
  dereferences UIDs as it goes. That separation is what let 3 independent
  implementations agree on §2/§3 while disagreeing on details elsewhere —
  worth preserving for our own maintainability.
- Don't try to be clever about the open questions in §4a/§7 up front — get
  x/y-only stroke rendering working end-to-end first (that alone matches
  what every existing viewer does), *then* circle back to pressure/angle
  once we have a way to visually compare against the iPad app's own
  rendering.

---

## 10. Local real-file inventory for testing

```
~/my/art/export/iPad/Folder (actually real)/Drawing.concept
~/my/art/export/iPad/Folder (actually real)/Drawing Copy.concept
~/my/art/export/iPad/New Folder/New Drawing*.concept        (4 files)
~/my/art/export/iPad/Welcome to Concepts/*.concept           (4 files, one
                                                       is the sample decoded
                                                       above via the JSON dump)
~/my/art/export/p9p/Concepts/Drawing.concepts                (git-annex
                                                       symlink, resolves fine)
~/my/art/export/p9p/Concepts-OLDER/Drawing.concepts           (worth diffing
                                                       against the non-OLDER
                                                       one for format drift)
```
Plus the bundled samples in the three cloned repos (`example files/`,
`samples/`, `assets/*.concept`) if we want more variety without touching our
own art.

## 11. Open questions worth resolving before/while coding

1. What exactly is packed into iOS stroke-point bytes 8-15 (§4a)?
2. What are the real semantics of `brushToolNew` values (tool-id enum)?
   No id→name table found in any of the 3 repos.
3. Does `closed` mean shape-tool closure, or can freehand strokes be closed
   too? No closed-stroke sample inspected yet.
4. Eraser strokes / auto-shape (rect/ellipse/polygon) tool items: what
   `$classname`/fields do they actually use? All 3 viewers guess field names
   for this without a confirmed sample.
5. `.concepts` (Android/Win): are indices `4`/`6`/`17` (§7) stable across
   `formatVersion`s, or specific to the one version romangarms tested
   against? Do image/text/shape items use the same `[4,[0,[N,...]]]` outer
   wrapper with a different `N`, or a totally different shape?
6. `resource.pack` structure — need a sample with actual embedded images.
7. `workspace.pack`'s embedded JSON — full schema (layer names/visibility/
   order for the Android/Windows side, analogous to iOS's
   `DrawingItemGroup.name`).
8. Compare `p9p/Concepts` vs `p9p/Concepts-OLDER` byte-for-byte for any
   structural drift — free signal about format evolution we haven't used yet.

## 12. Cross-comparison with Apple Notes drawings (`com.apple.drawing.2`)

Source: `~/my/project/apple-notes/ai/tools/apple-notes-drawing-2.proto`, its
companion notes (`ai/notes/apple/com.apple.drawing.2/{drawing,
point-decode-pipeline,legacy_drawing}.md`), and the existing decoder
(`ai/tools/apple-notes-drawing-decode.pl`). That project reverse-engineered
Apple Notes' own PencilKit-based stroke format to a *much* higher confidence
level than we have for Concepts — live arm64e disassembly of
`PencilKit.framework`, not just cross-checking independently-written
guesses. Worth mining both for direct parity (same underlying Apple Pencil
hardware/APIs) and for methodology.

### Layers: Notes has none

Confirmed (grepped all three `.md` files for "layer" — zero hits). Apple
Notes' `Drawing` message is completely flat: `strokes[]`, `inks[]`,
`replica_uuids[]`, `version_vector[]`, no grouping/layer container at all in
the persisted format. The closest things to "layers" are two unrelated
per-stroke fields — `group_uuid` (field 16, the single weakest-confidence
field in the whole schema, elimination-only: arbitrary multi-stroke
*selection* grouping) and `render_group_uuid` (field 13: shape-recognition
grouping, which strokes got merged into one auto-recognized shape). Neither
is an ordered, named, nested structure the way Concepts' `DrawingItemGroup`
(§3) is — `drawing.md` even traced the real underlying ObjC concept
(`groupID`/`_setGroupID:`, used by `PKGroupQuery`/handwriting-transcription/
math-recognition) and found it looks more like an ephemeral/recomputed tag
than a persisted layer stack.

**Takeaway: "how are layers stored" doesn't transfer between these two
formats at all.** Concepts genuinely has a layers feature (named, ordered
`DrawingItemGroup`s under `rootGroupLayers`, §3); Apple Notes' drawing
format has no equivalent whatsoever. Don't expect to reuse any
layer-decoding logic between the two decoders — this part of our Concepts
work is unprecedented relative to the Notes project, not a port of it.

### Stroke-level structure: same concepts, different encoding choices

| concept | Concepts (`.concept`, iOS) | Apple Notes (`com.apple.drawing.2`) |
|---|---|---|
| stroke identity | `diIdentifier` (UID→string) + `diTimeStamp` (wall-clock) | `stroke_uuid` (16 raw bytes) + `StrokeID` logical clock (`sort_id`/`version`/`substrokes_version`, each `{clock, replica_index, subclock}`) — a real CRDT vector-clock, since Notes syncs multi-writer via CloudKit; no such machinery seen in our Concepts sample (Concepts files are standalone zips) |
| brush/tool | `brushProperties` UID → `{brushToolNew: int, brushWidth, brushColor: UIColor{r,g,b,a}, brushAlpha}`, **inlined per-stroke** | `ink_index: int`, an index into document-level `Drawing.inks[]` (deduplicated) → `Ink{color: RGBA, identifier: string e.g. "com.apple.ink.pen", weight: int}` |
| transform | `diSavedTransform`: 64-byte buffer, 4×4 float32 matrix, affine part at fixed byte offsets (§5) | `Transform` message: 6 *separately optional* protobuf float32 fields `a,b,c,d,tx,ty` — same `CGAffineTransform` semantics, field order confirmed via live disassembly, but plain protobuf rather than a padded 4×4 buffer |
| bounding box | not observed in our sample's `Stroke` key list (§4) — likely computed on demand by viewers, not persisted | `Bounds{origin_x, origin_y, width, height}`, persisted per-stroke (field 6, proven) |
| shape recognition | not confirmed at all yet (§11 Q4) | `shape_type` (small closed enum: 1,2,3,6,7,10,12) + `render_group_uuid`, both empirically well-characterized |

The **tool-identity** row is the most actionable parity point for our own
open question #2 (§11: "no id→name table found for `brushToolNew`"):
Apple's equivalent field is a **human-readable string**
(`"com.apple.ink.pen"`), not an opaque int. That doesn't hand us Concepts'
actual tool names, but it's a reason to keep looking for a string
identifier somewhere in the Concepts object graph (maybe on
`BrushProperties` itself, under a key we haven't printed yet, or in a
different plist) before assuming `brushToolNew`'s int enum is undocumented
forever.

### Per-point auxiliary channels — the most useful find for §4a

Apple Notes' point format (confirmed via **static disassembly**, not
guesswork) is: first 8 bytes of every point = unconditional float32 x,
float32 y (identical to every Concepts point format we found, iOS *and*
Android); remaining bytes = a **presence-bitmask-gated, tightly-packed (no
padding for absent channels), increasing-index-order** sequence of named
channels, where `point_flags` (a per-stroke int) has one bit per channel and
`stride = 8 + sum(size of each present channel)`.

That is *exactly* the structural pattern we guessed at for Concepts' iOS
stroke points in §4a — three boolean flags
(`containsPressure`/`altitudeAngleAvailable`/`azimuthAngleAvailable`)
sitting right next to the point buffer field, presumably gating which of
the trailing 8 bytes mean what — except Apple's version is now *proven*
rather than hypothesized. That's a real structural cross-check that we're
reading Concepts' analogous flags correctly in spirit, even without
disassembling Concepts itself.

Better still, Apple's confirmed formulas let us re-test our raw-`uint16`
hypothesis numerically:

```
force (pressure):  raw_uint16 / 1000.0                       — no bias
azimuth:           raw_uint16 * (2π/65535) − π                — range: −π..+π
altitude:          π/2 − raw_uint16 * (π/2/65535)              — range: 0 (flat) .. π/2 (upright)
```

Plugging these into the exact same real Concepts stroke hand-inspected in
§4a (28-point stroke, all three `*Available` flags true):

- bytes 10-11 ("altitude?"), raw=44341 for the first several points →
  `π/2 − 44341×(π/2/65535)` ≈ **0.508 rad ≈ 29°** — a physically plausible
  pencil-tilt angle for ordinary handwriting/sketching
- bytes 14-15 ("azimuth?"), raw=64275 for point 0 →
  `64275×(2π/65535) − π` ≈ **3.02 rad ≈ 173°** — close to Apple's own
  azimuth convention's "pointing left" direction (per the decode script's
  comment: 0 = +x/right, ±π = −x/left) — plausible for a right-handed grip

Both land in sane, physically-meaningful ranges using **Apple's exact
published constants** — much stronger evidence than the original "seems
plausible" hand-wave in §4a. **Confidence upgrade**: it's now a
well-motivated hypothesis (not proof — different vendor, could coincidentally
converge, or differ in scale/bias) that Concepts' iOS point format reuses
this same `uint16` fixed-point convention for pressure/altitude/azimuth,
most likely in that channel order (6 of the 8 mystery bytes).

That still leaves 2 bytes unexplained — the previously-flagged "CONSTANT
`0xAAA9` across the entire stroke" field (§4a bytes 12-13). Apple's channel
table has 10 possible channels total, only 3 of which we have a matching
Concepts boolean flag for; a channel that's legitimately **constant across
an entire stroke** — e.g. Apple's `aspectRatio`/`edgeWidth` (brush-tip-shape
properties that don't change mid-stroke) — is a much better-fitting
explanation now than "reserved/sentinel," given we have a real precedent
for "per-point channel that happens not to vary within one stroke." We
don't have a matching Concepts flag for this 4th channel (only 3
`*Available` booleans were found in our one sample's object dump) — worth
specifically grepping a Concepts `Stroke` object for anything like
`aspectRatioAvailable`/`edgeWidthAvailable`/`opacityAvailable` before
concluding it's truly unconditional the way channel 0 (location) is.

**Revised recommendation for §9/§11**: when decoding Concepts' iOS point
channels for real, try Apple's exact per-channel scale/bias table first
(same divisors — /1000 for force; /65535 scaled to 2π with −π bias for
azimuth; /65535 scaled to π/2 with π/2 bias for altitude) rather than
deriving our own from scratch, and validate the same way
`point-decode-pipeline.md` did: check `sum(channel sizes for the
Concepts-equivalent set flags) == stride − 8` exactly, across several
distinct flag combinations pulled from our own real sample files, before
trusting any field order.

### Perl implementation notes (methodology, not format)

`apple-notes-drawing-decode.pl` is a good template for our own decoder's
*shape*, even though the wire format differs entirely (protobuf vs.
bplist/msgpack):

- clean separation between "decode the envelope/container" and "walk the
  decoded message tree" — matches our own §9 plan to keep bplist-decode and
  Concepts-object-graph-walk as separate layers.
- per-channel decode table as a flat data structure (`@POINT_CHANNELS`,
  name/size/kind triples) driving one generic loop, instead of hand-writing
  ~10 near-identical decode branches — directly reusable pattern for our own
  iOS point-channel decoder once §4a's byte assignments are confirmed.
- uses `Math::BigRat` to keep angle/pressure values as exact rationals
  instead of lossy floats, and `Data::Float::float_hex()` to print float32
  values as exact IEEE754 hex rather than rounded decimal — worth
  considering if we ever want to bit-for-bit diff against a reference
  decode, though probably overkill for a first pass.
- uses real CPAN modules throughout (`Google::ProtocolBuffers`,
  `DBI`/`DBD::SQLite`) rather than hand-rolling protobuf — i.e. that
  project's own rule is "hand-roll only the formats with no existing
  module," same rule §1/§9 set for us (bplist00, MessagePack — confirmed
  not installed; zip — a solved problem, don't reinvent it).

**Open question raised by this comparison, not resolved by it**: since
writing §9, `Mac::PropertyList`, `Data::MessagePack`, and `Archive::Zip`
have all been installed locally (visible under `perl5/` in this repo now).
That's a genuine fork in plan worth deciding explicitly before writing any
code: are these meant as (a) the actual implementation, superseding the
original "hand-roll bplist/msgpack ourselves" plan, or (b) a reference
oracle to diff our still-hand-rolled decoder's output against (mirroring
how the Apple Notes project leans on real modules for solved problems but
still hand-rolled the genuinely novel per-point channel decode)? The two
plans want different code structure from the very first line, so worth
pinning down before starting.

**Resolved** (decided in conversation): use the modules. `ai/tools/
concepts-drawing-decode.pl` is built on `Mac::PropertyList`/`Archive::Zip`
directly, not a hand-rolled bplist/zip reader.

## 13. `ai/tools/concepts-drawing-decode.pl` - findings from actually building it

First working decoder, modeled on `apple-notes-drawing-decode.pl`'s shape
(container decode / graph walk / s-expression or InkML render, split into
separate layers) but outputting the full named/nested layer tree (§3)
instead of a flat stroke list - see the script's own header comment for
the full rationale on each point below; this section is the durable
record of what testing it against our real sample corpus (§10) turned up
that §1-§12 didn't already know.

**A real, confirmed bug in the installed `Mac::PropertyList` (worked
around, not patched in place)**: its binary-plist array/dict readers
unpack object-reference bytes with `_object_ref_size == 1 ? "C*" : "n*"` -
only 1-byte refs are handled correctly; anything needing 4-byte refs
(any archive with >65535 low-level bplist objects total - not a contrived
case, a few thousand strokes gets there easily given how many sub-objects
each one has) gets silently misparsed. Symptom looked exactly like a
self-referencing object (bplist index 0 appearing to reference itself),
recursing until the process was killed - confirmed via `Carp::cluck` that
it's `unpack("n*", ...)` misreading 4-byte big-endian refs as 2-byte ones,
not a real cycle in the data. Reproduced against a real file in our own
corpus: `~/my/art/export/iPad/New Folder/New Drawing 2 Copy.concept`
(trailer decodes to `ref_size=4, num_objects=92039`). Since the buggy
closures are private to a `BEGIN` block in `ReadBinary.pm` and unreachable
from outside, the script replaces `_read_object`/`_read_object_at_offset`
wholesale (typeglob assignment) with a corrected reimplementation that
also adds memoization by object index (the original has none - harmless
for small files, wasteful for large ones with heavily-shared objects like
every stroke's `$class`). Fixed, this file now parses in well under a
second.

Drafted an actual upstream fix (not just our own script's workaround) in
`~/net/github.com/briandfoy/mac-propertylist` (currently uncommitted
there; decided in conversation not to open a PR yet). Confirmed with a
minimal, Concepts-independent repro first: a 70,000-element array/dict
built via the module's own `Mac::PropertyList::WriteBinary` (forces
`ref_size=4`) round-trips as silent garbage on the unfixed reader - not
just misordered, actual cross-contamination (`key26913` came back holding
`key30930`'s value). The real fix consolidates the three duplicated buggy
unpack sites into one `_read_refs`/`_unpack_sized_ints` helper, mirroring
a pattern the file already used correctly for its *offset table* sizing
(`_get_offset_table`) but never applied to object-ref sizing. Extended
past just fixing size 4: the trailer's `ref_size`/`offset_size` bytes are
spec-legal at any width from 1 to 8 (nothing in the format restricts them
to a power of two), so `_unpack_sized_ints` (the shared helper both
`_read_refs` and `_get_offset_table` now use) handles every width 1-8
uniformly rather than only the ones some writer has been observed to
produce. Widths 5 and up fall back to a manual most-significant-byte-first
decode accumulated into `Math::BigInt`, since there's no native `unpack()`
template for them and (7-8 especially) values can exceed a native
double's 2^53 exact-integer limit on any Perl, or a plain integer's range
on a 32-bit-`IV` build - verified directly against 2**64-1 for width 8.
**What was actually special about the real Concepts file? Nothing** - and
that turned out to matter for how the regression tests ended up shaped.
Dug into it because publishing the real 1.8MB `Strokes.plist` (personal
artwork) as a test fixture felt like the wrong move if it wasn't
necessary. Broke down `New Drawing 2 Copy.concept`'s 92039 low-level
bplist objects by `$classname`: 1966 `Stroke`s, and each one's *own* dict
contributes on the order of a dozen-plus low-level objects on top of
itself - a `BrushProperties` dict, a `UIColor` dict, an `NSDate`-shaped
timestamp dict, a `keyPoints` `NSMutableArray` plus one `KeyPoint` dict
per key point, and (this is the part that actually inflates the count)
every dict *key string* (`"brushWidth"`, `"diIdentifier"`, `"$class"`, ...)
is its OWN separately-numbered low-level bplist object too, not free
metadata - dict keys aren't just Perl hash keys once you're back at the
raw byte level. `92039 / 1966 ≈ 46.8` low-level objects per stroke - not
a bug, not an anomaly, not "orphaned"/leftover data (checked and ruled
that out directly: `scalar(@{$objects})`, i.e. the NSKeyedArchiver-level
`$objects` array's own length, was only 16667 for this file - the other
~75000 are exactly this key-string/sub-dict multiplication, counted at
the low bplist level rather than the NSKeyedArchiver level). Just ordinary
per-stroke bookkeeping, multiplied by "a few thousand strokes," crossing
65536.

The load-bearing fact: **`ref_size`/`offset_size` are pure trailer bytes
that don't care what produced the file or what's in it - only how many
low-level objects there are in total.** A minimal synthetic file with a
trailer that *declares* a wide `ref_size` exercises the identical decode
path a huge real file needs that width for, with zero real content
involved. Confirmed this isn't just a theory: hand-built a tiny (62-139
byte) 5-element-array bplist per width 1-8 (plus one with
`offset_size != ref_size`, to make sure the two trailer fields are
decoded independently rather than one accidentally reusing the other's
width) via a new `hacks/make-wide-refs-fixtures.pl`, each with its
trailer manually over-provisioned to claim that width regardless of the
tiny content - verified every one against *three* independent readers
(this module's fixed one, Python's `plistlib`, and real Apple `plutil`
over `ssh MBP`) before trusting them, and confirmed each one reproduces
the exact failure class (hang on 3/4, garbage/crash on 5-8) against the
*pre-fix* code. These now live as real fixture files under `plists/`
(`wide-refs-1.plist` .. `wide-refs-8.plist`, `wide-refs-mismatched.plist`),
read via `Mac::PropertyList::parse_plist_file(catfile(qw(plists ...)))` -
matching this project's own existing convention (`t/read_binary.t` reads
`plists/the_perl_review.abcdp` the same way) - rather than only
synthesizing bytes inline in the test. So: nothing from the real Concepts
file needed publishing at all; the generic trailer-width property was the
whole story, and a ~100-byte synthetic file proves it just as completely
as the 1.8MB real one would have. `t/read_binary_large_refs.t` now checks
0/1/max-value round-trips at every width 1-8 directly against
`_unpack_sized_ints` too (32 more checks, a stronger arithmetic check than
the fixtures' fixed small values alone give), plus the original real-file
round-trips at widths 2 and 4 through the actual reader/writer at
realistic scale. Fails on the pre-fix code, passes after. Full suite:
301/301. `MANIFEST` updated to list the new `plists/`/`t/` files (not
`hacks/make-wide-refs-fixtures.pl` itself - matching how sibling files in
`hacks/` are dev-only and already absent from `MANIFEST`).

Cross-checked against two independent implementations, not just our own
patched reader agreeing with itself: (1) Python 3.14's standard-library
`plistlib` - read its own `_count_to_size`/`_BINARY_FORMAT` source
directly, confirmed it independently also only ever picks 1/2/4/8 (never
3/5/6/7), same as this module's writer; fed a Python-written 70000-element
binary plist (forces `ref_size=4`) through the *unfixed* Perl reader and
it hung/recursed exactly the same way our own Concepts files did,
confirming this isn't an artifact of round-tripping through the same
(possibly-also-buggy) Perl writer - then confirmed the *fixed* reader
round-trips it perfectly. (2) Real `plutil` on macOS (via `ssh MBP`,
macOS 15.7.7) - `plutil -convert binary1` on the same 70000-element data
produced a file **byte-for-byte identical** to Python's own binary
writer output, and the fixed Perl reader round-trips that genuine
Apple-produced file perfectly too; separately, `plutil -lint` accepted a
plist written by this module's own `WriteBinary` as fully spec-valid.
No test file/oracle actually exercises `offset_size`/`ref_size` outside
{1,2,4} in the wild across any of Perl/Python/real-`plutil`'s writers -
3 and 5-8 remain "spec-legal, defensively handled, never yet observed"
even after this cross-check, which is exactly why the unit-level
all-widths test above matters on its own rather than as a stand-in for
a real-file test that may never exist.

**Update: the `_read_plist_trailer` truncation flagged above is also a
real, now-fixed bug** - the user asked for it to actually be verified
rather than left as a hunch, so it was. `_read_plist_trailer` read
`object_count`/`top_object`/`table_offset` with
`unpack "x6 C C (x4 N)3"` - "skip 4 bytes, read a 4-byte big-endian int,"
three times - which, since these are genuinely 8-byte big-endian fields,
always keeps the low 4 bytes and silently discards the high 4. Unlike
`ref_size`, this isn't only reachable via an absurd object count:
`table_offset` is a byte position in the file, so any single file over
4GB total size hits it regardless of object count (one huge embedded
blob is enough) - `object_count`/`top_object` still need >4 billion
objects, which stays unrealistic.

Verified empirically, not just by inspection: built a real (not
synthetic-trailer-only) file whose offset table genuinely sits past the
4GB mark, using a sparse hole in the middle (confirmed ~8KB actual disk
for a file that reports as >4GB - safe to construct here). Against the
unfixed code: `table_offset` truncated to a small in-range value that
happened to land inside the sparse (all-zero) hole; that decoded as a
zeroed-out offset table; every object resolved to file position 0 - the
literal `"bplist00"` magic bytes - misparsed as a 2-character UTF-16BE
string. No error anywhere, just silently wrong output (worse than the
`ref_size` bug in that specific way).

Fixed by reusing `_unpack_sized_ints` at size 8 (the same helper the
`ref_size` fix already built) for all three fields. Added
`t/read_binary_trailer_counts.t`: a fast, always-run subtest exercises
`_read_plist_trailer` directly against a tiny in-memory buffer whose
*trailer bytes* just claim values past 2^32 (the function only ever
reads the last 32 bytes of whatever it's given, so no large file is
needed to test the arithmetic in isolation) - this is the real regression
coverage, safe on every platform including CPAN Testers. A second
subtest does build a genuine sparse >4GB file end-to-end, but only when
`$MAC_PROPERTYLIST_TEST_HUGE_TRAILER` is explicitly set - deliberately
*not* unconditional, because this project's own CI runs `xt/`
unconditionally on Linux/macOS/**and Windows**, and Windows/NTFS does not
auto-sparsify a file just because you seek and write past its current
end the way Linux/macOS filesystems do - an unconditional version could
have tried to allocate 4GB of real disk on a Windows CI runner (or,
worse, on a CPAN Testers box - which is exactly the failure mode
raised and specifically wanted avoided before committing anything).
Confirmed the fixture-building code itself had its own unrelated bug on
the first attempt (an errant `- 8` in the offset calculation, unrelated
to the module fix) before trusting a green result - worth remembering
that a passing "reproduction" needs the same scrutiny as the fix itself.
Full suite: 304/304, both with and without the opt-in env var, disk usage
unchanged either way.

**Zip member names aren't always spelled the way you'd expect**: one real
file (`~/my/art/export/iPad/Welcome to Concepts/Section 1/Untitled
1.concept`, dated 2020 - an older export) stores its entries with a
leading `/` (`/Strokes.plist`, not `Strokes.plist`), which
`Archive::Zip`'s exact-match `memberNamed()` then can't find at all. The
script now matches by exact name, leading-`/`-stripped name, or `/`-suffix
rather than one fixed spelling (`find_member()`). Before this fix, the
script wrongly reported "no Strokes.plist" for a file that in fact had
one - worth remembering if any other tool in this repo ever reaches for
`memberNamed()` directly.

**A stroke's `$classname` is not reliably "Stroke" - it can be a per-tool
subclass, and this resolves part of §11 Q2**: a real file
(`.../Welcome to Concepts/Section 1/Untitled 2.concept`) has strokes whose
leaf class is `BallpointPen`, not `Stroke` - confirmed its `$classes`
ancestry is `[BallpointPen, Stroke, DrawingItem, NSObject]`, i.e. a real
subclass, not a naming coincidence. This means classifying items by exact
`$classname eq 'Stroke'` (as this script originally did, and as you'd
naturally write from the one Converted-PLISTS reference sample) silently
drops any stroke drawn with whatever tool produces that subclass - the
script now dispatches by ancestry (`is_a($objects, $item, 'Stroke')`,
checking the full `$classes` list) instead, and reports the leaf class
name alongside `brushToolNew` in its output specifically so this kind of
per-tool subclass stays visible rather than silently collapsing to
"Stroke" as every 3rd-party viewer's code currently does.

Cross-referencing `$classname` against `brushToolNew` across our whole
real-file corpus (17 files) gives a first, partial, empirical answer to
§11 Q2 ("no id→name table found for `brushToolNew`"):

```
BallpointPen  tool=1   (1539 strokes, 100% of BallpointPen-classed strokes)
Stroke        tool=1   (6437 strokes)
Stroke        tool=2   (4 strokes)
Stroke        tool=11  (24 strokes)
Stroke        tool=14  (3 strokes)
Stroke        tool=16  (24 strokes)
```

Reading: `brushToolNew=1` is very likely "ballpoint pen" (every
`BallpointPen`-classed stroke has it, with high confidence given the
sample size) - but the same id also appears on plain `Stroke`-classed
objects in other (newer-looking) files, suggesting Concepts *used to*
encode tool identity via a per-tool subclass (`BallpointPen`, and
presumably siblings for Marker/Fineliner/Pencil/etc., none seen yet in
our corpus) and at some point switched to a single generic `Stroke` class
plus this numeric field instead - i.e. `$classname` and `brushToolNew`
are two eras of the same "which tool drew this" fact, not two independent
signals. Tool ids 2/11/14/16 remain unmatched to any name - would need
either a subclass-tagged sample for each (none in our corpus) or the
strings-in-the-binary approach `apple-notes`'s `drawing.md` used for
`Ink.identifier`-style names, applied to the Concepts app binary itself
(out of scope for a file-format-only decoder, but worth remembering as an
option).

**Not yet exercised**: no PDF page (`TiledPDFPage`) or embedded-image
sample in our corpus reached the script's image/PDF decode path at all
(§10's inventory has no file with an actual imported image or PDF) - that
code is written straight from the plist shape in §6, not checked against
a real file the way the stroke path now has been. Treat it with the same
confidence level as an un-cross-checked viewer-code guess until a real
sample turns up.
