# Benchmarks

This is the canonical performance document for the project: benchmark
methodology, current numbers, and the implementation details that most directly
explain those numbers.

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

Two sets of size N with 50% overlap. Adams' divide-and-conquer with optional
fork-join parallelism. Set algebra uses operation-specific root thresholds:

- union `131,072`
- intersection `65,536`
- difference `131,072`
- ordered-map merge `65,536`

Recursive re-forking currently uses `65,536` for all four operations, plus a
`65,536` minimum-branch guard and a `64` sequential cutoff for tiny subtrees.

This is the library's dominant advantage. The split/join structure gives
work-optimal set algebra, and fork-join parallelism helps extend that advantage
to larger multicore workloads.

### vs sorted-set (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **13.9x** | **22.4x** | **44.3x** |
| Intersection | **8.7x** | **15.8x** | **32.4x** |
| Difference | **9.4x** | **21.7x** | **46.1x** |

### vs data.avl (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **11.5x** | **19.3x** | **39.6x** |
| Intersection | **7.2x** | **13.5x** | **27.5x** |
| Difference | **7.3x** | **13.6x** | **32.3x** |

Interpretation:
- against `sorted-set`, the gap is mainly algorithmic: generic `clojure.set`
  paths over built-in sorted collections do not exploit a native split/join
  algebra
- against `data.avl`, both libraries benefit from ordered trees, but this
  library's split/join constant factors are lower and the set operations also
  parallelize

### vs clojure.set on hash-set (exploratory, unfair baseline)

This is not an ordered-collection comparison, so it should be read as an
exploratory stress test rather than as the main benchmark story. Even so, the
current split/join implementation still wins decisively.

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **4.1x** | **7.3x** | **19.7x** |
| Intersection | **4.3x** | **7.6x** | **17.3x** |
| Difference | **5.1x** | **9.2x** | **24.2x** |

### Raw times (ms)

| Operation | N | sorted-set | data.avl | ordered-set |
|-----------|---|----------:|----------:|----------:|
| Union | 10K | 3.09 | 2.55 | **0.22** |
| | 100K | 38.23 | 33.03 | **1.71** |
| | 500K | 207.15 | 184.85 | **4.67** |
| Intersection | 10K | 2.14 | 1.78 | **0.25** |
| | 100K | 27.14 | 23.29 | **1.72** |
| | 500K | 145.60 | 123.64 | **4.49** |
| Difference | 10K | 1.84 | 1.44 | **0.20** |
| | 100K | 28.21 | 17.63 | **1.30** |
| | 500K | 136.14 | 95.32 | **2.95** |

### Raw times vs clojure.set on hash-set (ms)

| Operation | N | clojure.set/hash-set | ordered-set |
|-----------|---|---------------------:|------------:|
| Union | 10K | 0.89 | **0.21** |
| | 100K | 10.94 | **1.50** |
| | 500K | 70.94 | **3.61** |
| Intersection | 10K | 0.95 | **0.22** |
| | 100K | 11.90 | **1.56** |
| | 500K | 64.85 | **3.75** |
| Difference | 10K | 0.87 | **0.17** |
| | 100K | 10.38 | **1.13** |
| | 500K | 57.86 | **2.40** |

## Fold (r/fold)

Chunked parallel fold via `r/fold`. The tree is split into equal subtrees and folded in parallel. sorted-set and data.avl fall back to sequential reduce.

Implementation note: `CollFold` is not just delegated blindly to `r/fold`.
`node-chunked-fold` splits the tree eagerly in the caller thread, enforces a
minimum chunk size of `4096`, and then folds chunk indices in parallel. That
keeps split overhead under control and avoids depending on dynamic bindings
inside worker tasks.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.31ms | 3.10ms | 15.56ms |
| data.avl | 0.08ms | 1.26ms | 5.48ms |
| **ordered-set** | **0.08ms** | **0.42ms** | **1.61ms** |
| vs sorted-set | **3.7x** | **7.3x** | **9.7x** |
| vs data.avl | 1.0x | **3.0x** | **3.4x** |

## Construction

Batch from collection (parallel fold + union).

