# Changelog

## [0.2.0-SNAPSHOT] - 2025-02-11

### New Collection Types

- **Range Map** (`range-map`) — non-overlapping `[lo, hi)` ranges with automatic carve-out on insert
- **Segment Tree** (`segment-tree`, `sum-tree`, `min-tree`, `max-tree`) — O(log n) range aggregation with any associative operation
- **Priority Queue** (`priority-queue`) — O(log n) push/peek/pop with min and max access
- **Ordered Multiset** (`ordered-multiset`) — sorted bag allowing duplicate elements
- **Fuzzy Set/Map** (`fuzzy-set`, `fuzzy-map`) — nearest-neighbor lookup by distance with configurable tiebreaking

### New Operations

- **Set algebra**: `union`, `intersection`, `difference`, `subset?`, `superset?`, `disjoint?`
- **Positional**: `rank-of`, `slice`, `median`, `percentile`
- **Navigation**: `nearest` (floor/ceiling with keyword tests `:<=`, `:>=`, `:<`, `:>`), `subrange`, `split-key`, `split-at`
- **Interval**: `overlapping`, `span`
- **Range map**: `ranges`, `span`, `gaps`, `assoc-coalescing`, `get-entry`, `range-remove`
- **Segment tree**: `query`, `aggregate`, `update-val`, `update-fn`
- **Priority queue**: `push`, `push-all`, `peek-min`, `peek-min-val`, `pop-min`, `peek-max`, `peek-max-val`, `pop-max`
- **Multiset**: `multiplicity`, `disj-one`, `disj-all`, `distinct-elements`, `element-frequencies`
- **Fuzzy**: `fuzzy-nearest`, `fuzzy-exact-contains?`, `fuzzy-exact-get`
- **Map**: `assoc-new`, `ordered-merge-with`

### Specialized Constructors

- Type-specific: `long-ordered-set`, `long-ordered-map`, `double-ordered-set`, `double-ordered-map`, `string-ordered-set`, `string-ordered-map`
- Custom comparator: `ordered-set-by`, `ordered-map-by`, `ordered-set-with`, `ordered-map-with`, `ordered-multiset-by`, `fuzzy-set-by`, `fuzzy-map-by`, `segment-tree-by`, `segment-tree-with`
- Exported comparators: `long-compare`, `double-compare`, `string-compare`, `compare-by`

### Interface Implementations

- `clojure.lang.Sorted` — native `subseq`/`rsubseq` on ordered-set and ordered-map
- `clojure.core.reducers/CollFold` — chunked parallel fold; ordered-set/map and compatible tree-backed types split into larger chunks before delegating to `r/fold`
- `clojure.lang.IHashEq` — correct `hash` for use in hash-based collections
- `java.io.Serializable` — Java serialization support
- `IReduceInit`/`IReduce` — direct tree traversal for fast `reduce`
- Direct `ISeq` implementations (`KeySeq`, `EntrySeq`) replace lazy-seq wrappers
- Tree-rooted seq helpers are explicitly named `node-key-seq`, `node-entry-seq`, and reverse variants

### EDN Tagged Literals

Round-trip serialization via `data_readers.clj`: `#ordered/set`, `#ordered/map`, `#ordered/interval-set`, `#ordered/interval-map`, `#ordered/range-map`, `#ordered/priority-queue`, `#ordered/multiset`.

### Performance

- **Parallel set operations** via ForkJoinPool with operation-specific root thresholds (`65,536-131,072`), `65,536` recursive thresholds, and `64` sequential cutoff
- **Primitive node types** (`LongKeyNode`, `DoubleKeyNode`) — unboxed key storage
- **Primitive lookup fast path** — `long-ordered-set` bypasses `Comparator` dispatch
- Fold benchmarking now includes a non-trivial frequency-map workload comparing `ordered-set` fold against `hash-set` reduce, `sorted-set` fold/reduce, and `data.avl` fold/reduce

See [benchmarks](doc/benchmarks.md) and [performance analysis](doc/perf-analysis.md) for numbers.

### Build

- Added `deps.edn` with aliases: `:dev`, `:test`, `:bench`, `:bench-simple`, `:bench-range-map`, `:bench-parallel`

### Bug Fixes

- `SortedSet.tailSet` now returns elements >= x (was exclusive)
- `SortedSet.subSet` now returns elements >= from, < to
- Interval tree construction uses sequential reduce (parallel fold lost dynamic binding for node allocator at >2048 elements)

### Breaking Changes

- **Removed** `mutable-ordered-set`, `mutable-ordered-map`, `mutable-interval-set`, `mutable-interval-map`
- **Removed** `transient`/`persistent!` support (path-copying made it a no-op)

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
