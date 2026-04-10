# Changelog

## [0.2.0] - 2026-04-08

### New Collection Types

- **Rope** (`rope`) — persistent chunked sequence for O(log n) structural editing
  (concat, split, splice, insert, remove). Backed by a weight-balanced tree of
  chunk vectors with a formal Chunk Size Invariant. Up to 1968x faster than
  `PersistentVector` on repeated random edits at 500K elements; 3-10x faster
  on concatenation; 1.3-1.7x faster on reduce at scale. Includes
  structure-sharing subrange views via `rope-sub`, transient support for batch construction,
  parallel `r/fold`, `java.util.List` interop, and lexicographic `Comparable`.
- **Range Map** (`range-map`) — non-overlapping `[lo, hi)` ranges with automatic carve-out on insert
- **Segment Tree** (`segment-tree`, `sum-tree`, `min-tree`, `max-tree`) — O(log n) range aggregation with any associative operation
- **Priority Queue** (`priority-queue`) — O(log n) push/peek/pop with min and max access
- **Ordered Multiset** (`ordered-multiset`) — sorted bag allowing duplicate elements
- **Fuzzy Set/Map** (`fuzzy-set`, `fuzzy-map`) — nearest-neighbor lookup by distance with configurable tiebreaking

### New Operations

- **Set algebra**: `union`, `intersection`, `difference`, `subset?`, `superset?`, `disjoint?`
- **Positional**: `rank`, `slice`, `median`, `percentile`
- **Navigation**: `nearest` (floor/ceiling with keyword tests `:<=`, `:>=`, `:<`, `:>`), `subrange`, `split-key`, `split-at`
- **Interval**: `overlapping`, `span`
- **Range map**: `ranges`, `span`, `gaps`, `assoc-coalescing`, `get-entry`, `range-remove`
- **Segment tree**: `query`, `aggregate`, `update-val`, `update-fn`
- **Priority queue**: `push`, `push-all`, `peek-min`, `peek-min-val`, `pop-min`, `peek-max`, `peek-max-val`, `pop-max`
- **Multiset**: `multiplicity`, `disj-one`, `disj-all`, `distinct-elements`, `element-frequencies`
- **Fuzzy**: `fuzzy-nearest`, `fuzzy-exact-contains?`, `fuzzy-exact-get`
- **Rope**: `rope-concat`, `rope-concat-all`, `rope-split`, `rope-sub`, `rope-insert`, `rope-remove`, `rope-splice`, `rope-chunks`, `rope-chunks-reverse`, `rope-chunk-count`, `rope-str`
- **Map**: `assoc-new`, `ordered-merge-with`
- **Comparator**: `general-compare` — opt-in total order over all values including non-Comparable types (Namespace, Var, etc.). ~20% slower lookups on Comparable types vs default.

### Specialized Constructors

- Rope: `rope`
- Type-specific: `long-ordered-set`, `long-ordered-map`, `double-ordered-set`, `double-ordered-map`, `string-ordered-set`, `string-ordered-map`
- Custom comparator: `ordered-set-by`, `ordered-map-by`, `ordered-set-with`, `ordered-map-with`, `ordered-multiset-by`, `fuzzy-set-by`, `fuzzy-map-by`, `segment-tree-by`, `segment-tree-with`
- Exported comparators: `long-compare`, `double-compare`, `string-compare`, `general-compare`, `compare-by`

### Interface Implementations

- `clojure.lang.Sorted` — native `subseq`/`rsubseq` on ordered-set and ordered-map
- `clojure.core.reducers/CollFold` — chunked parallel fold; ordered-set/map, rope, and compatible tree-backed types split into larger chunks before delegating to `r/fold`
- `clojure.lang.IEditableCollection` / `ITransientCollection` — transient support for `Rope` with mutable tail buffer for efficient batch construction
- `clojure.core.protocols/CollReduce` — implemented directly on all collection deftypes for correct fast-path reduction
- `clojure.lang.IHashEq` — correct `hash` for use in hash-based collections
- `java.io.Serializable` — Java serialization support
- `IReduceInit`/`IReduce` — direct tree traversal for fast `reduce`
- Direct `ISeq` implementations (`KeySeq`, `EntrySeq`) replace lazy-seq wrappers

