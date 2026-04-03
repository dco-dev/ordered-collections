# Performance Analysis

All benchmarks via [Criterium](https://github.com/hugoduncan/criterium) (quick-benchmark, 6 samples) on JDK 25.0.2, Mac OS X 26.3.1 (`aarch64`), 12 processors available to the JVM, 8192 MB heap. Two sets with 50% overlap for set operations; 10K random lookups for point queries.

Results are in `bench-results/<timestamp>.edn`. Run with `lein bench --full` (~30 min on this machine) or `lein bench --readme --full` (~10 min).

## Set Operations

The dominant advantage. Adams' divide-and-conquer with optional fork-join
parallelism still dominates the set-heavy workloads. The current production
policy no longer uses one universal threshold:

- union root threshold: `131,072`
- intersection root threshold: `65,536`
- difference root threshold: `131,072`
- ordered-map merge root threshold: `65,536`
- recursive re-fork threshold: `65,536`
- minimum branch for spawning: `65,536`
- direct sequential cutoff: `64`

The corrected `lein bench-parallel` harness now measures the real production
dispatch path across long keys, string keys, and ordered-map merge. On this
machine, the per-operation thresholds outperform the older one-size-fits-all
cutoffs and let parallelism pay off at more practical collection sizes.

As an exploratory check, the current benchmark suite also includes
`clojure.set` on plain hash-sets. That is not a fair ordered-collection
comparison, but it is still informative: ordered-set remains about 4-24x faster
for union/intersection/difference across the measured sizes.

### vs sorted-set

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **13.9x** | **22.4x** | **44.3x** |
| Intersection | **8.7x** | **15.8x** | **32.4x** |
| Difference | **9.4x** | **21.7x** | **46.1x** |

### vs data.avl

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **11.5x** | **19.3x** | **39.6x** |
| Intersection | **7.2x** | **13.5x** | **27.5x** |
| Difference | **7.3x** | **13.6x** | **32.3x** |

Why: `clojure.set/union` etc. insert element-by-element, O(n log n). data.avl uses the same split-based algorithm but without parallelism and with higher constant factors for split/join (AVL height recomputation).

### vs clojure.set on hash-set (exploratory)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **4.1x** | **7.3x** | **19.7x** |
| Intersection | **4.3x** | **7.6x** | **17.3x** |
| Difference | **5.1x** | **9.2x** | **24.2x** |

## Fold (r/fold)

ordered-set implements `CollFold` via chunked tree splitting plus `r/fold`. The implementation enforces a 4,096-element minimum chunk size. sorted-set and data.avl fall back to sequential reduce.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| vs sorted-set | **3.7x** | **7.3x** | **9.7x** |
| vs data.avl | 1.0x | **3.0x** | **3.4x** |

Raw times at N=500K: sorted-set 15.6ms, data.avl 5.5ms, ordered-set 1.6ms.

## Split

About 3.1-3.7x faster than data.avl across the measured sizes (100 splits per benchmark).

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| vs data.avl | **3.1x** | **3.6x** | **3.7x** |

Why: weight composes trivially after split/join — no height recomputation needed.

## Construction

Batch construction (from collection) uses parallel fold + union.

### vs sorted-set

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| Set | **2.9x** | **2.8x** | **2.7x** |

### vs data.avl

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| Set | **1.5x** | **1.4x** | **1.5x** |

Use the collection constructor, not sequential `conj`:

```clojure
(ordered-set (range 1000000))           ;; fast: parallel batch
(reduce conj (ordered-set) (range 1000000))  ;; slow: sequential insert
```

## Last Element

O(log n) via `java.util.SortedSet.last()` vs O(n) seq traversal for sorted-set and data.avl.

At N=100K (1000 calls each):

| | Time | vs ordered-set |
|--|-----:|---------------:|
| sorted-set | 5,085ms | 39,000x slower |
| data.avl | 5,376ms | 41,000x slower |
| **ordered-set** | **0.13ms** | baseline |

This gap grows linearly with N.

## Lookup

Point queries remain close to both competitors. Treat the current lookup numbers
as near-parity rather than as a meaningful advantage or disadvantage; the real
performance story is in split/join-heavy operations.

### Set lookup (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 1.47ms | 2.14ms | 2.89ms |
| data.avl | 1.51ms | 2.13ms | 2.85ms |
| ordered-set | 1.42ms | 2.58ms | 2.94ms |

### Map lookup (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 1.55ms | 2.21ms | 3.12ms |
| data.avl | 1.45ms | 2.11ms | 2.85ms |
| ordered-map | 1.21ms | 1.87ms | 2.71ms |

### Specialized node types

`LongKeyNode` stores an unboxed `long` key, avoiding boxed Long allocation and comparison overhead. `DoubleKeyNode` does the same for `double`.

```clojure
(long-ordered-set data)     ;; unboxed long keys
(double-ordered-set data)   ;; unboxed double keys
(string-ordered-set data)   ;; String.compareTo
```

## Iteration (reduce)

Both ordered-set and data.avl implement `IReduceInit` with direct tree traversal, avoiding lazy seq overhead. sorted-set pays for seq allocation.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.31ms | 3.43ms | 17.81ms |
| data.avl | 0.06ms | 0.95ms | 5.31ms |
| ordered-set | 0.07ms | 0.98ms | 4.84ms |

ordered-set is much faster than sorted-set and remains close to data.avl, slightly ahead at 500K in the current run.

## Insert and Delete (sequential)

Single-element insert/delete — not the batch constructor. Competitive with both alternatives.

### Insert (10K operations into N-element set)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 8.70ms | 141ms | 912ms |
| data.avl | 5.71ms | 91ms | 749ms |
| ordered-set | 4.61ms | 91ms | 809ms |

### Delete (10K operations)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 4.12ms | 72ms | 525ms |
| data.avl | 2.86ms | 50ms | 365ms |
| ordered-set | 2.53ms | 44ms | 366ms |

## Positional Access (nth / rank-of)

Both ordered-set and data.avl provide O(log n) positional operations. sorted-set requires O(n) traversal.

### nth (10K accesses by index)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.85ms | 1.73ms | 2.83ms |
| ordered-set | 1.40ms | 2.00ms | 2.80ms |

data.avl is still usually faster for nth, but the gap narrows substantially by 500K in the current simple benchmark.

### rank-of (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 2.39ms | 3.49ms | 6.03ms |
| ordered-set | 1.15ms | 2.62ms | 4.99ms |

ordered-set is faster for rank-of, roughly 1.2-2.1x in the current simple benchmark.

## Interval Collections

Construction is slower than regular sets (interval augmentation overhead: max-endpoint maintenance). Queries are efficient — O(log n + k).

### Construction

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-set | 9.3ms | 191.9ms | 1,367.3ms |
| interval-map | 10.4ms | 233.0ms | 1,667.2ms |

### Lookup (1K overlap queries)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-map | 61.4ms | 80.3ms | 94.9ms |

Sub-linear growth — the O(log n + k) complexity means query time depends more on result count than collection size.

## Memory

From `memory_test.clj` at N=100,000 (clj-memory-meter):

| | Bytes/element | vs core |
|--|:---:|:---:|
| sorted-set | 60.6 | 1.00x |
| data.avl | 64.0 | 1.06x |
| ordered-set | 64.0 | 1.06x |

| | Bytes/entry | vs core |
|--|:---:|:---:|
| sorted-map | 84.6 | 1.00x |
| data.avl | 88.0 | 1.04x |
| ordered-map | 88.0 | 1.04x |

~4 bytes/node overhead for the weight field. Same cost as data.avl's size field.

## Summary

| Area | Result |
|------|--------|
| Set algebra | **8.7–46.1x** vs sorted-set, **7.2–39.6x** vs data.avl |
| Fold | **3.7–9.7x** vs sorted-set, **1.0–3.4x** vs data.avl |
| Split | **3.1–3.7x** vs data.avl |
| Construction | **2.7–2.9x** vs sorted-set |
| Last element | **~40,000x** at N=100K (O(log n) vs O(n)) |
| Iteration | **4–5x** vs sorted-set, on par with data.avl |
| Lookup | Roughly comparable to both; not a headline differentiator |
| nth | data.avl ~1.4x faster |
| rank-of | ordered-set ~1.1x faster |
| Memory | 6% more than core, same as data.avl |
