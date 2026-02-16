# Competitive Analysis: ordered-collections

This document compares `ordered-collections` against the primary alternatives in the Clojure ecosystem: `clojure.core/sorted-set`, `clojure.core/sorted-map`, and `clojure.data.avl`.

## Executive Summary

| Aspect | ordered-collections | clojure.core | clojure.data.avl |
|--------|---------------------|--------------|------------------|
| **Tree Type** | Weight-balanced | Red-black | AVL |
| **Set Operations** | O(m log(n/m+1)) parallel | O(n) via clojure.set | O(m log(n/m+1)) |
| **O(log n) nth/rank** | Yes | No | Yes |
| **O(log n) first/last** | Yes | O(n) | Yes |
| **Interval Trees** | Yes | No | No |
| **Fuzzy Lookup** | Yes | No | No |
| **Memory/element** | ~64 bytes | ~61 bytes | ~64 bytes |
| **Parallel fold** | Yes | No | No |

## Memory Overhead (Measured)

From `memory_test.clj` at N=100,000:

| Collection | Bytes/Element | vs sorted-set |
|------------|---------------|---------------|
| sorted-set | 60.6 | 1.00x |
| data.avl sorted-set | 64.0 | 1.06x |
| **ordered-set** | 64.0 | 1.06x |
| long-ordered-set | 88.0 | 1.45x |

| Collection | Bytes/Entry | vs sorted-map |
|------------|-------------|---------------|
| sorted-map | 84.6 | 1.00x |
| data.avl sorted-map | 88.0 | 1.04x |
| **ordered-map** | 88.0 | 1.04x |

**Takeaway**: Memory overhead is minimal (4-6%) compared to core sorted collections. Both ordered-collections and data.avl use the same amount of memory.

## Performance Characteristics

### Set Operations

Both ordered-collections and data.avl implement Adams' divide-and-conquer algorithms:

```
union(T1, T2):
  Split T1 at T2.root → (L1, _, R1)
  return join(T2.root, union(L1, T2.left), union(R1, T2.right))
```

**Complexity**: O(m log(n/m + 1)) where m ≤ n

This is asymptotically optimal and **dramatically faster** than `clojure.set/union` which is O(n).

ordered-collections adds **parallel execution** via ForkJoinPool for trees exceeding 65,536 combined elements, providing additional speedup on multi-core systems.

### Indexed Access

Both ordered-collections and data.avl track subtree sizes, enabling:
- `(nth coll i)` in O(log n) instead of O(n)
- `(rank coll x)` to find element position
- `(split-at coll i)` to split at index

Core sorted collections require O(n) traversal for positional access.

### First/Last Element

| Operation | clojure.core | ordered-collections |
|-----------|--------------|---------------------|
| `(first coll)` | O(1) | O(1) |
| `(last coll)` | **O(n)** | **O(log n)** |

For a 1M element set, `(last sorted-set)` scans the entire collection. ordered-collections uses `java.util.SortedSet.last()` which traverses only log₂(n) ≈ 20 nodes.

## Feature Comparison with data.avl

| Feature | ordered-collections | data.avl |
|---------|---------------------|----------|
| `split-key` | ✓ | ✓ |
| `split-at` | ✓ | ✓ |
| `subrange` | ✓ | ✓ |
| `nearest` | ✓ | ✓ |
| `nth` / positional access | ✓ | ✓ |
| `rank-of` | ✓ | ✓ |
| Parallel set operations | ✓ | ✗ |
| Parallel `r/fold` | ✓ | ✗ |
| Interval trees | ✓ | ✗ |
| Fuzzy lookup | ✓ | ✗ |
| Range maps | ✓ | ✗ |
| Priority queues | ✓ | ✗ |
| Segment trees | ✓ | ✗ |
| Multisets | ✓ | ✗ |
| Serialization | ✓ | ✓ |
| ClojureScript | ✗ | ✓ |
| Transient support | ✗ | ✓ |

## When to Use Each Library

### Use clojure.core sorted collections when:
- You need the smallest possible dependency footprint
- Memory is more important than specialized operations
- You don't need fast `last`, positional access, or set operations

### Use clojure.data.avl when:
- You need ClojureScript compatibility
- You need transient/mutable builders for construction
- You only need the core sorted map/set functionality

### Use ordered-collections when:
- You need interval trees, fuzzy sets, or other specialized collections
- You want parallel set operations and parallel fold
- You're building applications with heavy set algebra
- You need range maps, segment trees, or priority queues

## Tree Algorithm

ordered-collections uses weight-balanced trees with Hirai-Yamamoto parameters (δ=3, γ=2). This is the same algorithm used in Haskell's `Data.Set` and `Data.Map`.

**Academic Foundation:**
- Adams, S. (1992). "Implementing Sets Efficiently in a Functional Language"
- Hirai, Y. & Yamamoto, K. (2011). "Balancing Weight-Balanced Trees" [JFP 21(3):287-307]

**Why weight-balanced trees?**
1. Simple invariant (size ratio) enables clean persistent implementations
2. Adams' set algorithms require only the `join` operation to be tree-specific
3. Subtree sizes are already maintained, enabling O(log n) positional access

## Specialized Collections

ordered-collections provides several collections not available elsewhere:

### Interval Trees
Augmented trees with max-endpoint tracking for O(k + log n) overlap queries:
```clojure
(def events (interval-set [[0 10] [5 15] [20 30]]))
(overlapping events [8 12])  ;=> [[0 10] [5 15]]
```

### Fuzzy Sets/Maps
Approximate matching with configurable distance functions:
```clojure
(def fs (fuzzy-set [1.0 2.0 3.0 10.0]))
(fs 2.1)  ;=> 2.0 (nearest match)
```

### Range Maps
Non-overlapping range-to-value mappings with automatic coalescing:
```clojure
(def rm (range-map {[0 10] :a [20 30] :b}))
(rm 5)   ;=> :a
(rm 15)  ;=> nil
```

### Segment Trees
O(log n) range aggregate queries:
```clojure
(def st (sum-tree {0 10, 1 20, 2 30, 3 40}))
(query st 1 3)  ;=> 90
```

## Honest Limitations

1. **No ClojureScript support**: JVM-only due to Java interop
2. **No transient builders**: Construction is persistent-only
3. **Slightly higher memory**: 6% more than core sorted collections
4. **Default comparator overhead**: `clojure.core/compare` has type dispatch overhead; use `long-ordered-set` for primitive keys

## References

1. Adams, S. (1992). "Implementing Sets Efficiently in a Functional Language". CSTR 92-10.
2. Hirai, Y. & Yamamoto, K. (2011). "Balancing Weight-Balanced Trees". JFP 21(3):287-307.
3. Blelloch, G., Ferizovic, D., & Sun, Y. (2016). "Just Join for Parallel Ordered Sets". SPAA '16.
4. [clojure.data.avl documentation](https://github.com/clojure/data.avl)
5. [Haskell containers documentation](https://hackage.haskell.org/package/containers)

---

*Analysis based on measured benchmarks. Memory tests at N=100,000 on JDK 25.*
