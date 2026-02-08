# When to Use ordered-collections

A decision guide for choosing between sorted collection implementations.

## Quick Decision Matrix

| Your Priority | Best Choice |
|---------------|-------------|
| Maximum lookup speed | Any (~equal, within 8%) |
| Need `nth` or `rank` operations | `ordered-map` / `ordered-set` |
| Heavy iteration workloads | `ordered-map` / `ordered-set` |
| Parallel processing (`r/fold`) | `ordered-map` / `ordered-set` |
| Set algebra (union, intersection) | `ordered-set` |
| Interval/range overlap queries | `interval-map` / `interval-set` |
| Nearest-neighbor lookups | `fuzzy-map` / `fuzzy-set` |
| Minimal dependencies | `sorted-map` / `sorted-set` |
| Batch construction | `ordered-map` / `ordered-set` (parallel) |
| First/last element access | `ordered-set` (7000x faster) |

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
- Fast construction via parallel fold (matches or beats sorted-map/sorted-set)
- First/last element access (~7000x faster than sorted-set at scale)
- Parallel aggregation via `r/fold` (2.3x faster)
- Efficient set algebra (union, intersection, difference) — 5-9x faster
- Split operations (4.5x faster than data.avl)
- Interval/range overlap queries
- Applications needing both map and interval functionality

**Limitations:**
- Sequential insert ~1.5x slower than sorted-map (use batch construction instead)
- Additional dependency

**Choose when:** You need fast construction, parallel processing, set operations, or interval queries.

## Workload-Based Recommendations

### Read-Heavy API Cache

```
Pattern: Many lookups, few updates
Recommendation: ordered-map or sorted-map (equal performance)

Reasoning: Lookup performance is within 8%. ordered-map adds
parallel construction and nth/rank if needed later.
```

### Analytics Pipeline

```
Pattern: Build once, aggregate many times
Recommendation: ordered-set + r/fold

Reasoning: Parallel construction is 25% faster. Parallel fold
provides 2.3x speedup on aggregation.
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
N = 500,000 elements (parallel fold construction)

sorted-map:    1.0x (baseline)  ████████
data.avl:      2.2x             █████████████████
ordered-map:   1.0x             ████████  ← NOW EQUAL (was 2.2x)

sorted-set:    1.0x (baseline)  ████████
data.avl:      1.7x             █████████████
ordered-set:   0.8x             ██████    ← 25% FASTER
```

**Verdict:** ordered-map now matches sorted-map. ordered-set is 25% faster than sorted-set.

### Lookup (smaller is better)

```
10,000 random lookups on N = 500,000

sorted-map:    1.0x (baseline)  ████
data.avl:      1.1x             ████▌
ordered-map:   1.08x            ████▎
```

**Verdict:** Nearly equivalent. Within 8% — rarely matters in practice.

### First/Last Access (smaller is better)

```
1,000 first/last calls on N = 100,000

sorted-set:    1.0x (baseline)  ████████████████████████████████████████
ordered-set:   0.00014x         ▏  ← ~7000x FASTER (O(log n) vs O(n))
```

**Verdict:** ordered-set provides O(log n) endpoint access via SortedSet interface.

### Iteration (smaller is better)

```
reduce over N = 500,000

sorted-set:    1.0x (baseline)  ████████
data.avl:      0.59x            █████
ordered-set:   0.86x            ███████
```

**Verdict:** ordered-set 14% faster than sorted-set via IReduceInit.

### Parallel Fold (smaller is better)

```
r/fold over N = 500,000

sorted-set:    1.0x (sequential fallback)  ████████
data.avl:      1.0x (sequential fallback)  ████████
ordered-set:   0.43x (true parallel)       ████
```

**Verdict:** Only ordered-collections parallelizes. 2.3x speedup at scale.

### Set Operations (smaller is better)

```
Union/Intersection/Difference of two 500K-element sets

clojure.set union:        1.0x  ████████████
ordered-set union:        0.17x ██           ← 5.8x FASTER

clojure.set intersection: 1.0x  ████████████
ordered-set intersection: 0.19x ██           ← 5.3x FASTER

clojure.set difference:   1.0x  ████████████
ordered-set difference:   0.12x █            ← 8.6x FASTER
```

**Verdict:** ordered-set 5-9x faster on set algebra via divide-and-conquer.

### Split (smaller is better)

```
100 splits on N = 500,000

data.avl:      1.0x (baseline)  ██████████
ordered-set:   0.22x            ██
```

**Verdict:** ordered-set 4.5x faster on splits.

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
1. You need fast batch construction (parallel fold — 25% faster for sets, equal for maps)
2. You need first/last element access (7000x faster than sorted-set)
3. You need `nth` or `rank` operations
4. You need parallel fold (`r/fold`) — 2.3x faster
5. You perform set algebra (union, intersection, difference) — 5-9x faster
6. You need interval/overlap queries
7. You need efficient split operations — 4.5x faster

**Stick with sorted-map/sorted-set when:**
1. You want zero dependencies
2. You're doing mostly sequential inserts (1.5x faster than ordered-*)
3. You don't need any advanced features
