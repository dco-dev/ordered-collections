# Competitive Analysis: ordered-collections

This document compares `ordered-collections` against the primary alternatives in the Clojure ecosystem: `clojure.core/sorted-set`, `clojure.core/sorted-map`, and `clojure.data.avl`.

## Executive Summary

| Aspect | ordered-collections | clojure.core | clojure.data.avl |
|--------|---------------------|--------------|------------------|
| **Tree type** | Weight-balanced | Red-black | AVL |
| **Set operations** | O(m log(n/m+1)) | O(n) via clojure.set | O(m log(n/m+1)) |
| **O(log n) nth/rank** | Yes | No | Yes |
| **O(log n) last** | Yes | O(n) | O(n) |
| **Parallel fold** | Yes (fork-join) | No | No |
| **Interval trees** | Yes | No | No |
| **Segment trees** | Yes | No | No |
| **Range maps** | Yes | No | No |
| **Fuzzy lookup** | Yes | No | No |
| **Memory/element** | ~64 bytes | ~61 bytes | ~64 bytes |

## Algorithmic Comparison

### Set Operations (Union, Intersection, Difference)

All three libraries support set algebra, but the algorithms differ fundamentally:

**clojure.core** uses `clojure.set/union` etc., which iterate element-by-element, inserting each into the result. This is O(n log n) regardless of overlap between the sets.

**data.avl** and **ordered-collections** both implement Adams' divide-and-conquer algorithm:

```
union(T₁, T₂):
    split T₁ at root(T₂) → (L₁, _, R₁)
    join(union(L₁, left(T₂)), root(T₂), union(R₁, right(T₂)))
```

This gives O(m log(n/m + 1)) where m ≤ n, which is information-theoretically optimal. When one set is much smaller, this is dramatically better than linear.

**ordered-collections** additionally parallelizes the two independent recursive calls via `ForkJoinPool` when the combined tree size exceeds 210,000 elements. In practice this yields 5-13x speedup over sorted-set and 2-10x over data.avl across the range N=10K to N=500K. See [Benchmarks](benchmarks.md) for measurements.

### Positional Access

Both ordered-collections and data.avl track subtree sizes, enabling:
- `(nth coll i)` in O(log n) instead of O(n)
- `(rank-of coll x)` to find element position
- `(split-at i coll)` to split at index

Core sorted collections require O(n) traversal for any positional operation.

### First/Last Element

| Operation | clojure.core | data.avl | ordered-collections |
|-----------|:---:|:---:|:---:|
| `(first coll)` | O(1) | O(1) | O(1) |
| `(last coll)` | **O(n)** | **O(n)** | **O(log n)** |

For a 1M-element set, `(last sorted-set)` scans the entire collection (~8 seconds). ordered-collections traverses ~20 nodes via `java.util.SortedSet.last()`.

### Split and Join

| | clojure.core | data.avl | ordered-collections |
|--|:---:|:---:|:---:|
| `split-key` | — | O(log n) | O(log n) |
| `split-at` | — | O(log n) | O(log n) |
| `join` | — | O(log n) | O(log n) |

Split/join is the foundation for set operations, subrange extraction, and parallel fold. Core sorted collections don't expose these operations.

ordered-collections has lower constant factors for split/join than data.avl (~3-4x faster in practice) because the weight-balanced invariant composes directly with subtree sizes — no height recomputation is needed.

### Parallel Fold

ordered-collections implements `clojure.core.reducers/CollFold` using tree decomposition: split at the root, fold left and right subtrees in parallel via `ForkJoinPool`, combine results.

Both sorted-set and data.avl fall back to sequential reduce. At N=500K, parallel fold is 5-15x faster than sorted-set and 1-5x faster than data.avl, depending on the reduction function.

## Feature Comparison with data.avl

| Feature | ordered-collections | data.avl |
|---------|:---:|:---:|
| `split-key` | ✓ | ✓ |
| `split-at` | ✓ | ✓ |
| `subrange` | ✓ | ✓ |
| `nearest` (floor/ceiling) | ✓ | ✓ |
| `nth` / positional access | ✓ | ✓ |
| `rank-of` | ✓ | ✓ |
| Parallel set operations | ✓ | — |
| Parallel `r/fold` | ✓ | — |
| Interval trees | ✓ | — |
| Segment trees | ✓ | — |
| Fuzzy sets/maps | ✓ | — |
| Range maps | ✓ | — |
| Priority queues | ✓ | — |
| Multisets | ✓ | — |
| EDN serialization | ✓ | ✓ |
| ClojureScript | — | ✓ |
| Transient support | — | ✓ |

