# Performance Optimization Plan

## Implemented Optimizations

### 1. Specialized Comparators (DONE)
Added `long-ordered-set` and `long-ordered-map` that use `Long.compare` instead of `clojure.core/compare`.

**Results:**
- Lookup: 25% faster (16.2ms → 12.1ms for 10K queries on 100K elements)
- Closes gap with sorted-set from 47% slower to only 10% slower

**Usage:**
```clojure
(require '[com.dean.ordered-collections.core :as oc])

;; For Long/Integer keys
(def s (oc/long-ordered-set (range 100000)))
(def m (oc/long-ordered-map (map #(vector % %) (range 100000))))
```

### 2. Efficient Direct Seq Types (DONE)
Added `KeySeq`, `EntrySeq`, `KeySeqReverse`, `EntrySeqReverse` that implement `ISeq` directly without lazy-seq or `map` wrapper overhead.

**Results:**
- Direct reduce on collection: **2.1x faster** than sorted-set
- Reduce over seq: **1.4x faster** than sorted-set (seq types implement IReduceInit)
- Seq iteration (first/next): within 7% of sorted-set

**Implementation:**
- Direct `clojure.lang.ISeq` implementation with enumerator-based traversal
- `IReduceInit` and `IReduce` for fast reduce operations on seqs
- `Counted` for O(1) count when size is known
- `Iterable` for `RT.toArray` compatibility

### 3. Parallel Set Operations (DONE)
Set operations (union, intersection, difference) use fork-join parallelism via the divide-and-conquer algorithm from Blelloch et al.

**Results:**
- Union: 7.8x faster than clojure.set
- Intersection: 9.0x faster
- Difference: 7.7x faster

**Algorithm:** Split B at root(A), recurse on left/right subtrees in parallel, join results. See `algorithms.md` for details.

### 4. Parallel Construction (DONE)
Batch construction via `r/fold` + `union` achieves O(n) work vs O(n log n) sequential insertion.

**Results:**
- `ordered-set`: 25% faster than `sorted-set` for batch construction
- `ordered-map`: matches `sorted-map` (was 2.2x slower before optimization)

### 5. Parallel Map Merge (DONE)
Added `ordered-merge-with` for fast map merging with conflict resolution.

**Results:**
- ~5x faster than `clojure.core/merge-with` for large ordered-maps

### 6. Interval Tree Construction Fix (DONE)
Fixed interval-set and interval-map construction to use `reduce` instead of `r/fold`.

**Reason:**
- `r/fold` runs in parallel worker threads that don't inherit dynamic bindings
- The `*t-join*` binding (which selects `IntervalNode` vs `SimpleNode`) was lost in workers
- This caused `ClassCastException: SimpleNode cannot be cast to IAugmentedNode` for collections >2048 elements

### 7. Range Map with Guava Semantics (DONE)
Implemented `range-map` compatible with Guava's TreeRangeMap:
- `assoc`: inserts range, carving out overlaps (does NOT coalesce)
- `assoc-coalescing`: inserts and merges adjacent same-value ranges
- `get-entry`: returns `[range value]` for point lookup
- `range-remove`: removes all mappings in a range

**Performance:** O(k log n) where k = overlapping ranges. See `algorithms.md` for carving/coalescing algorithms.

---

## Removed/Rejected Optimizations

### Transient API (REMOVED)
Previously added `transient`/`persistent!` support, but **removed** because:
- The implementation only saved wrapper allocation, not tree node allocation
- Tree operations still did full path-copying on every mutation
- Added API complexity without meaningful performance benefit
- True transient optimization would require mutable tree nodes with ownership tracking

**Future consideration:** A proper transient implementation would need:
- Mutable node types with ownership bits
- Copy-on-write when shared
- Thread-local ownership tracking
- Significant implementation complexity

### ArrayLeaf Optimization (REMOVED)
Previously experimented with `ArrayLeaf` for cache-friendly leaf storage, but **removed** because:
- Added code complexity
- Benefits were marginal in practice
- Interacted poorly with other optimizations

---

## Current Performance Profile

Based on benchmarks at N=100,000:

### Where We're Faster

| Operation | vs sorted-* | Why |
|-----------|-------------|-----|
| Batch construction | **25% faster** (sets) | Parallel fold + union |
| Direct reduce | **2.1x faster** | IReduceInit with tree traversal |
| Reduce over seq | **27% faster** | IReduceInit on seq types |
| First/last | **~92,000x faster** | O(log n) vs O(n) |
| Set operations | **6-9x faster** | Parallel divide-and-conquer |
| Count on seq | **O(1) vs O(n)** | Counted seqs track size |
| nth access | **O(log n) vs O(n)** | Subtree weights |

### Unique Capabilities

