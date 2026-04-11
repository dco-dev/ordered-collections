# Changelog

## [0.2.1-SNAPSHOT] - unreleased

### New Collection Types

- **StringRope** (`string-rope`) — persistent chunked text sequence backed by
  `java.lang.String` chunks. Implements `java.lang.CharSequence` so it drops
  into `re-find`/`re-seq`/`re-matches`, `clojure.string`, and any Java API
  expecting text. Equality with `String` is content-based and hash-compatible.
  `#string/rope "…"` tagged literal with EDN round-trip. Constructor:
  `string-rope` / `string-rope-concat`. At 100K+ characters, up to
  ~35x faster than `String` on repeated structural edits.
- **ByteRope** (`byte-rope`) — persistent chunked binary sequence backed by
  `byte[]` chunks. Unsigned byte semantics (0–255 as long). Unsigned
  lexicographic `Comparable` via `Arrays.compareUnsigned`. `#byte/rope "hex"`
  tagged literal. Constructor: `byte-rope` / `byte-rope-concat`. Extras:
  `byte-rope-bytes`, `byte-rope-hex`, `byte-rope-write`,
  `byte-rope-input-stream`, `byte-rope-get-byte`/`-short`/`-int`/`-long`
  (plus `-le` variants), `byte-rope-index-of`, and a streaming
  `byte-rope-digest` that feeds chunks through `java.security.MessageDigest`
  without materialization.

### Rope Family Improvements

- **Flat-mode optimization** for all three rope variants (`rope`,
  `string-rope`, `byte-rope`). When a rope's element count is at or below
  the per-variant flat threshold (1024 elements, characters, or bytes),
  the rope stores its content as a bare concrete collection
  (`PersistentVector`, `java.lang.String`, or `byte[]`) directly in the
  root field, skipping the tree wrapper entirely. Reads dispatch straight
  to the native type with zero indirection overhead; edits that grow the
  rope past the threshold transparently promote to chunked tree form;
  transients demote back to flat form at `persistent!` time when the
  result fits. Memory for small ropes is essentially identical to the
  natural baseline (1.00x vs `PersistentVector` / `String` / `byte[]`).
- **Per-variant Chunk Size Invariant (CSI)** — each rope variant now
  declares its own `+target-chunk-size+` / `+min-chunk-size+` constants
  and binds them via its `with-tree` macro into the kernel's new
  `*target-chunk-size*` / `*min-chunk-size*` dynamic vars. Tuned via
  `lein bench-rope-tuning`: all three variants default to 1024/512
  (up from the historical 256/128). At 500K elements, generic Rope
  gains +41% nth, +38% split, and 5x concat; StringRope and ByteRope
  improve on every measured operation.
- **`kernel/chunk.clj`** — extracted from `kernel/rope.clj`. Holds the
  `PRopeChunk` protocol extensions for the three chunk backends
  (`APersistentVector`, `String`, `byte[]`) as a standalone kernel
  submodule. `kernel/rope.clj` drops from 1237 to 1155 lines and is now
  purely the rope tree algebra.
- **StringRope internals refactor** — `with-tree` macro replaces 16+
  copies of the `(binding [*t-join* alloc] ...)` form; `->StringRope*`
  helper replaces 35+ copies of the 6-arg constructor; `coll->str` and
  `coll->tree-root` coercion helpers deduplicate scattered dispatch
  logic in the PRope method bodies.
- **`rope-splice-inplace`** fused single-chunk splice path avoids an
  intermediate `chunk-splice` allocation on the overflow path via
  `chunk-splice-split`.
- **Cursor cache** on StringRope and ByteRope uses direct
  `^:volatile-mutable ^int` fields instead of a boxed `Object[]`.

### Benchmarks and Tooling

- **`lein bench-rope-tuning`** fully rewritten to sweep chunk sizes
  across all three rope variants (`Rope` vs `Vector`, `StringRope` vs
  `String`, `ByteRope` vs `byte[]`). Reports per-operation speedups and
  a geomean score for ranking. Supports
  `--variant rope|string-rope|byte-rope`.
- **`lein bench`** (`bench_runner.clj`) full suite gains N=1000 and
  N=5000 cardinalities alongside the existing 10K/100K/500K. The 1K
  column exercises flat-mode for all three rope variants; the 5K
  column exercises the smallest tree-mode regime.
- **`lein bench-simple`** gains a `:rope` category (alongside the
  existing `:string-rope` and new `:byte-rope` categories) and adds
  N=5000 to the shared size defaults.
- **Memory test** (`memory_test.clj`) gains `string-rope-memory` and
  `byte-rope-memory` deftests plus a new rope family section in the
  summary report table, showing all three variants against their
  natural baselines.

### Documentation

- [Cookbook](doc/cookbook.md) restructured with six rope recipes at the
  front (text editor, regex on StringRope, bulk sequence assembly, binary
  protocol, streaming digest, undo history). Duplicate section
  numbering cleaned up; existing collection recipes renumbered.
- [Ropes](doc/ropes.md) gains a "Chunk Abstraction: One Kernel, Many
  Backends" section explaining `PRopeChunk` and `kernel/chunk.clj`, a
  "Specialized Ropes" section with per-variant design and examples,
  and a variant-picker table. API section now covers all three
  variants with the shared `PRope` surface up front.
- [Collections API](doc/collections-api.md) gains full StringRope and
  ByteRope sections with constructors, interfaces, and per-variant
  operations.

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
