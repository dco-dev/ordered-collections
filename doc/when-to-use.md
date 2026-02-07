# When to Use ordered-collections

A decision guide for choosing between sorted collection implementations.

## Quick Decision Matrix

| Your Priority | Best Choice |
|---------------|-------------|
| Maximum lookup speed | `sorted-map` / `sorted-set` |
| Need `nth` or `rank` operations | `ordered-map` / `ordered-set` |
| Heavy iteration workloads | `ordered-map` / `ordered-set` |
| Parallel processing (`r/fold`) | `ordered-map` / `ordered-set` |
| Set algebra (union, intersection) | `ordered-set` |
| Interval/range overlap queries | `interval-map` / `interval-set` |
| Nearest-neighbor lookups | `fuzzy-map` / `fuzzy-set` |
| Minimal dependencies | `sorted-map` / `sorted-set` |
| Batch construction | `ordered-set` (parallel) |

## Detailed Comparison

### Clojure Built-ins: sorted-map / sorted-set

**Best for:**
- Simple sorted storage with fast lookup
- Applications where you only need basic get/assoc/dissoc
- Minimizing dependencies
- Maximum lookup performance

**Limitations:**
- No `nth` operation (requires O(n) conversion to vector)
- No rank queries
- `r/fold` falls back to sequential reduce
- `clojure.set` operations are O(n) linear scans

**Choose when:** Lookup dominates your workload and you don't need rank/nth or parallel fold.

### data.avl

**Best for:**
- O(1) rank access via `nth`
- Slightly faster lookup than ordered-collections
- Well-tested, mature library

**Limitations:**
- No parallel fold
- Split operations slower than ordered-collections
- No interval tree support

**Choose when:** You need fast `nth` access and don't need parallel processing or interval queries.

### ordered-collections (this library)

**Best for:**
- Iteration-heavy workloads (30% faster than sorted-map)
- Parallel aggregation via `r/fold` (1.6x faster)
- Efficient set algebra (union, intersection, difference)
- Split operations (5x faster than data.avl)
- Interval/range overlap queries
- Applications needing both map and interval functionality

**Limitations:**
- Lookup ~10% slower than sorted-map
- Construction ~2x slower than sorted-map
- Additional dependency

**Choose when:** You iterate more than you lookup, need parallel processing, or need interval queries.

## Workload-Based Recommendations

### Read-Heavy API Cache

```
Pattern: Many lookups, few updates
Recommendation: sorted-map

Reasoning: Lookup performance is critical. The 10% advantage
of sorted-map compounds over millions of requests.
```

### Analytics Pipeline

```
Pattern: Build once, aggregate many times
Recommendation: ordered-set + r/fold

Reasoning: Construction cost is amortized. Parallel fold
provides 1.7x speedup on aggregation, which dominates.
```

### Real-Time Leaderboard

```
Pattern: Frequent updates + rank queries
Recommendation: ordered-map

Reasoning: Only weight-balanced trees provide O(log n) rank.
sorted-map would require O(n) traversal for rank.
```

### Time-Series Database

```
Pattern: Range queries, sliding windows
Recommendation: ordered-map with subseq

Reasoning: Native Sorted support enables efficient range
queries. Split operations enable efficient window trimming.
```

### Meeting Scheduler

```
Pattern: Overlap detection, conflict checking
Recommendation: interval-map

Reasoning: No other sorted collection handles interval
overlap queries efficiently. This is the only option.
```

### Approximate Matching / Nearest Lookup

```
Pattern: Find closest value when exact match doesn't exist
Recommendation: fuzzy-set / fuzzy-map

Reasoning: Fuzzy collections return the nearest element
by distance when exact match fails. O(log n) nearest lookup.
```

### ETL Deduplication

```
Pattern: Build large set, check membership
Recommendation: ordered-set (build) → persistent (query)

Reasoning: Parallel construction is faster. Once built,
lookup performance is comparable.
```

## Performance by Operation

### Construction (smaller is better)

