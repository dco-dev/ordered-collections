# Benchmarks

## Running

```
$ lein bench                  # Criterium, N=100K (~5 min)
$ lein bench --full           # Criterium, N=10K,100K,500K (~30 min)
$ lein bench --readme --full  # README tables only (~10 min)
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
| JVM | OpenJDK 25.0.2, 64-bit Server VM |
| Clojure | 1.12.4 |
| OS / Arch | Mac OS X 26.3.1 / aarch64 |
| Available processors | 12 |
| Heap | 8192 MB |
| Method | Criterium quick-benchmark (6 samples, JIT warmup, outlier detection) |

Relative ratios are more meaningful than absolute times.

## Set Operations

Two sets of size N with 50% overlap. Adams' divide-and-conquer with optional fork-join parallelism. The current configured threshold is 524,288 combined elements. The threshold benchmark now measures the real production dispatch path for long keys, string keys, and ordered-map merge; on this machine, that showed the old 210,000 cutoff was too aggressive.

### vs sorted-set (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **9.8x** | **9.6x** | **7.5x** |
| Intersection | **6.1x** | **6.8x** | **5.1x** |
| Difference | **6.9x** | **10.3x** | **5.6x** |

### vs data.avl (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **7.5x** | **7.0x** | **5.7x** |
| Intersection | **5.3x** | **5.4x** | **4.0x** |
| Difference | **5.1x** | **5.8x** | **3.5x** |

### Raw times (ms)

| Operation | N | sorted-set | data.avl | ordered-set |
|-----------|---|----------:|----------:|----------:|
| Union | 10K | 3.44 | 2.64 | **0.35** |
| | 100K | 45.94 | 33.43 | **4.81** |
| | 500K | 258.84 | 195.35 | **34.38** |
| Intersection | 10K | 2.32 | 2.00 | **0.38** |
| | 100K | 30.69 | 24.56 | **4.53** |
| | 500K | 168.55 | 131.18 | **33.09** |
| Difference | 10K | 1.97 | 1.46 | **0.29** |
| | 100K | 31.70 | 17.78 | **3.06** |
| | 500K | 158.92 | 100.15 | **28.38** |

## Fold (r/fold)

Chunked parallel fold via `r/fold`. The tree is split into equal subtrees and folded in parallel. sorted-set and data.avl fall back to sequential reduce.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.31ms | 3.14ms | 16.11ms |
| data.avl | 0.08ms | 1.23ms | 5.59ms |
| **ordered-set** | **0.08ms** | **0.44ms** | **1.77ms** |
| vs sorted-set | **3.7x** | **7.2x** | **9.1x** |
| vs data.avl | 1.0x | **2.8x** | **3.2x** |

## Construction

Batch from collection (parallel fold + union).

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 4.53ms | 77.06ms | 558.07ms |
| data.avl | 2.40ms | 36.31ms | 299.92ms |
| **ordered-set** | **1.75ms** | **30.52ms** | **217.67ms** |
| vs sorted-set | **2.6x** | **2.5x** | **2.6x** |
| vs data.avl | **1.4x** | **1.2x** | **1.4x** |

## Split

100 splits at random keys. Weight-balanced trees have lower constant factors — no height recomputation.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.43ms | 0.74ms | 0.98ms |
| **ordered-set** | **0.17ms** | **0.23ms** | **0.29ms** |
| vs data.avl | **2.6x** | **3.3x** | **3.3x** |

## Lookup

10K random lookups. Set lookups are within roughly 10-15% across implementations; map lookups stay within roughly 20%.

### Set (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 1.70 | 2.43 | 3.09 |
| data.avl | 1.59 | 2.22 | 2.94 |
| ordered-set | 1.32 | 2.10 | 2.79 |

### Map (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 1.48 | 2.64 | 3.49 |
| data.avl | 1.52 | 2.20 | 2.96 |
| ordered-map | 1.22 | 2.24 | 2.97 |

## Last Element

1000 calls. O(log n) via `java.util.SortedSet.last()` vs O(n) seq traversal.

| | N=10K | N=100K |
|--|------:|-------:|
| sorted-set | 506ms | 5,340ms |
| data.avl | 533ms | 5,180ms |
| **ordered-set** | **0.12ms** | **0.13ms** |

~40,000x faster at N=100K. Gap grows linearly with N.

## Iteration (reduce)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.31ms | 3.43ms | 17.81ms |
| data.avl | 0.06ms | 0.95ms | 5.31ms |
| ordered-set | 0.07ms | 0.98ms | 4.84ms |

4–5x faster than sorted-set at larger sizes. On par with data.avl.

## Insert (sequential conj, not batch)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 6.37ms | 71.62ms | 1,220ms |
| data.avl | 5.25ms | 49.26ms | 983ms |
| ordered-set | 4.61ms | 44.81ms | 906ms |

## Delete

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 3.30ms | 36.43ms | 621.05ms |
| data.avl | 1.91ms | 26.13ms | 482.01ms |
| ordered-set | 1.82ms | 22.90ms | 461.38ms |

## Positional Access

Both O(log n). sorted-set has no nth/rank.

### nth (10K accesses by index)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.85ms | 1.73ms | 2.83ms |
| ordered-set | 1.40ms | 2.00ms | 2.80ms |

data.avl is usually faster, but the gap narrows substantially at 500K in the current run.

### rank-of (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 2.39ms | 3.49ms | 6.03ms |
| ordered-set | 1.15ms | 2.62ms | 4.99ms |

ordered-set is ~1.2-2.1x faster in the current run.

## Interval Collections

### Construction (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-set | 13.1 | 176 | 2,970 |
| interval-map | 12.7 | 195 | 3,140 |

### Lookup (1K overlap queries, ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-map | 5.7 | 21.1 | 172.6 |

Sub-linear growth — O(log n + k) means query time depends more on result count than collection size.

## Map Construction

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 3.09ms | 55.42ms | 437.76ms |
| data.avl | 2.38ms | 37.28ms | 322.27ms |
| ordered-map | **1.69ms** | **32.56ms** | **210.03ms** |

2.1x faster than sorted-map and 1.5x faster than data.avl at N=500K in the current run.
