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
$ lein bench-rope-tuning      # Rope chunk-size sweep
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
| Union | **15.4x** | **26.4x** | **56.6x** |
| Intersection | **9.0x** | **17.0x** | **36.2x** |
| Difference | **9.6x** | **22.1x** | **50.2x** |

### vs data.avl (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **10.9x** | **20.5x** | **42.1x** |
| Intersection | **7.2x** | **13.0x** | **28.1x** |
| Difference | **7.2x** | **12.7x** | **32.0x** |

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
| Union | **4.2x** | **7.2x** | **16.3x** |
| Intersection | **3.8x** | **6.1x** | **12.9x** |
| Difference | **4.4x** | **7.6x** | **18.6x** |

### Raw times (ms)

| Operation | N | sorted-set | data.avl | ordered-set |
|-----------|---|----------:|----------:|----------:|
| Union | 10K | 3.63 | 2.56 | **0.24** |
| | 100K | 42.71 | 33.27 | **1.62** |
| | 500K | 246.60 | 183.40 | **4.36** |
| Intersection | 10K | 2.32 | 1.85 | **0.26** |
| | 100K | 30.36 | 23.25 | **1.79** |
| | 500K | 170.02 | 131.96 | **4.70** |
| Difference | 10K | 2.02 | 1.51 | **0.21** |
| | 100K | 30.70 | 17.71 | **1.39** |
| | 500K | 156.97 | 100.24 | **3.13** |

### Raw times vs clojure.set on hash-set (ms)

| Operation | N | clojure.set/hash-set | ordered-set |
|-----------|---|---------------------:|------------:|
| Union | 10K | 1.00 | **0.24** |
| | 100K | 11.65 | **1.62** |
| | 500K | 70.93 | **4.36** |
| Intersection | 10K | 0.98 | **0.26** |
| | 100K | 10.85 | **1.79** |
| | 500K | 60.70 | **4.70** |
| Difference | 10K | 0.91 | **0.21** |
| | 100K | 10.51 | **1.39** |
| | 500K | 58.12 | **3.13** |

## Fold (r/fold)

Parallel fold via `r/fold`. The tree is split into equal subtrees and folded in parallel. sorted-set and data.avl fall back to sequential reduce.

Implementation note: `CollFold` is not just delegated blindly to `r/fold`.
`node-fold` splits the tree eagerly in the caller thread and then folds chunk
indices in parallel. That
keeps split overhead under control and avoids depending on dynamic bindings
inside worker tasks.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 0.33ms | 3.02ms | 16.37ms |
| data.avl | 0.76ms | 4.34ms | 35.04ms |
| **ordered-set** | **0.13ms** | **0.73ms** | **3.97ms** |
| vs sorted-set | **2.5x** | **4.1x** | **4.1x** |
| vs data.avl | **5.8x** | **5.9x** | **8.8x** |

## Construction

Batch from collection (parallel fold + union).

This is why constructor-based bulk loading is the right path to benchmark and
the right path to use. Sequential `conj` is a different workload and is covered
separately below.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| sorted-set | 4.65ms | 75.02ms | 542.18ms |
| data.avl | 2.33ms | 35.16ms | 291.11ms |
| **ordered-set** | **1.55ms** | **26.34ms** | **172.21ms** |
| vs sorted-set | **3.0x** | **2.8x** | **3.1x** |
| vs data.avl | **1.5x** | **1.3x** | **1.7x** |

## Split

100 splits at random keys. Weight-balanced trees have lower constant factors — no height recomputation.

This is one of the cleanest demonstrations of the representation choice. Weight
composes trivially after join; AVL trees must recompute heights bottom-up.

| | N=10K | N=100K | N=500K |
|--|------:|-------:|-------:|
| data.avl | 0.45ms | 0.68ms | 0.96ms |
| **ordered-set** | **0.07ms** | **0.09ms** | **0.12ms** |
| vs data.avl | **6.8x** | **7.2x** | **7.8x** |

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

## Set Equality

Randomized integer sets, measured in isolation with `lein bench-simple`.

This is not one of the library's headline capabilities, but it is a useful
sanity check for ordered-set's direct tree comparison path. The most meaningful
cases are:

- equal sets of the same size
- same-size sets differing in one element

The cardinality-mismatch case is effectively a count check and is not very
interesting.

### Equal sets

| | N=1K | N=10K | N=100K |
|--|-----:|------:|-------:|
| hash-set | 0.03ms | 0.28ms | 3.66ms |
| sorted-set | 0.10ms | 1.19ms | 14.80ms |
| data.avl | 0.13ms | 1.40ms | 15.10ms |
| ordered-set | 0.01ms | 0.10ms | 1.59ms |
| vs hash-set | **3.4x** | **2.8x** | **2.3x** |
| vs sorted-set | **11.3x** | **12.0x** | **9.3x** |
| vs data.avl | **14.0x** | **14.1x** | **9.5x** |

