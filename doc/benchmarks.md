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
| Union | **9.5x** | **9.5x** | **7.3x** |
| Intersection | **6.9x** | **8.1x** | **5.2x** |
| Difference | **7.9x** | **12.5x** | **6.9x** |

### vs data.avl (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **7.6x** | **8.8x** | **6.9x** |
| Intersection | **5.1x** | **4.9x** | **3.9x** |
| Difference | **5.8x** | **6.1x** | **4.6x** |

### Raw times (ms)

| Operation | N | sorted-set | data.avl | ordered-set |
|-----------|---|----------:|----------:|----------:|
| Union | 10K | 6.05 | 4.84 | **0.63** |
| | 100K | 69.7 | 64.2 | **7.31** |
| | 500K | 366 | 348 | **50.2** |
| Intersection | 10K | 4.48 | 3.31 | **0.65** |
| | 100K | 60.5 | 36.6 | **7.42** |
| | 500K | 270 | 203 | **52.3** |
| Difference | 10K | 3.65 | 2.71 | **0.46** |
| | 100K | 59.0 | 28.7 | **4.73** |
| | 500K | 237 | 160 | **34.4** |

## Fold (r/fold)

Fork-join parallel fold (threshold: 8,192 elements). sorted-set and data.avl fall back to sequential reduce.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.59ms | 6.01ms | 53.9ms |
| data.avl | 0.17ms | 1.91ms | 19.3ms |
| **ordered-set** | **0.10ms** | **0.37ms** | **4.30ms** |
| vs sorted-set | **5.7x** | **16.4x** | **12.5x** |
| vs data.avl | 1.6x | **5.2x** | **4.5x** |

## Construction

Batch from collection (parallel fold + union).

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 7.34ms | 133ms | 889ms |
| data.avl | 3.43ms | 63.1ms | 585ms |
| **ordered-set** | **3.27ms** | **69.0ms** | **426ms** |
| vs sorted-set | **2.2x** | **1.9x** | **2.1x** |
| vs data.avl | 1.0x | 0.9x | **1.4x** |

## Split

100 splits at random keys. Weight-balanced trees have lower constant factors — no height recomputation.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.87ms | 1.35ms | 1.70ms |
| **ordered-set** | **0.31ms** | **0.47ms** | **0.49ms** |
| vs data.avl | **2.8x** | **2.9x** | **3.5x** |

## Lookup

10K random lookups. Within 10% across all implementations — tree height differences wash out.

### Set (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 2.43 | 3.91 | 7.59 |
| data.avl | 2.18 | 3.30 | 6.63 |
| ordered-set | 2.03 | 3.05 | 6.54 |

### Map (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 2.56 | 4.26 | 8.58 |
| data.avl | 1.94 | 3.28 | 6.95 |
| ordered-map | 1.95 | 3.46 | 6.96 |

## Last Element

1000 calls. O(log n) via `java.util.SortedSet.last()` vs O(n) seq traversal.

| | N=10K | N=100K |
|--|------:|-------:|
| sorted-set | 761ms | 7,770ms |
| data.avl | 845ms | 10,200ms |
| **ordered-set** | **0.22ms** | **0.24ms** |

~32,000x faster at N=100K. Gap grows linearly with N.

## Iteration (reduce)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.61ms | 6.21ms | 43.2ms |
| data.avl | 0.10ms | 1.87ms | 10.4ms |
| ordered-set | 0.13ms | 1.41ms | 13.1ms |

4–5x faster than sorted-set. On par with data.avl.

## Insert (sequential conj, not batch)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 7.74ms | 134ms | 883ms |
| data.avl | 4.70ms | 96ms | 780ms |
| ordered-set | 4.10ms | 96ms | 737ms |

## Delete

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 3.85ms | 70ms | 495ms |
| data.avl | 2.60ms | 47ms | 353ms |
| ordered-set | 2.11ms | 43ms | 332ms |

## Positional Access

Both O(log n). sorted-set has no nth/rank.

### nth (10K accesses by index)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.92ms | 1.35ms | 2.08ms |
| ordered-set | 1.01ms | 1.64ms | 2.93ms |

data.avl ~1.4x faster.

### rank-of (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 2.50ms | 4.11ms | 6.90ms |
| ordered-set | 1.90ms | 3.15ms | 5.50ms |

ordered-set ~1.3x faster.

## Interval Collections

### Construction (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-set | 21.9 | 371 | 2,330 |
| interval-map | 24.8 | 469 | 2,960 |

### Lookup (10K overlap queries, ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-map | 133 | 153 | 172 |

Sub-linear growth — O(log n + k) means query time depends more on result count than collection size.

## Map Construction

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 5.52ms | 98ms | 686ms |
| data.avl | 3.70ms | 68ms | 678ms |
| ordered-map | 3.25ms | 65ms | **430ms** |

1.6x faster than sorted-map at N=500K via parallel batch construction.