This is why constructor-based bulk loading is the right path to benchmark and
the right path to use. Sequential `conj` is a different workload and is covered
separately below.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 4.52ms | 72.12ms | 530.02ms |
| data.avl | 2.32ms | 35.56ms | 297.27ms |
| **ordered-set** | **1.56ms** | **25.71ms** | **194.96ms** |
| vs sorted-set | **2.9x** | **2.8x** | **2.7x** |
| vs data.avl | **1.5x** | **1.4x** | **1.5x** |

## Split

100 splits at random keys. Weight-balanced trees have lower constant factors — no height recomputation.

This is one of the cleanest demonstrations of the representation choice. Weight
composes trivially after join; AVL trees must recompute heights bottom-up.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.42ms | 0.68ms | 0.93ms |
| **ordered-set** | **0.14ms** | **0.19ms** | **0.25ms** |
| vs data.avl | **3.1x** | **3.6x** | **3.7x** |

## Lookup

10K random lookups. These are all in the same practical performance tier; small
differences are not especially meaningful compared with the much larger wins in
set algebra, split/join-derived operations, construction, and fold.

Treat these as near-parity numbers, not as a headline differentiator.

### Set (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 1.47 | 2.14 | 2.89 |
| data.avl | 1.51 | 2.13 | 2.85 |
| ordered-set | 1.42 | 2.58 | 2.94 |

### Map (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 1.55 | 2.21 | 3.12 |
| data.avl | 1.45 | 2.11 | 2.85 |
| ordered-map | 1.21 | 1.87 | 2.71 |

## Last Element

1000 calls. O(log n) via `java.util.SortedSet.last()` vs O(n) seq traversal.

This is endpoint access, not a claim about `clojure.core/last`.

| | N=10K | N=100K |
|--|------:|-------:|
| sorted-set | 424ms | 5,085ms |
| data.avl | 538ms | 5,376ms |
| **ordered-set** | **0.12ms** | **0.13ms** |

~40,000x faster at N=100K. Gap grows linearly with N.

## Iteration (reduce)

Both ordered-collections and data.avl implement direct tree reduction paths,
which is why both are much faster than seq-driven reduction over `sorted-set`.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.31ms | 3.43ms | 17.81ms |
| data.avl | 0.06ms | 0.95ms | 5.31ms |
| ordered-set | 0.07ms | 0.98ms | 4.84ms |

4–5x faster than sorted-set at larger sizes. On par with data.avl.

## Insert (sequential conj, not batch)

This section is deliberately separate from construction. It measures repeated
single-element mutation, not bulk loading.

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

These are enabled by subtree sizes. For this library, size is part of the core
tree invariant rather than an add-on feature.

### nth (10K accesses by index)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.85ms | 1.73ms | 2.83ms |
| ordered-set | 1.40ms | 2.00ms | 2.80ms |

data.avl is usually faster, but the gap narrows substantially at 500K.

### rank-of (10K lookups)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 2.39ms | 3.49ms | 6.03ms |
| ordered-set | 1.15ms | 2.62ms | 4.99ms |

ordered-set is ~1.2-2.1x faster in these measurements.

## Interval Collections

### Construction (ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-set | 9.3 | 191.9 | 1,367.3 |
| interval-map | 10.4 | 233.0 | 1,667.2 |

### Lookup (1K overlap queries, ms)

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| interval-map | 61.4 | 80.3 | 94.9 |

Sub-linear growth — O(log n + k) means query time depends more on result count than collection size.

## Map Construction

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-map | 3.14ms | 55.25ms | 436.80ms |
| data.avl | 2.37ms | 37.41ms | 335.96ms |
| ordered-map | **1.45ms** | **28.81ms** | **191.14ms** |

2.3x faster than sorted-map and 1.8x faster than data.avl at N=500K in these measurements.

## Specialized Node Types

Primitive-specialized node types reduce boxing and comparison overhead for
common homogeneous-key cases:

```clojure
(long-ordered-set data)     ;; unboxed long keys
(double-ordered-set data)   ;; unboxed double keys
(string-ordered-set data)   ;; String.compareTo
```

These are constant-factor optimizations on top of the same tree algebra, not a
separate implementation strategy.
