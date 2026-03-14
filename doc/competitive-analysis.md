# Competitive Analysis: ordered-collections

Comparison with `clojure.core/sorted-set`, `clojure.core/sorted-map`, and `clojure.data.avl`.

## Summary

| Aspect | ordered-collections | clojure.core | clojure.data.avl |
|--------|---------------------|--------------|------------------|
| **Tree type** | Weight-balanced | Red-black | AVL |
| **Set operations** | O(m log(n/m+1)), parallel | O(n) via clojure.set | O(m log(n/m+1)) |
| **O(log n) nth/rank** | Yes | No | Yes |
| **O(log n) last** | Yes | O(n) | O(n) |
| **Parallel fold** | Yes (fork-join) | No | No |
| **Interval trees** | Yes | No | No |
| **Segment trees** | Yes | No | No |
| **Range maps** | Yes | No | No |
| **Fuzzy lookup** | Yes | No | No |
| **Memory/element** | ~64 bytes | ~61 bytes | ~64 bytes |

## Algorithmic Differences

### Set Operations

All three libraries support union, intersection, and difference.

**clojure.core** uses `clojure.set/union` etc., which iterate element-by-element. O(n log n) regardless of overlap.

**data.avl** and **ordered-collections** both use Adams' divide-and-conquer algorithm: split one tree at the other's root, recurse on halves, join. O(m log(n/m + 1)) where m ≤ n — information-theoretically optimal. When one set is much smaller, this is dramatically better than linear.

**ordered-collections** additionally parallelizes the two independent recursive calls via `ForkJoinPool`. Two thresholds control granularity:
- **210,000** (combined subtree size) — below this, sequential recursion is faster than forking
- **64** — below this, direct linear merge replaces divide-and-conquer entirely

In practice: 5–13x faster than sorted-set and 2–10x faster than data.avl across N=10K–500K. See [Benchmarks](benchmarks.md).

### Split and Join

| | clojure.core | data.avl | ordered-collections |
|--|:---:|:---:|:---:|
| `split-key` | — | O(log n) | O(log n) |
| `split-at` | — | O(log n) | O(log n) |
| `join` | — | O(log n) | O(log n) |

Both data.avl and ordered-collections expose split/join, but ordered-collections has ~3–4x lower constant factors because weight composes trivially: `weight(join(L, k, R)) = weight(L) + 1 + weight(R)`. AVL trees must recompute heights bottom-up after joining. Red-black trees must reconcile color invariants. This difference compounds in set operations, which call split/join at every level of recursion.

### Positional Access

Both ordered-collections and data.avl track subtree sizes, enabling:
- `(nth coll i)` — O(log n) vs O(n) for core sorted collections
- `(rank-of coll x)` — 0-based index of element
- `(split-at i coll)` — split at position

ordered-collections additionally provides `slice`, `median`, and `percentile` — all O(log n), all derived from `nth`.

### First / Last

| Operation | clojure.core | data.avl | ordered-collections |
|-----------|:---:|:---:|:---:|
| `(first coll)` | O(1) | O(1) | O(1) |
| `(last coll)` | **O(n)** | **O(n)** | **O(log n)** |

`last` on a 1M-element sorted-set scans the entire collection. ordered-collections traverses ~20 nodes via `java.util.SortedSet.last()`.

### Parallel Fold

ordered-collections implements `clojure.core.reducers/CollFold` using tree decomposition: split at root, fold subtrees in parallel via `ForkJoinPool`, combine. Threshold: 8,192 elements.

sorted-set and data.avl fall back to sequential reduce.

## Feature Comparison with data.avl

| Feature | ordered-collections | data.avl |
|---------|:---:|:---:|
| `split-key` / `split-at` | ✓ | ✓ |
| `subrange` | ✓ | ✓ |
| `nearest` (floor/ceiling) | ✓ | ✓ |
| `nth` / `rank-of` | ✓ | ✓ |
| `slice` / `median` / `percentile` | ✓ | — |
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

**ClojureScript support.** ordered-collections is JVM-only (`java.util.SortedSet`, `ForkJoinPool`, etc.).

**Transient support.** data.avl supports `transient`/`persistent!` for batch mutation. ordered-collections uses persistent construction throughout, mitigated by parallel batch construction which is competitive in practice.

## Memory

From `memory_test.clj` at N=100,000 (measured with clj-memory-meter):

| Collection | Bytes/Element | vs core |
|------------|:---:|:---:|
| sorted-set | 60.6 | 1.00x |
| data.avl sorted-set | 64.0 | 1.06x |
| **ordered-set** | 64.0 | 1.06x |

| Collection | Bytes/Entry | vs core |
|------------|:---:|:---:|
| sorted-map | 84.6 | 1.00x |
| data.avl sorted-map | 88.0 | 1.04x |
| **ordered-map** | 88.0 | 1.04x |

The extra ~4 bytes/node is the weight field. Both data.avl and ordered-collections pay this cost for subtree size tracking.

## Specialized Collections

Not available elsewhere in the Clojure ecosystem:

**Interval trees** — augmented with max-endpoint per subtree. O(log n + k) overlap queries for both point and interval queries:
```clojure
(def events (oc/interval-set [[0 10] [5 15] [20 30]]))
(events [8 12])  ;=> ([0 10] [5 15])
(events 5)       ;=> ([0 10] [5 15])
```

**Segment trees** — pre-computed subtree aggregates. O(log n) range queries and updates with any associative operation:
```clojure
(def st (oc/sum-tree {0 10, 1 20, 2 30, 3 40}))
(oc/query st 1 3)  ;=> 90
```

**Range maps** — non-overlapping `[lo, hi)` ranges. Inserting a range automatically carves out overlaps:
```clojure
(def rm (oc/range-map {[0 10] :a [20 30] :b}))
(rm 5)   ;=> :a
(rm 15)  ;=> nil
```

**Fuzzy sets/maps** — nearest-neighbor by distance with configurable tiebreaking:
```clojure
(def fs (oc/fuzzy-set [1.0 2.0 3.0 10.0]))
(fs 2.1)  ;=> 2.0
```

## When to Use Each

**clojure.core sorted collections** — zero dependencies, basic operations only. Fine if you just need lookup, insert, delete, and `subseq`.

**clojure.data.avl** — ClojureScript support, transient builders, nth/rank. Good choice when you need rank operations but not intervals, segments, or parallelism.

**ordered-collections** — specialized collections (intervals, segments, ranges, fuzzy), parallel set operations and fold, O(log n) `last`/`median`/`percentile`/`slice`. Best when you need capabilities beyond basic sorted access.

## Limitations

1. **JVM-only** — no ClojureScript (Java interop throughout)
2. **No transient builders** — persistent construction only (mitigated by parallel batch construction)
3. **6% more memory** than core sorted collections (same as data.avl)
4. **Point lookup within 10%** of both competitors (slightly taller trees, not a meaningful difference)

## References

1. Adams, S. (1992). "Implementing Sets Efficiently in a Functional Language". CSTR 92-10.
2. Hirai, Y. & Yamamoto, K. (2011). "Balancing Weight-Balanced Trees". JFP 21(3):287-307.
3. Blelloch, G., Ferizovic, D., & Sun, Y. (2016). "Just Join for Parallel Ordered Sets". SPAA '16.
4. [clojure.data.avl](https://github.com/clojure/data.avl)
5. [Haskell containers (Data.Set, Data.Map)](https://hackage.haskell.org/package/containers)
