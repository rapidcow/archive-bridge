# `Coherence.framework` — the generic CRDT engine behind the Paper store

Companion to `envelope.md` (which reverse-engineers the on-disk bytes from
scratch, empirically). This file is about the actual Apple binary that
produces/consumes those bytes: `/System/Library/PrivateFrameworks/
Coherence.framework`, a private framework. Not specific to drawings/Paper -
this is Apple Notes' generic CRDT storage engine (the same "Coherence" name
the earlier scratch Swift attempt linked against via `Capsule<Paper>`,
`CRContext`, `CRKeyPath`, etc.) - so everything here should apply to any
Coherence-backed Notes content, not just drawings.

## Headline result

`Coherence` links a private, vendored copy of SwiftProtobuf
(`InternalSwiftProtobuf.framework`, a separate `LC_LOAD_DYLIB`) and defines
a real, compiler-generated set of `proto3` Swift message types, all named
`Proto_*`. Their names map almost one-to-one onto everything independently
inferred from raw bytes in `envelope.md`, with **zero prior knowledge of
these symbol names used while writing that file** - i.e. this is
independent confirmation, not the source the guesses were reverse-engineered
from:

| `envelope.md` finding (from bytes alone)                          | Matching `Proto_*` Swift type            |
|---------------------------------------------------------------------|-------------------------------------------|
| `Reference` sqlite table, `Id`→`Data` rows                          | `Proto_Reference` (has a nested `TypeEnum`) |
| `Id` encoding (`CRKeyPath` raw bytes)                                | `Proto_KeyPath`                            |
| `RetainCount`/`ChildRetainCounts` sqlite columns                    | `Proto_RetainCounts`                       |
| `Assets` sqlite table                                                | `Proto_AssetV` (probably `Proto_Asset`, mangled) |
| generic "typed value wrapper" seen around every leaf (`4: "<bytes>"`) | `Proto_Value`, `Proto_TaggedValue`, `Proto_Optional` |
| the `field 2 → 9 → 1 → ...` ordered-list-with-tie-break-bytes structure found in the root manifest (see envelope.md's "Possible ordered-sequence CRDT structure") | `Proto_OrderedSet` (has its own `TypeEnum`), plus `Proto_Sequence` / `Proto_Subsequence` / `Proto_RLE` as likely-related building blocks |
| per-property CRDT register (`CRRegister` in the earlier reflection-based attempt) | `Proto_Register` |
| whole-bundle/document container                                     | `Proto_Document`                           |
| version/clock-ish small structs seen everywhere                     | `Proto_Timestamp`, `Proto_Version`, `Proto_Counter` |
| self-describing struct-with-named-fields shape (the `"bounds"/"frame"/"drawing"` string table) | `Proto_Struct` |

Also present but not yet tied to a specific byte-level finding:
`Proto_CRDT`/`Proto_CustomCRDT`/`Proto_CustomCRValue`, `Proto_Constants`,
`Proto_Selection`, `Proto_Set`, `Proto_RepeatedValue`.

`Proto_Sequence` additionally has a nested `AttributeRun` type - suggestive
of rich-text attribute runs (bold/italic spans etc.), which would make sense
for a *generic* engine shared with plain-text notes, not something specific
to drawings.

## What's genuinely confirmed vs. not

**Confirmed:** these exact type names exist in `Coherence`'s Swift type
metadata (`nm`'s demangled/mangled symbol table, cross-checked against
`__TEXT.__swift5_proto`/`__swift5_types` sections being present and
non-empty) and each carries `SwiftProtobuf.Message`-family protocol
conformance markers (`_associated conformance ... InternalSwiftProtobuf7Message`
entries for each type). That's a hard fact, not inference.

**Not confirmed:** field numbers, field names, or even the exact mapping in
the table above beyond "these names strongly suggest this role." No
`_protobuf_nameMap` static properties were found as exported/named symbols
for any of these types (checked via `nm` grep across the whole symbol
table, both with and without `-U`) - either they were compiled out (Apple
may build with SwiftProtobuf's binary-only mode, skipping the JSON/text
name table entirely since it's never used over the wire to non-Swift code),
or they exist but aren't individually named symbols. Either way, this means
recovering field *numbers* (names are likely gone for good) requires
disassembling each type's generated `decodeMessage(decoder:)`/
`traverse(visitor:)` methods directly - and those aren't exported symbols
either (`nm -U` and plain `nm` both came up empty for
`decodeMessage`/`traverse` symbols scoped to any `Proto_*` type), consistent
with this being a heavily locally-stripped shared-cache binary (total
symbol count barely changes with/without `-U`, unlike a normal debug
build). Locating them would require either:
- walking `__TEXT.__swift5_proto` protocol conformance records to find each
  `Proto_*` type's witness table for the `SwiftProtobuf.Message` protocol,
  then reading the `decodeMessage`/`traverse` function pointers out of that
  witness table and disassembling from there, or
- pattern-matching the disassembly directly (SwiftProtobuf's generated
  decode loop has a very recognizable shape: a `switch fieldNumber` inside
  a `while let fieldNumber = try decoder.nextFieldNumber()` loop, each case
  calling one of a small family of `decodeSingular*Field`/`decodeRepeated*Field`
  functions - these callee names *are* likely to survive as imported symbols
  from `InternalSwiftProtobuf.framework` even if the caller isn't
  individually named, which could let you find call sites by imported-symbol
  cross-reference instead of by conformance-record walking).

Neither of those was attempted yet this session - this file stops at "the
schema-shaped types exist and roughly what they're each probably for,"
not "here are the field numbers."

## Methodology

1. SSH'd to the same source Mac used for `com.apple.drawing.2` (macOS
   26.6.2, build 25G83, Apple Silicon), where `ipsw` (blacktop/ipsw,
   already installed) and prior framework extracts live in `~/re-notes`.
2. `ipsw dyld info -l dyld_shared_cache_arm64e | grep -i coherence` located
   two private frameworks: `Coherence.framework` and
   `_Coherence_CloudKit_Private.framework` (the latter not examined).
3. `ipsw dyld extract --objc -o . dyld_shared_cache_arm64e
   /System/Library/PrivateFrameworks/Coherence.framework/Versions/A/Coherence`
   produced a standalone Mach-O (`~/re-notes/Coherence`, 6.0 MB).
4. `nm -U Coherence | grep -oE 'Proto_[A-Za-z_]+' | sort -u` enumerated
   every distinct `Proto_*` type name (Swift name-mangling keeps original
   identifiers as literal substrings, so this works without a full
   demangle pass). Cross-checked a couple of the more interesting ones
   (`Proto_OrderedSet`, `Proto_Sequence`) by grepping their full mangled
   symbol names to confirm nested types (`Proto_Reference.TypeEnum`,
   `Proto_Sequence.AttributeRun`) and protocol conformances.
5. `ipsw macho info Coherence` confirmed `InternalSwiftProtobuf.framework`
   as a real `LC_LOAD_DYLIB` dependency (not just an inferred naming
   convention) and listed the Swift metadata sections present
   (`__swift5_proto`, `__swift5_types`, `__swift5_fieldmd`, etc.) that a
   deeper pass would need to walk.
6. Searched for `_protobuf_nameMap`/`decodeMessage`/`traverse` symbols
   across the full symbol table (not just exported) - none found scoped to
   any `Proto_*` type, which is why this file stops short of field numbers.

## Provenance

- Source Mac: macOS 26.6.2 (build 25G83), Apple Silicon, reached via SSH
  (same machine/session as `com.apple.drawing.2`'s notes).
- Tooling: `ipsw` (blacktop/ipsw, already installed at `/opt/homebrew/bin/
  ipsw`), `nm`.
- Binary: `Coherence.framework` (private framework), extracted from
  `dyld_shared_cache_arm64e`, kept at `~/re-notes/Coherence` on the source
  Mac (not committed to this repo).
- No disassembly was performed for this file - purely symbol-table and
  Mach-O-section-level inspection. See "What's genuinely confirmed vs. not"
  above for exactly where that boundary is.
