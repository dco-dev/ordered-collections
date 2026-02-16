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

*Benchmarks run using [Criterium](https://github.com/hugoduncan/criterium) on JDK 25, Apple M1 Pro.*

### Construction (build from N elements)

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 17 ms | 28 ms | **18 ms** |
| 100,000 | 248 ms | 390 ms | **212 ms** |
| 500,000 | 890 ms | 604 ms | **371 ms** |

**Verdict**: At small sizes, roughly equivalent. **At scale (N=500K), ordered-collections is 2.4x faster than sorted-set and 1.6x faster than data.avl** due to parallel construction via `r/fold` and fast parallel union.

### Incremental Insert (assoc/conj one at a time)

| N | sorted-map | data.avl | ordered-map | long-ordered-map |
|---|------------|----------|-------------|------------------|
| 10,000 | ~8 ms | ~6 ms | ~10 ms | ~5 ms |
| 100,000 | ~120 ms | ~90 ms | ~150 ms | ~70 ms |

**Verdict**: With the default heterogeneous comparator, data.avl is faster. However, **with primitive-specialized types (`long-ordered-map`, `string-ordered-map`) or explicit comparators, ordered-collections matches or beats data.avl**. The default comparator trades performance for flexibility (supports mixed types like `[1 "two" :three]`).

### Lookup (10,000 random lookups)

| N | sorted-set | ordered-set | long-ordered-set |
|---|------------|-------------|------------------|
| 100,000 | 2.93ms | 2.80ms | **2.11ms** |

**Verdict**: Generic `ordered-set` is on par with `sorted-set`. `long-ordered-set` is **28% faster** due to primitive comparator.

### Set Operations (union/intersection/difference)

Comparing ordered-collections to data.avl (which falls back to clojure.set):

**At N=500,000 (two sets with 50% overlap):**

| Operation | sorted-set | data.avl | ordered-set | vs sorted-set | vs data.avl |
|-----------|------------|----------|-------------|---------------|-------------|
| Union | 288ms | 371ms | **38ms** | **7.6x** | **10x** |
| Intersection | 217ms | 176ms | **35ms** | **6.2x** | **5x** |
| Difference | 211ms | 144ms | **29ms** | **7.3x** | **5x** |

**Verdict**: **ordered-collections is 5-10x faster** at scale due to Adams' divide-and-conquer algorithm with fork-join parallelism (for collections above 65,536 combined elements).

### Parallel Fold (r/fold)

| N | sorted-set | data.avl | ordered-set | vs sorted | vs avl |
|---|------------|----------|-------------|-----------|--------|
| 500,000 | 60.3ms | 13.0ms | **4.1ms** | **14.8x** | **3.2x** |

**Verdict**: **ordered-collections is 3.2x faster than data.avl** and 14.8x faster than sorted-set for parallel fold using tree-based fork-join. Both data.avl and sorted-set fall back to sequential reduction.

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

### Primitive Specialization (ordered-collections only)

```clojure
;; 28% faster lookups for Long keys
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
;; Run the Criterium benchmark suite (statistically valid results)
(require '[com.dean.ordered-collections.criterium-bench :as cb])

;; Quick suite (~10 minutes)
(cb/run-quick)

;; Full suite with statistical analysis (~45-60 minutes)
(cb/run-full)

;; Individual comparisons
(cb/with-quick-bench
  (cb/compare-set-operations 100000))
```

Memory measurement requires `clj-memory-meter`:

```clojure
(require '[com.dean.ordered-collections.memory-test :as mem])
(mem/run-memory-tests)
```
