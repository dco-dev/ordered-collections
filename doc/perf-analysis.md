# Performance Analysis

All benchmarks via [Criterium](https://github.com/hugoduncan/criterium) (quick-benchmark, 6 samples) on JDK 25, Apple M1 Pro (16 cores, 8192 MB heap). Two sets with 50% overlap for set operations; 10K random lookups for point queries.

Results are in `bench-results/<timestamp>.edn`. Run with `lein bench --full` (~13 min) or `lein bench --readme --full` (~5 min).

## Set Operations

The dominant advantage. Adams' divide-and-conquer + fork-join parallelism (threshold: 210,000 combined elements).

### vs sorted-set

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **9.3x** | **10.7x** | **7.1x** |
| Intersection | **7.2x** | **7.7x** | **5.1x** |
| Difference | **8.6x** | **12.7x** | **6.4x** |

### vs data.avl

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **6.1x** | **10.3x** | **7.4x** |
| Intersection | **5.1x** | **5.2x** | **4.2x** |
| Difference | **5.5x** | **7.0x** | **4.2x** |

Why: `clojure.set/union` etc. insert element-by-element, O(n log n). data.avl uses the same split-based algorithm but without parallelism and with higher constant factors for split/join (AVL height recomputation).

## Fold (r/fold)

ordered-set implements `CollFold` via tree-based fork-join (threshold: 8,192 elements). sorted-set and data.avl fall back to sequential reduce.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| vs sorted-set | **4.4x** | **14.7x** | **11.5x** |
| vs data.avl | 1.4x | **5.0x** | **3.9x** |

Raw times at N=500K: sorted-set 41ms, data.avl 14ms, ordered-set 3.6ms.

## Split

3–4x faster than data.avl across all sizes (100 splits per benchmark).

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| vs data.avl | **3.1x** | **3.7x** | **3.8x** |

Why: weight composes trivially after split/join — no height recomputation needed.

## Construction

Batch construction (from collection) uses parallel fold + union.

### vs sorted-set

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| Set | **2.4x** | **2.1x** | **2.2x** |

### vs data.avl

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| Set | 1.1x | 1.0x | **1.6x** |

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
| sorted-set | 8,292ms | 29,000x slower |
| data.avl | 9,377ms | 33,000x slower |
| **ordered-set** | **0.28ms** | baseline |

This gap grows linearly with N.

## Lookup

Point queries are within 10% of both competitors — tree height differences wash out.

### Set lookup (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 2.39ms | 3.60ms | 7.48ms |
| data.avl | 2.29ms | 3.21ms | 6.44ms |
| ordered-set | 2.01ms | 3.18ms | 6.83ms |

### Map lookup (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 2.84ms | 4.31ms | 8.59ms |
| data.avl | 2.29ms | 3.60ms | 7.48ms |
| ordered-map | 2.14ms | 3.52ms | 7.39ms |

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
| sorted-set | 0.59ms | 6.59ms | 35.3ms |
| data.avl | 0.10ms | 1.49ms | 8.24ms |
| ordered-set | 0.12ms | 1.44ms | 8.93ms |

ordered-set is 4–5x faster than sorted-set. On par with data.avl.

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
| data.avl | 0.85ms | 1.56ms | 2.10ms |
| ordered-set | 1.08ms | 2.20ms | 3.27ms |

data.avl is ~1.4x faster for nth. Both are O(log n).

### rank-of (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 2.64ms | 5.64ms | 7.24ms |
| ordered-set | 2.14ms | 4.95ms | 6.78ms |

ordered-set is ~1.1x faster for rank-of.

## Interval Collections

Construction is slower than regular sets (interval augmentation overhead: max-endpoint maintenance). Queries are efficient — O(log n + k).

### Construction

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-set | 19.0ms | 372ms | 2,555ms |
| interval-map | 25.1ms | 457ms | 3,156ms |

### Lookup (10K overlap queries)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-map | 135ms | 167ms | 177ms |

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
| Set algebra | **5–13x** faster (fork-join + split/join) |
| Fold | **4–15x** vs sorted-set, **1.4–5x** vs data.avl |
| Split | **3–4x** vs data.avl |
| Construction | **2x** vs sorted-set |
| Last element | **29,000x** at N=100K (O(log n) vs O(n)) |
| Iteration | **4–5x** vs sorted-set, on par with data.avl |
| Lookup | Within 10% of both |
| nth | data.avl ~1.4x faster |
| rank-of | ordered-set ~1.1x faster |
| Memory | 6% more than core, same as data.avl |