```
N = 500,000 elements

sorted-map:    1.0x (baseline)  ████
data.avl:      2.2x             █████████
ordered-map:   2.2x             █████████
```

**Verdict:** sorted-map wins construction. Use ordered-collections when construction is rare relative to other operations.

### Lookup (smaller is better)

```
10,000 random lookups on N = 500,000

sorted-map:    1.0x (baseline)  ████
data.avl:      1.1x             ████▌
ordered-map:   1.1x             ████▌
```

**Verdict:** Nearly equivalent. The 10% difference rarely matters in practice.

### Iteration (smaller is better)

```
reduce over N = 500,000

sorted-map:    1.0x (baseline)  ████████
data.avl:      0.85x            ███████
ordered-map:   0.75x            ██████
```

**Verdict:** ordered-collections wins iteration by 25-30%.

### Parallel Fold (smaller is better)

```
r/fold over N = 1,000,000

sorted-map:    1.0x (sequential fallback)  ████████
data.avl:      1.0x (sequential fallback)  ████████
ordered-map:   0.6x (true parallel)        █████
```

**Verdict:** Only ordered-collections parallelizes. 1.6x speedup at scale.

### Set Intersection (smaller is better)

```
intersection of two 500K-element sets

clojure.set:   1.0x (baseline)  ████████████
ordered-set:   0.25x            ███
```

**Verdict:** ordered-collections 4x faster on set algebra.

### Split (smaller is better)

```
100 splits on N = 500,000

data.avl:      1.0x (baseline)  ██████████
ordered-set:   0.2x             ██
```

**Verdict:** ordered-collections 5x faster on splits.

## Memory Comparison

All implementations use similar memory per entry:

| Implementation | Bytes per entry (approx) |
|----------------|--------------------------|
| sorted-map | 40-48 |
| data.avl | 48-56 |
| ordered-map | 48-56 |

The slight overhead in ordered-map comes from storing subtree weights.

## API Compatibility

### Full Clojure Compatibility

All ordered-collections types support:
- `get`, `assoc`, `dissoc`, `contains?`
- `seq`, `rseq`, `first`, `last`
- `count`, `empty`, `empty?`
- `=`, `hash`
- `meta`, `with-meta`
- `reduce`, `into`
- `nth` (for sets)

### Full clojure.lang.Sorted Compatibility

ordered-map and ordered-set support:
- `subseq`, `rsubseq`
- `comparator`
- `.seqFrom`, `.entryKey`, `.seq`

### Java Interop

- `java.util.Map` (ordered-map)
- `java.util.Set` / `java.util.SortedSet` (ordered-set)
- `java.io.Serializable`
- `java.lang.Comparable`
- `java.util.Iterator` / `Iterable`

## Migration Guide

### From sorted-map

```clojure
;; Before
(sorted-map :a 1 :b 2)
(sorted-map-by > :a 1 :b 2)

;; After
(require '[com.dean.ordered-collections.core :as oc])
(oc/ordered-map {:a 1 :b 2})
(oc/ordered-map-by > {:a 1 :b 2})
```

### From sorted-set

```clojure
;; Before
(sorted-set 1 2 3)
(sorted-set-by > 1 2 3)

;; After
(oc/ordered-set [1 2 3])
(oc/ordered-set-by > [1 2 3])
```

### From data.avl

```clojure
;; Before
(require '[clojure.data.avl :as avl])
(avl/sorted-map :a 1 :b 2)
(avl/nth my-map 5)

;; After
(oc/ordered-map {:a 1 :b 2})
(nth my-map 5)  ; same API
```

## Summary

**Use ordered-collections when:**
1. You iterate more than you lookup
2. You need `nth` or `rank` operations
3. You need parallel fold (`r/fold`)
4. You perform set algebra (union, intersection, difference)
5. You need interval/overlap queries
6. You need efficient split operations

**Stick with sorted-map when:**
1. Lookup is your primary operation
2. You want zero dependencies
3. Construction performance is critical
4. You don't need any advanced features
