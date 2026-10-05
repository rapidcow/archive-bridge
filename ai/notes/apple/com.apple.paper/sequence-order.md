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

## Not yet established

- Whether decode (`decodeSubstrings` 0x22df5f978) preserves the wire `child` order or
  rebuilds children by another rule (e.g. `TopoID` comparison, `TopoID::operator<`
  0x22df62558, `minimumPermenantID` 0x22df62480).
- The pairing between a `Set` member and a position (the `Set` side lives in Swift
  `CROrderedSet`, whose functions are stripped). Probable rule: member `added_by`
  counter == position counter, per skeleton.md; unverified for multi-replica bundles.
- How replica identity enters ordering (merge uses `TopoID` including replica).
