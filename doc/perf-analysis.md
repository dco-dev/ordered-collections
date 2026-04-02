# Performance Analysis

All benchmarks via [Criterium](https://github.com/hugoduncan/criterium) (quick-benchmark, 6 samples) on JDK 25.0.2, Mac OS X 26.3.1 (`aarch64`), 12 processors available to the JVM, 8192 MB heap. Two sets with 50% overlap for set operations; 10K random lookups for point queries.

Results are in `bench-results/<timestamp>.edn`. Run with `lein bench --full` (~30 min on this machine) or `lein bench --readme --full` (~10 min).

## Set Operations

The dominant advantage. Adams' divide-and-conquer with optional fork-join parallelism still dominates the set-heavy workloads. The configured threshold is now 524,288 combined elements. The refreshed `lein bench-parallel` harness measures the real production dispatch path across long keys, string keys, and ordered-map merge, and on this machine it showed that the old 210,000 threshold entered the fork-join path too early, especially for string keys. Treat 524,288 as a conservative tuning choice for the current implementation and hardware, not a universal law.

### vs sorted-set

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **9.8x** | **9.6x** | **7.5x** |
| Intersection | **6.1x** | **6.8x** | **5.1x** |
| Difference | **6.9x** | **10.3x** | **5.6x** |

### vs data.avl

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **7.5x** | **7.0x** | **5.7x** |
| Intersection | **5.3x** | **5.4x** | **4.0x** |
| Difference | **5.1x** | **5.8x** | **3.5x** |

Why: `clojure.set/union` etc. insert element-by-element, O(n log n). data.avl uses the same split-based algorithm but without parallelism and with higher constant factors for split/join (AVL height recomputation).

## Fold (r/fold)

ordered-set implements `CollFold` via chunked tree splitting plus `r/fold`. The implementation enforces a 4,096-element minimum chunk size. sorted-set and data.avl fall back to sequential reduce.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| vs sorted-set | **3.7x** | **7.2x** | **9.1x** |
| vs data.avl | 1.0x | **2.8x** | **3.2x** |

Raw times at N=500K: sorted-set 16.1ms, data.avl 5.6ms, ordered-set 1.8ms.

## Split

About 2.6-3.3x faster than data.avl across the measured sizes (100 splits per benchmark).

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| vs data.avl | **2.6x** | **3.3x** | **3.3x** |

Why: weight composes trivially after split/join — no height recomputation needed.

## Construction

Batch construction (from collection) uses parallel fold + union.

### vs sorted-set

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| Set | **2.6x** | **2.5x** | **2.6x** |

### vs data.avl

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| Set | **1.4x** | **1.2x** | **1.4x** |

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
| sorted-set | 5,340ms | 41,000x slower |
| data.avl | 5,180ms | 40,000x slower |
| **ordered-set** | **0.13ms** | baseline |

This gap grows linearly with N.

## Lookup

Point queries remain close to both competitors. Set lookup stays within roughly 10-15%; map lookup stays within roughly 20%, with `ordered-map` very close to `data.avl`.

### Set lookup (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 1.70ms | 2.43ms | 3.09ms |
| data.avl | 1.59ms | 2.22ms | 2.94ms |
| ordered-set | 1.32ms | 2.10ms | 2.79ms |

### Map lookup (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 1.48ms | 2.64ms | 3.49ms |
| data.avl | 1.52ms | 2.20ms | 2.96ms |
| ordered-map | 1.22ms | 2.24ms | 2.97ms |

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
| interval-set | 13.1ms | 176ms | 2,970ms |
| interval-map | 12.7ms | 195ms | 3,140ms |

### Lookup (1K overlap queries)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-map | 5.7ms | 21.1ms | 172.6ms |

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
| Set algebra | **5–10x** vs sorted-set, **3.5–7.5x** vs data.avl |
| Fold | **3.7–9.1x** vs sorted-set, **1.0–3.2x** vs data.avl |
| Split | **2.6–3.3x** vs data.avl |
| Construction | **2.5–2.6x** vs sorted-set |
| Last element | **~40,000x** at N=100K (O(log n) vs O(n)) |
| Iteration | **4–5x** vs sorted-set, on par with data.avl |
| Lookup | Within 10% of both |
| nth | data.avl ~1.4x faster |
| rank-of | ordered-set ~1.1x faster |
| Memory | 6% more than core, same as data.avl |