### What data.avl has that we don't

**ClojureScript support.** ordered-collections is JVM-only due to Java interop (`java.util.SortedSet`, `ForkJoinPool`, etc.). If you need sorted collections in ClojureScript, data.avl is your option.

**Transient support.** data.avl supports `transient`/`persistent!` for batch mutation. ordered-collections uses persistent construction throughout, mitigated by parallel batch construction which is competitive in practice.

## Memory Overhead

From `memory_test.clj` at N=100,000 (measured with clj-memory-meter):

| Collection | Bytes/Element | vs sorted-set |
|------------|:---:|:---:|
| sorted-set | 60.6 | 1.00x |
| data.avl sorted-set | 64.0 | 1.06x |
| **ordered-set** | 64.0 | 1.06x |

| Collection | Bytes/Entry | vs sorted-map |
|------------|:---:|:---:|
| sorted-map | 84.6 | 1.00x |
| data.avl sorted-map | 88.0 | 1.04x |
| **ordered-map** | 88.0 | 1.04x |

Memory overhead is minimal (4-6%) compared to core sorted collections. ordered-collections and data.avl use the same amount — each node stores one extra field (subtree size/weight) beyond what red-black trees need.

## Specialized Collections

ordered-collections provides several collection types not available elsewhere in the Clojure ecosystem:

### Interval Trees
Augmented trees with max-endpoint tracking for O(log n + k) overlap queries:
```clojure
(def events (oc/interval-set [[0 10] [5 15] [20 30]]))
(events [8 12])  ;=> ([0 10] [5 15])  — intervals overlapping [8,12]
(events 5)       ;=> ([0 10] [5 15])  — intervals containing point 5
```

### Segment Trees
O(log n) range aggregate queries with O(log n) updates:
```clojure
(def st (oc/sum-tree {0 10, 1 20, 2 30, 3 40}))
(oc/query st 1 3)  ;=> 90
```

### Range Maps
Non-overlapping range-to-value mappings with automatic splitting on insert:
```clojure
(def rm (oc/range-map {[0 10] :a [20 30] :b}))
(rm 5)   ;=> :a
(rm 15)  ;=> nil
```

### Fuzzy Sets/Maps
Nearest-neighbor lookup with configurable distance and tiebreaking:
```clojure
(def fs (oc/fuzzy-set [1.0 2.0 3.0 10.0]))
(fs 2.1)  ;=> 2.0
```

## When to Use Each

### Use clojure.core sorted collections when:
- You need zero external dependencies
- You only need basic lookup, insert, delete, and `subseq`
- You don't need fast `last`, positional access, or set algebra

### Use clojure.data.avl when:
- You need ClojureScript compatibility
- You need transient builders for batch construction
- You want nth/rank but don't need intervals, segments, or parallelism

### Use ordered-collections when:
- You need specialized collections (intervals, segments, ranges, fuzzy lookup)
- You want parallel set operations and parallel fold
- You're working with large sorted collections and need fast set algebra
- You need O(log n) `last`, `median`, `percentile`, or `slice`

## Honest Limitations

1. **No ClojureScript support** — JVM-only due to Java interop
2. **No transient builders** — construction is persistent-only (mitigated by parallel batch construction)
3. **Slightly higher memory** — 6% more than core sorted collections
4. **Lookup within 10%** — not meaningfully faster or slower than either competitor for point queries

## References

1. Adams, S. (1992). "Implementing Sets Efficiently in a Functional Language". CSTR 92-10.
2. Hirai, Y. & Yamamoto, K. (2011). "Balancing Weight-Balanced Trees". JFP 21(3):287-307.
3. Blelloch, G., Ferizovic, D., & Sun, Y. (2016). "Just Join for Parallel Ordered Sets". SPAA '16.
4. [clojure.data.avl documentation](https://github.com/clojure/data.avl)
5. [Haskell containers documentation](https://hackage.haskell.org/package/containers)