Interpretation:

- by `1K`, ordered-set is already competitive to clearly faster on equal-set comparison
- by `10K`, ordered-set's direct ordered comparison path is clearly better
- by `100K`, ordered-set is still several times faster than hash-set and about
  an order of magnitude faster than sorted-set and data.avl

### Same size, one different element

| | N=1K | N=10K | N=100K |
|--|-----:|------:|-------:|
| hash-set | 0.01ms | 0.10ms | 0.33ms |
| sorted-set | 0.10ms | 1.20ms | 14.93ms |
| data.avl | 0.11ms | 1.36ms | 15.07ms |
| ordered-set | 0.01ms | 0.10ms | 1.09ms |

Interpretation:

- hash-set still wins the unequal cases at larger sizes
- ordered-set still substantially beats sorted-set and data.avl
- ordered-set is still in the same practical tier at `10K`, then well ahead of
  the ordered competitors by `100K`

This is a good example of where direct ordered traversal helps beyond set
algebra itself: once sets are large enough, the library can compare two
compatible ordered sets very efficiently without falling back to generic
membership-oriented equality work.

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

## Rope vs PersistentVector

The rope is a persistent sequence optimized for structural editing. Where
PersistentVector is O(n) for mid-sequence splice/insert/remove, the rope is
O(log n). The advantage is unbounded and grows linearly with collection size.

### Structural Editing

| Workload | N=10K | N=100K | N=500K |
|---|---:|---:|---:|
| 200 random edits — rope | 2.6ms | 2.2ms | 2.9ms |
| 200 random edits — vector | 97ms | 1.04s | 5.4s |
| **Speedup** | **38x** | **473x** | **1862x** |

| Workload | N=10K | N=100K | N=500K |
|---|---:|---:|---:|
| Single splice — rope | 128µs | 54µs | 49µs |
| Single splice — vector | 842µs | 6.0ms | 27ms |
| **Speedup** | **7x** | **111x** | **551x** |

At 500K elements, 200 random splice operations take ~3ms on the rope vs ~5.4
seconds on the vector. The rope's time is nearly constant across sizes because
each operation is O(log n).

### Concatenation

| Workload | N=10K | N=100K | N=500K |
|---|---:|---:|---:|
| Concat many pieces — rope | 36µs | 123µs | 364µs |
| Concat many pieces — vector | 102µs | 800µs | 3.4ms |
| **Speedup** | **3x** | **7x** | **9x** |

Bulk concatenation collects chunks in O(total chunks) and builds the tree
directly, avoiding pairwise tree operations.

### Reduce

| Workload | N=10K | N=100K | N=500K |
|---|---:|---:|---:|
| Reduce sum — rope | 145µs | 601µs | 2.8ms |
| Reduce sum — vector | 93µs | 617µs | 3.6ms |
| **Ratio** | 0.6x | ~1x | **1.3x** |

The rope beats vectors on reduce at N >= 100K because 256-element chunks give
better cache locality per reduction step than PersistentVector's 32-wide trie
nodes. The rope uses a direct in-order tree walk (no enumerator frames) and
delegates to the vector's native `.reduce` for chunk-internal iteration.

### Parallel Fold

| Workload | N=10K | N=100K | N=500K |
|---|---:|---:|---:|
| Fold sum — rope | 0.22ms | 0.31ms | 0.85ms |
| Fold sum — vector | 1.23ms | 0.47ms | 1.13ms |
| **Speedup** | **5.6x** | **1.5x** | **1.3x** |

The rope's `r/fold` uses tree-based fork-join decomposition — split at the
midpoint, fork left, compute right inline, join. This maps directly onto the
`ForkJoinPool` work-stealing model without a separate chunking pass. At small N
the speedup is largest because the rope's tree structure provides immediate
parallelism while the vector's fold has higher setup overhead.

### Rope vs String (text workload)

For text-editing workloads, the rope also beats `java.lang.String` at scale:

| Workload (N=100K chars) | Rope | String | Speedup |
|---|---:|---:|---:|
| Splice 32 chars at midpoint | 3.6µs | 13.4µs | **3.8x** |
| Split at midpoint | 425ns | 2.6µs | **6.1x** |

String splice and split copy the entire string (O(n)); the rope does O(log n)
tree work.

### Where the Rope Loses

| Workload | Ratio | Why |
|---|---|---|
| Split / slice | ~20x slower | O(log n) vs O(1) `subvec` wrapper |
| Random nth | 0.4-0.7x | O(log n) vs O(1) trie lookup |
| Build via transient | 2-3x slower | Periodic O(log n) tree flush vs O(1) array append |

These losses are inherent to the tree-backed design, not to the
implementation. The absolute times are microseconds.

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