Operations not available in sorted-set/sorted-map:
- `nth` positional access: O(log n)
- `rank` (ranked-set only): O(log n)
- Parallel `r/fold`: ~2x speedup on large collections
- Interval queries: O(log n + k)
- Fuzzy/nearest lookup: O(log n)
- Range map with carving/coalescing
- Segment tree range aggregates

---

## Future Optimization Strategies

### Tier 1: Code Quality (In Progress)

#### 1.1 Collection Type Consolidation
**Status:** Planned (see `.claude/plans/squishy-leaping-oasis.md`)
**Impact:** ~700-800 lines removed, improved maintainability
**Effort:** Medium

Reduce duplicated code across 6 collection types using compile-time macros:
- `ordered_set.clj`, `ordered_map.clj`
- `interval_set.clj`, `interval_map.clj`
- `fuzzy_set.clj`, `fuzzy_map.clj`

All share ~80% identical interface implementations. Factor into composable macros.

### Tier 2: Medium Impact, Medium Risk

#### 2.1 Primitive-Specialized Collections
**Impact:** 30-50% faster for numeric keys/values
**Effort:** High

Create specialized versions with unboxed primitives:

```clojure
(deftype LongNode [^long k v l r ^long x]
  IBalancedNode (x [_] x)
  INode
  (k [_] k)
  (v [_] v)
  (l [_] l)
  (r [_] r))
```

Benefits:
- No boxing overhead
- Primitive comparison (1 instruction vs method call)
- Better memory layout

#### 2.2 Lazy/Batched Rebalancing
**Impact:** 20-30% faster sequential insert
**Effort:** Medium

Defer rebalancing for small imbalances:

```clojure
(defn stitch-wb-lazy [create k v l r]
  (let [imbalance (/ (max lw rw) (inc (min lw rw)))]
    (if (< imbalance +lazy-threshold+)  ;; e.g., 2.5
      (create k v l r)  ;; Skip rotation
      (stitch-wb create k v l r))))  ;; Full rebalance
```

Trade-off: May affect worst-case bounds. Requires analysis.

#### 2.3 Reduce Tree Depth via B-tree Hybrid
**Impact:** 20% faster lookup
**Effort:** High

Use nodes with 4-8 children (B-tree style):

```clojure
(deftype BTreeNode [^objects keys ^objects vals ^objects children ^int n])
```

Benefits:
- Fewer levels: log₄(n) vs log₂(n)
- Better cache utilization per node access

Trade-offs:
- More complex implementation
- May hurt insert/delete performance
- Harder to maintain weight-balance invariant

### Tier 3: Lower Impact or Experimental

#### 3.1 Path Compression
**Impact:** 10% faster for sparse trees
**Effort:** Medium

Collapse chains of single-child nodes.

#### 3.2 SIMD-Friendly Binary Search
**Impact:** 5-10% faster internal search
**Effort:** Low

Use `java.util.Arrays/binarySearch` which may leverage JVM optimizations.

---

## Implementation Priority

### Phase 1: Code Quality
1. Collection type consolidation (macros)
2. Remove dead code paths
3. Improve test coverage

### Phase 2: Performance (If Needed)
1. Primitive-specialized `long-ordered-set` improvements
2. Lazy rebalancing experiments
3. Profile-guided optimization for hot paths

### Phase 3: Advanced (Research)
1. B-tree hybrid experiments
2. True transient implementation with mutable nodes
3. SIMD exploration

---

## Benchmarking

For each optimization:

1. **Micro-benchmark** the specific operation
2. **Macro-benchmark** full use cases
3. **Memory profile** to catch regressions
4. **Compare against** sorted-set, data.avl, Scala TreeSet

Key benchmarks:
```clojure
(require '[criterium.core :as crit])

;; Lookup
(crit/bench (get my-set some-key))

;; Sequential insert
(crit/bench (reduce conj (ordered-set) data))

;; Batch construction
(crit/bench (ordered-set data))

;; Set operations
(crit/bench (union s1 s2))

;; Iteration
(crit/bench (reduce + my-set))
```

---

## Risk Assessment

| Optimization | Risk | Mitigation |
|--------------|------|------------|
| Collection consolidation | Low | Macro-only, tests verify equivalence |
| Primitive specialization | Low | Additive, doesn't change core |
| Lazy rebalancing | Medium | May affect worst-case bounds |
| B-tree hybrid | High | Major architecture change |
| True transients | High | Complex ownership tracking |

---

## Documentation Status

Documentation has been significantly improved:

| Document | Status |
|----------|--------|
| `README.md` | Updated with performance claims, examples |
| `algorithms.md` | Comprehensive coverage of all algorithms |
| `when-to-use.md` | Decision matrix, workload recommendations |
| `cookbook.md` | Practical examples combining data structures |
| `zorp-example.md` | Extended case study |
| API docstrings | Updated in `core.clj` |
