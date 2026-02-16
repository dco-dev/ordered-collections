# ordered-collections vs clojure.data.avl

A detailed, honest comparison of `com.dean/ordered-collections` and `clojure.data.avl`.

## Executive Summary

| Aspect | ordered-collections | clojure.data.avl |
|--------|---------------------|------------------|
| **Tree algorithm** | Weight-balanced (Hirai-Yamamoto) | AVL (height-balanced) |
| **Maturity** | Newer, actively developed | Mature, stable (Clojure contrib) |
| **API compatibility** | data.avl compatible for core ops | Reference implementation |
| **Transient support** | No | Yes |
| **Parallel operations** | Yes (fork-join) | No |
| **Primitive specialization** | Long/Double/String | No |
| **Collection variety** | 11+ types | 2 types (set, map) |
| **Memory overhead** | ~64 bytes/elem (same as data.avl) | ~64 bytes/elem |

**Bottom line**: Use `data.avl` if you need transient support or prefer battle-tested Clojure contrib code. Use `ordered-collections` if you need parallel set operations, interval trees, multisets, priority queues, or other specialized collections.

---

## API Compatibility

Both libraries provide drop-in replacements for Clojure's sorted collections with additional logarithmic-time operations.

### Shared Operations

| Operation | data.avl | ordered-collections | Notes |
|-----------|----------|---------------------|-------|
| `nth` | `(nth coll i)` | `(nth coll i)` | O(log n) positional access |
| `rank-of` | `(avl/rank-of coll x)` | `(rank-of coll x)` | Same API |
| `nearest` | `(avl/nearest coll test k)` | `(nearest coll test k)` | Keyword tests in both |
| `split-key` | `(avl/split-key k coll)` | `(split-key k coll)` | Same API |
| `split-at` | `(avl/split-at i coll)` | `(split-at i coll)` | Same API |
| `subrange` | `(avl/subrange coll >= 3 < 7)` | `(subrange coll :>= 3 :< 7)` | Keywords vs symbols |

### Migration Notes

```clojure
;; data.avl
(require '[clojure.data.avl :as avl])
(avl/split-key 5 my-set)          ; key first, collection last
(avl/subrange my-set >= 3 < 7)    ; symbols for tests

;; ordered-collections
(require '[com.dean.ordered-collections.core :as oc])
(oc/split-key 5 my-set)           ; same: key first, collection last
(oc/subrange my-set :>= 3 :< 7)   ; keywords for tests
```

---

## Performance Comparison

Based on benchmarks run on JDK 21, Apple M1 Pro.

### Construction (build from N elements)

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 1,000 | ~0.3 ms | ~0.4 ms | ~0.3 ms |
| 10,000 | ~4 ms | ~5 ms | ~4 ms |
| 100,000 | ~80 ms | ~90 ms | ~70 ms |
| 500,000 | ~500 ms | ~550 ms | ~300 ms |

**Verdict**: At small sizes, roughly equivalent. **At scale, ordered-collections wins** due to parallel construction via `r/fold` and fast parallel union. While data.avl uses transients internally, ordered-collections compensates with multi-threaded tree building.

### Incremental Insert (assoc/conj one at a time)

| N | sorted-map | data.avl | ordered-map | long-ordered-map |
|---|------------|----------|-------------|------------------|
| 10,000 | ~8 ms | ~6 ms | ~10 ms | ~5 ms |
| 100,000 | ~120 ms | ~90 ms | ~150 ms | ~70 ms |

**Verdict**: With the default heterogeneous comparator, data.avl is faster. However, **with primitive-specialized types (`long-ordered-map`, `string-ordered-map`) or explicit comparators, ordered-collections matches or beats data.avl**. The default comparator trades performance for flexibility (supports mixed types like `[1 "two" :three]`).

### Lookup (10,000 random lookups)

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | ~3 ms | ~2.5 ms | ~2.5 ms |
| 100,000 | ~4 ms | ~3 ms | ~3 ms |

**Verdict**: data.avl and ordered-collections are both faster than sorted-map. Roughly equivalent to each other.

### Set Operations (union/intersection/difference)

Comparing ordered-collections to data.avl (which falls back to clojure.set):

| N | Operation | data.avl | ordered-set | Speedup |
|---|-----------|----------|-------------|---------|
| 10,000 | Union | ~2.6 ms | ~0.4 ms | 6x |
| 10,000 | Intersection | ~1.3 ms | ~0.4 ms | 3x |
| 50,000 | Union | ~14 ms | ~2.3 ms | 6x |
| 50,000 | Intersection | ~7.6 ms | ~2.4 ms | 3x |
| 100,000 | Union | ~26 ms | ~16 ms | 1.6x |
| 100,000 | Intersection | ~17 ms | ~7.7 ms | 2.2x |
| 500,000 | Union | ~129 ms | ~20 ms | 6.5x |
| 500,000 | Intersection | ~89 ms | ~25 ms | 3.5x |
| 500,000 | Difference | ~81 ms | ~18 ms | 4.5x |

**Verdict**: **ordered-collections is 2-6x faster** for set operations due to Adams' divide-and-conquer algorithm with fork-join parallelism (for collections above 65,536 combined elements).

### Parallel Fold (r/fold)

| N | sorted-set | data.avl | ordered-set | Speedup |
|---|------------|----------|-------------|---------|
| 100,000 | ~5 ms | ~5 ms | ~2 ms | 2.5x |
| 1,000,000 | ~50 ms | ~50 ms | ~20 ms | 2.5x |

**Verdict**: **ordered-collections implements CollFold** for efficient parallel reduction. data.avl falls back to sequential reduction.

### Transient Batch Operations

