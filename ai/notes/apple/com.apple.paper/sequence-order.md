# Sequence document order, from static disassembly (macOS 15.8.1, 24H32)

Source: `Coherence` in the live dyld shared cache
(`/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e`),
read with `ipsw dyld macho ... --symbols --demangle` and `ipsw dyld disass --vaddr`.
No Notes data was opened. Addresses are unslid (this cache build only).

## Why this is the right code

Swift `CRSequence` is generic over `CRTTMergeableString` storage (`CRSequenceStorage`
typeref `CRTTMergeableStringCy...`), the C++ engine behind Notes text. Its C++ symbols
are NOT stripped: `TopoSubstring`, `TopoID`, `TopoIDRange`, `traverseIterative`. Mapping
(inferred from names and sentinel checks `isStartNode`/`isEndNode`, not proven):
`Subsequence` ~ `TopoSubstring`, `Subsequence.added_by` ~ `TopoID`,
`Subsequence.child` ~ `TopoSubstring` children vector.

## Findings (read from the instructions, CONFIRMED against the disassembly)

- `TopoSubstring::addChild` (0x22df60008): plain `vector::push_back` onto the children
  vector at `+0x38`, then `child->+0x50 += 1`. So `+0x50` is the child's **in-degree**
  (number of nodes listing it as a child). No sorting on insertion. The graph is a DAG,
  not necessarily a tree.
- `-[CRTTMergeableString orderedSubstrings]` (0x22df67e1c) returns the cache vector at
  `+0x8`, rebuilt by `_updateCache` (0x22df67ea4), which just appends every node visited
  by `traverseIterative(root, block)` (0x22df62ef4 -> 0x22df62d3c).
- `traverseIterative` (0x22df62d3c), an explicit stack (`std::deque`, popped from the back):
  1. push root.
  2. pop `n`, **visit `n`**.
  3. while `n` has exactly one child `c` and `c.indegree == 1`: visit `c`, `n = c`.
  4. for each child `c` of `n` in **reverse** list order:
     - if `c` is already in the pending map: decrement its count; push when it reaches 0.
     - else if `c.indegree < 2`: push.
     - else: map[c] = indegree - 1 (not pushed).
  5. repeat until the stack is empty.

Net effect: **depth-first pre-order, children visited in listed order (first child
first), except a node with in-degree k >= 2 is deferred until its k-th parent has been
visited** (topological join). Visit order of runs = document order of positions;
positions inside a run are in counter order (`splitAt`, `isSequentialWith`).

## Decode keeps the wire order (CONFIRMED, `decodeSubstrings` 0x22df5f978)

`decodeSubstrings` loops over the substrings in wire order. Per substring it reads, via
the `CRStringDecoderProtocol` selectors: char replica + clock, length, the addedBy list,
the removedAddedBy list (never seen on our wire), `substringContentOptions`, then the
children: for i in 0..<`substringChildCount`: `idx = substringChild:i`, then
`parent.addChild(substrings[idx])`. So `Subsequence.child` entries are indices into the
`subsequence` array and the list order is preserved verbatim; nothing sorts them, and
`TopoID` comparison is not used at load. Error strings (logged via NSLog): "child
substring index > number of substrings", "substring max > string max", "length of
substrings != attributedString length" (the last is the Swift-side counterpart of our
rule: summed live run length == element count). `contentOptions == 2` marks a node with
no content (its char offset is set to -1); whether the Swift decoder derives that from
the missing field 3 is unverified.

The Swift side (`CRStringDecoder`, `CRSequenceDecoder`) has no named code symbols and I
did not read its field getters (a symbol lookup failed). Field numbers stay as
established by the round-trip sweep.

## Not yet established

- The pairing between a `Set` member and a position (the `Set` side lives in Swift
  `CROrderedSet`, whose functions are stripped). Probable rule: member `added_by`
  counter == position counter, per skeleton.md; unverified for multi-replica bundles.
- How replica identity enters ordering (merge uses `TopoID` including replica).

## Check against the 52 bundles of cloud-ethan-1 (2026-10-05; copies, structure only)

Rule applied: the `traverseIterative` order above over `Subsequence.child`; pairing
tested: member `added_by` counter == live run content counter (`sequence_start_id` +
offset within the run). Findings (counts from the dump JSON, not from the binary):

- The traversal reaches every run in all 52 bundles. Exactly one bundle (`4AB3CD6B`) has
  a run with in-degree 2, so the join rule is exercised once, not in the others.
- Every bundle has exactly ONE member replica (all `Set` `added_by` on one replica).
  `Sequence.added_by_version` has the same size as `Set.version` in the bundles checked:
  content counters and member counters are one clock.
- Counter-equality pairing: 28/52 bundles match exactly. 4,774 of 28,496 members have no
  counter match overall, of which about 3,661 are in the 10 bundles whose live runs carry
  content ids from 2-4 replicas (several writers); the other ~1,113 are in 13
  single-replica bundles with 1-346 unmatched each (member counter and content counter
  off by small amounts, e.g. 169 vs 167).
- Rank pairing (k-th smallest member counter to k-th smallest content counter) is NOT
  consistent (offsets not monotone), so it is rejected.
- `OrderedSet.timestamps` is one distinct `Constants.uuids` index per member (6, 7, 8,
  ...), contiguous; it identifies members, not positions.
- Ground truth so far: pen-test fixture (one replica, no tombstones) where Set order,
  counter order and colour order agree. No ground truth exists yet for the unmatched cases.
