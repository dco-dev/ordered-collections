# Benchmarks

## Running

```
$ lein bench                  # Criterium, N=100K (~3 min)
$ lein bench --full           # Criterium, N=10K,100K,500K (~13 min)
$ lein bench --readme --full  # README tables only (~5 min)
$ lein bench --sizes 50000    # Custom sizes

$ lein bench-simple           # Quick iteration bench (100 to 100K)
$ lein bench-simple --full    # Full suite (100 to 1M)
$ lein bench-range-map        # Range-map vs Guava TreeRangeMap
$ lein bench-parallel         # Parallel threshold crossover analysis
```

Results are written to `bench-results/<timestamp>.edn` with system info, sizes, and per-operation Criterium statistics.

## Environment

| | |
|--|--|
| JVM | OpenJDK 25.0.1, 64-bit Server VM |
| Clojure | 1.12.4 |
| Hardware | Apple M1 Pro, 16 cores |
| Heap | 8192 MB |
| Method | Criterium quick-benchmark (6 samples, JIT warmup, outlier detection) |

Relative ratios are more meaningful than absolute times.

## Set Operations

Two sets of size N with 50% overlap. Adams' divide-and-conquer with fork-join parallelism (threshold: 210,000 combined elements).

### vs sorted-set (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **9.3x** | **10.7x** | **7.1x** |
| Intersection | **7.2x** | **7.7x** | **5.1x** |
| Difference | **8.6x** | **12.7x** | **6.4x** |

### vs data.avl (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **6.1x** | **10.3x** | **7.4x** |
| Intersection | **5.1x** | **5.2x** | **4.2x** |
| Difference | **5.5x** | **7.0x** | **4.2x** |

### Raw times (ms)

| Operation | N | sorted-set | data.avl | ordered-set |
|-----------|---|----------:|----------:|----------:|
| Union | 10K | 5.86 | 3.82 | **0.63** |
| | 100K | 70.9 | 67.8 | **6.61** |
| | 500K | 352 | 367 | **49.8** |
| Intersection | 10K | 4.51 | 3.19 | **0.63** |
| | 100K | 54.9 | 36.7 | **7.12** |
| | 500K | 232 | 193 | **45.7** |
| Difference | 10K | 3.80 | 2.45 | **0.44** |
| | 100K | 51.0 | 28.2 | **4.01** |
| | 500K | 234 | 155 | **36.4** |

## Fold (r/fold)

Fork-join parallel fold (threshold: 8,192 elements). sorted-set and data.avl fall back to sequential reduce.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.59ms | 5.97ms | 41.1ms |
| data.avl | 0.18ms | 2.01ms | 14.1ms |
| **ordered-set** | **0.14ms** | **0.40ms** | **3.58ms** |
| vs sorted-set | 4.4x | 14.7x | **11.5x** |
| vs data.avl | 1.4x | 5.0x | **3.9x** |

## Construction

Batch from collection (parallel fold + union).

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 8.01ms | 128ms | 858ms |
| data.avl | 3.56ms | 59.9ms | 602ms |
| **ordered-set** | **3.36ms** | **60.0ms** | **384ms** |
| vs sorted-set | 2.4x | 2.1x | **2.2x** |
| vs data.avl | 1.1x | 1.0x | **1.6x** |

## Split

100 splits at random keys. Weight-balanced trees have lower constant factors — no height recomputation.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.93ms | 1.48ms | 2.05ms |
| **ordered-set** | **0.30ms** | **0.40ms** | **0.54ms** |
| vs data.avl | **3.1x** | **3.7x** | **3.8x** |

## Lookup

10K random lookups. Within 10% across all implementations — tree height differences wash out.

### Set (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 2.39 | 3.60 | 7.48 |
| data.avl | 2.29 | 3.21 | 6.44 |
| ordered-set | 2.01 | 3.18 | 6.83 |

### Map (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 2.84 | 4.31 | 8.59 |
| data.avl | 2.29 | 3.60 | 7.48 |
| ordered-map | 2.14 | 3.52 | 7.39 |

## Last Element

1000 calls. O(log n) via `java.util.SortedSet.last()` vs O(n) seq traversal.

| | N=10K | N=100K |
|--|------:|-------:|
| sorted-set | 734ms | 8,292ms |
| data.avl | 844ms | 9,377ms |
| **ordered-set** | **0.25ms** | **0.28ms** |

~29,000x faster at N=100K. Gap grows linearly with N.

## Iteration (reduce)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.59ms | 6.59ms | 35.3ms |
| data.avl | 0.10ms | 1.49ms | 8.24ms |
| ordered-set | 0.12ms | 1.44ms | 8.93ms |

4–5x faster than sorted-set. On par with data.avl.

## Insert (sequential conj, not batch)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 8.70ms | 141ms | 912ms |
| data.avl | 5.71ms | 91ms | 749ms |
| ordered-set | 4.61ms | 91ms | 809ms |

## Delete

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 4.12ms | 72ms | 525ms |
| data.avl | 2.86ms | 50ms | 365ms |
| ordered-set | 2.53ms | 44ms | 366ms |

## Positional Access

Both O(log n). sorted-set has no nth/rank.

### nth (10K accesses by index)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.85ms | 1.56ms | 2.10ms |
| ordered-set | 1.08ms | 2.20ms | 3.27ms |

data.avl ~1.4x faster.

### rank-of (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 2.64ms | 5.64ms | 7.24ms |
| ordered-set | 2.14ms | 4.95ms | 6.78ms |

ordered-set ~1.1x faster.

## Interval Collections

### Construction (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-set | 19.0 | 372 | 2,555 |
| interval-map | 25.1 | 457 | 3,156 |

### Lookup (10K overlap queries, ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-map | 135 | 167 | 177 |

Sub-linear growth — O(log n + k) means query time depends more on result count than collection size.

## Map Construction

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 6.22ms | 102ms | 776ms |
| data.avl | 4.28ms | 78.8ms | 717ms |
| ordered-map | 3.45ms | 65.2ms | **354ms** |

2.2x faster than sorted-map at N=500K via parallel batch construction.