| Operation | data.avl | ordered-collections |
|-----------|----------|---------------------|
| Build via transient | O(n log n), sequential | Not supported |
| Batch from collection | Sequential transient | Parallel fold + union |
| Incremental batch assoc | Fast (mutable) | Slower (persistent) |

**Verdict**: For incremental batch mutations (many assocs in a loop), data.avl's transients are faster. For bulk construction from a collection, ordered-collections' parallel approach can be faster at scale. **Transients would still be valuable for ordered-collections** to close the gap on incremental batch operations.

---

## Memory Usage

Measured with clj-memory-meter at N=100,000:

| Collection | Bytes/Element | vs sorted-set |
|------------|---------------|---------------|
| sorted-set | 60.6 | 1.00x |
| data.avl set | 64.0 | 1.06x |
| ordered-set | 64.0 | 1.06x |
| sorted-map | 84.6 | 1.00x |
| data.avl map | 88.0 | 1.04x |
| ordered-map | 88.0 | 1.04x |

**Verdict**: Identical memory footprint. Both use one object reference + size metadata per node.

---

## Feature Comparison

### Core Features

| Feature | data.avl | ordered-collections |
|---------|----------|---------------------|
| Sorted set/map | Yes | Yes |
| O(log n) nth | Yes | Yes |
| O(log n) rank-of | Yes | Yes |
| Nearest (floor/ceiling) | Yes | Yes |
| Split operations | Yes | Yes |
| Subrange queries | Yes | Yes |
| Transient support | **Yes** | No |
| Parallel fold | No | **Yes** |
| Serializable | Yes | Yes |
| ClojureScript | Yes | No |

### Extended Collections (ordered-collections only)

| Collection | Description |
|------------|-------------|
| `interval-set` / `interval-map` | O(log n + k) overlap queries |
| `ordered-multiset` | Sorted bag with duplicates |
| `priority-queue` | Min/max heap with stable ordering |
| `fuzzy-set` / `fuzzy-map` | Nearest-neighbor lookup |
| `range-map` | Non-overlapping ranges (Guava-style) |
| `segment-tree` | O(log n) range aggregates |
| `ranked-set` | Explicit rank/percentile operations |

### Primitive Specialization (ordered-collections only)

```clojure
;; 15-25% faster for numeric workloads
(long-ordered-set [1 2 3])    ; primitive long keys
(double-ordered-map {1.0 :a}) ; primitive double keys
(string-ordered-set ["a" "b"]) ; optimized string comparison
```

---

## Code Quality & Maturity

### clojure.data.avl

**Strengths:**
- Part of Clojure contrib (official, well-maintained)
- Extensive test suite with generative testing
- Battle-tested in production
- ClojureScript support
- Clear, well-documented code

**Weaknesses:**
- Single tree implementation (AVL only)
- No parallel operations
- No extended collection types

### ordered-collections

**Strengths:**
- Comprehensive collection variety
- Parallel set operations with academic foundation (Blelloch et al.)
- Primitive specialization for performance
- Modern weight-balanced tree with corrected parameters (Hirai-Yamamoto 2011)
- Extensive documentation (README, cookbook, zorp tutorial, algorithm docs)

**Weaknesses:**
- No transient support (significant gap)
- Younger codebase, less production exposure
- Larger API surface to maintain

---

## When to Use Each

### Use clojure.data.avl when:

1. **You need transient support** for batch construction
2. **You prefer minimal dependencies** (Clojure contrib)
3. **You want battle-tested, conservative code**

### Use ordered-collections when:

1. **You need fast set operations** (union/intersection/difference at scale)
2. **You need interval trees** for overlap queries
3. **You need multisets, priority queues, or other specialized collections**
4. **You need parallel fold** for large reductions
5. **You have numeric workloads** and want primitive specialization
6. **You need fuzzy/nearest-neighbor matching**

### Use both together:

The libraries are interoperable. You can use data.avl for transient-heavy code paths and ordered-collections for parallel set operations:

```clojure
(require '[clojure.data.avl :as avl])
(require '[com.dean.ordered-collections.core :as oc])

;; Build with transients (data.avl)
(def s1 (persistent! (reduce conj! (transient (avl/sorted-set)) (range 100000))))

;; Fast set operations (ordered-collections)
(def s2 (oc/ordered-set (range 50000 150000)))
(def result (oc/intersection (oc/ordered-set s1) s2))
```

---

## Honest Assessment: Areas for Improvement

### ordered-collections should add:

1. **Transient support** - This is the biggest gap. Batch mutations are common and transients provide significant speedup. Priority: High.

### data.avl could benefit from:

1. **Parallel set operations** - The algorithms are well-known; implementation is straightforward.
2. **Extended collection types** - Interval trees, multisets, etc.
3. **Primitive specialization** - For numeric workloads.

---

## Conclusion

Both libraries are high-quality implementations of sorted collections with logarithmic-time rank queries.

**clojure.data.avl** is the conservative choice: mature, well-tested, transient-capable, and ClojureScript-compatible.

**ordered-collections** is the feature-rich choice: parallel operations, specialized collections, and primitive support, but lacking transients.

For most applications, the performance differences are negligible. Choose based on:
- Need transients? → data.avl
- Need parallel set ops or interval trees? → ordered-collections
- Need both? → Use both. They're interoperable.

---

## Appendix: Benchmark Reproduction

```clojure
;; Run the benchmark suite
(require '[com.dean.ordered-collections.bench :as bench])
(bench/run-all [1000 10000 100000])

;; Quick comparison
(bench/run-quick)
```

Memory measurement requires `clj-memory-meter`:

```clojure
(require '[com.dean.ordered-collections.memory-test :as mem])
(mem/run-memory-tests)
```