### EDN Tagged Literals

Round-trip serialization via `data_readers.clj`: `#ordered/set`, `#ordered/map`, `#interval/set`, `#interval/map`, `#range/map`, `#priority/queue`, `#multi/set`, `#vec/rope`. Collections with custom comparators (including `general-compare`) print in opaque `#<Type ...>` form to avoid non-round-trippable tagged literals.

### Performance

- **Parallel set operations** via ForkJoinPool with operation-specific root thresholds (`65,536–131,072`), `65,536` recursive thresholds, and `64` sequential cutoff
- **Primitive node types** (`LongKeyNode`, `DoubleKeyNode`) — unboxed key storage
- **Primitive lookup fast path** — `long-ordered-set` bypasses `Comparator` dispatch
- **Interval overlap** — `intersects?` reduced from up to 12 comparisons to 2 (closed-interval identity: `a0 <= b1 AND a1 <= b0`)
- **Reduction refactor** — unary reducers (nodes, keys, entries) share a single enumerator-based kernel; kv reducers remain separate to avoid packing overhead. All support `reduced` short-circuiting.
- Fold benchmarking includes a non-trivial frequency-map workload comparing `ordered-set` fold against `hash-set` reduce, `sorted-set` fold/reduce, and `data.avl` fold/reduce
- Benchmark/test infrastructure shares common workload generators, reference helpers, and competitor builders via `test-utils` and `bench-utils`

See [benchmarks](doc/benchmarks.md) for current numbers and analysis.

### Build

- Namespace root is now `ordered-collections.*` / `ordered_collections.*` rather than `com.dean.ordered-collections.*`
- Source/test tree lives under `src/ordered_collections` and `test/ordered_collections`
- `lein stats` — babashka-based project statistics report (clj-kondo analysis, git churn, codebase metrics)

### Bug Fixes

- `SortedSet.tailSet` now returns elements >= x (was exclusive)
- `SortedSet.subSet` now returns elements >= from, < to
- Interval tree construction uses sequential reduce (parallel fold lost dynamic binding for node allocator at >2048 elements)
- `segment-tree` range queries are generic over ordered keys rather than assuming integer-only query bounds
- `general-compare` collections print opaque (`#<OrderedSet ...>`) rather than emitting tagged literals that cannot round-trip through EDN
- Priority queue uses direct seq adapters instead of lazy `map` wrappers; stronger coverage for duplicate-priority ordering and boundary cases

### Documentation

- New [Ropes](doc/ropes.md) — rope tutorial, use cases, and design
- New [Collections API](doc/collections-api.md) — per-type constructor and operation reference
- [Migration guide](doc/vs-clojure-data-avl.md) corrected (`oc/rank` not `oc/rank-of`)
- Performance documentation consolidated in `doc/benchmarks.md`; the older `perf-analysis.md` and `when-to-use.md` are removed
- Cookbook examples refreshed for current semantics
- Generated `doc/api` output is no longer tracked in the repository

### Breaking Changes

- **Removed** `mutable-ordered-set`, `mutable-ordered-map`, `mutable-interval-set`, `mutable-interval-map`
- **Removed** `transient`/`persistent!` support (path-copying made it a no-op)
- **Renamed** public function: `rank-of` → `rank` (returns `nil` instead of `-1` for missing elements)
- Public namespace root and Maven/Lein artifact coordinate are now `ordered-collections`

---

## [0.1.2] - 2024

- Documentation improvements
- Minor bug fixes

## [0.1.1] - 2024

- Initial public release
- Weight-balanced persistent binary trees
- `ordered-set`, `ordered-map`, `interval-set`, `interval-map`
- Efficient set operations (intersection, union, difference)
- `nth` and `indexOf` in O(log n) time
