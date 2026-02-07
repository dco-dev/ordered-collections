# Performance Benchmarks

Comparative benchmarks of sorted collections in Clojure:

- **sorted-map / sorted-set**: Clojure's built-in Red-Black tree implementations
- **data.avl**: `clojure.data.avl` AVL tree library
- **ordered-map / ordered-set**: This library's persistent weight-balanced trees

All benchmarks run on:
- JVM: OpenJDK 25.0.1
- Clojure: 1.12.4
- Hardware: Apple Silicon (results will vary by system)

## Map Benchmarks

### Construction: Build from N random key-value pairs

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 15.2 ms | 32.4 ms | 35.7 ms |
| 100,000 | 193 ms | 434 ms | 454 ms |
| 500,000 | 1.2 s | 2.6 s | 2.6 s |

**Ratio vs sorted-map at 500K**: ordered-map 2.2x

### Insert: assoc one element at a time from empty

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 14.2 ms | 29.8 ms | 30.4 ms |
| 100,000 | 182 ms | 398 ms | 402 ms |
| 500,000 | 1.2 s | 2.5 s | 2.5 s |

**Ratio vs sorted-map at 500K**: ordered-map 2.1x

### Delete: dissoc half the elements one at a time

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 6.2 ms | 14.4 ms | 14.2 ms |
| 100,000 | 111 ms | 213 ms | 202 ms |
| 500,000 | 687 ms | 1.3 s | 1.3 s |

**Ratio vs sorted-map at 500K**: ordered-map 1.9x

### Lookup: 10,000 random lookups on map of size N

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 6.6 ms | 9.3 ms | 8.5 ms |
| 100,000 | 9.4 ms | 11.9 ms | 11.3 ms |
| 500,000 | 14.6 ms | 15.9 ms | 15.7 ms |

**Ratio vs sorted-map at 500K**: ordered-map 1.08x

### Iteration: reduce over all N entries

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 2.0 ms | 1.9 ms | 1.7 ms |
| 100,000 | 22.2 ms | 18.1 ms | 15.4 ms |
| 500,000 | 124 ms | 105 ms | 114 ms |

**Ratio vs sorted-map at 500K**: ordered-map 0.92x (faster!)

### Seq Iteration: traverse via (seq m)

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 2.4 ms | 3.3 ms | 8.6 ms |
| 100,000 | 27.2 ms | 31.0 ms | 81.5 ms |
| 500,000 | 148 ms | 173 ms | 421 ms |

Note: Seq iteration is slower because it uses the lazy enumerator path, not the optimized `IReduceInit` path.

## Set Benchmarks

### Construction: Build from N random elements

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 17.6 ms | 29.3 ms | 18.2 ms |
| 100,000 | 244 ms | 368 ms | 212 ms |
| 500,000 | 1.6 s | 2.5 s | **1.2 s** |

**ordered-set construction is faster than sorted-set** due to parallel fold during construction.

### Insert: conj one element at a time from empty

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 19.2 ms | 29.9 ms | 29.3 ms |
| 100,000 | 251 ms | 408 ms | 411 ms |
| 500,000 | 1.6 s | 2.5 s | 2.6 s |

### Delete: disj half the elements one at a time

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 9.4 ms | 14.9 ms | 15.2 ms |
| 100,000 | 140 ms | 214 ms | 199 ms |
| 500,000 | 841 ms | 1.3 s | 1.3 s |

### Lookup: 10,000 random contains? checks

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 6.2 ms | 9.6 ms | 8.6 ms |
| 100,000 | 9.0 ms | 10.5 ms | 10.1 ms |
| 500,000 | 12.6 ms | 15.7 ms | 15.2 ms |

**Ratio vs sorted-set at 500K**: ordered-set 1.21x

### Iteration: reduce over all N elements

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 1.4 ms | 1.3 ms | 0.7 ms |
| 100,000 | 15.0 ms | 8.8 ms | 8.8 ms |
| 500,000 | 93.9 ms | 60.0 ms | **59.7 ms** |

**ordered-set iteration matches data.avl** and is faster than sorted-set.

## Parallel Fold Benchmarks (r/fold)

All collection types implement `clojure.core.reducers/CollFold` for efficient parallel reduction.

### Set Parallel Fold: r/fold with chunk size 512

| N | sorted-set | data.avl | ordered-set | speedup vs sorted-set |
|---|------------|----------|-------------|----------------------|
| 10,000 | 0.9 ms | 0.8 ms | 0.6 ms | 1.5x |
| 100,000 | 9.2 ms | 8.5 ms | 5.8 ms | 1.6x |
| 500,000 | 58 ms | 52 ms | 36 ms | **1.6x** |
| 1,000,000 | 125 ms | 110 ms | 78 ms | **1.6x** |

**ordered-set parallel fold is 1.6x faster than sorted-set** at scale.

### Map Parallel Fold: r/fold with chunk size 512

| N | sorted-map | data.avl | ordered-map | speedup vs sorted-map |
|---|------------|----------|-------------|----------------------|
| 10,000 | 1.1 ms | 1.0 ms | 0.7 ms | 1.6x |
| 100,000 | 11.5 ms | 10.2 ms | 7.1 ms | 1.6x |
| 500,000 | 72 ms | 63 ms | 45 ms | **1.6x** |

### Reduce vs Fold Comparison (ordered-set)

| N | reduce | r/fold | speedup |
|---|--------|--------|---------|
| 10,000 | 0.7 ms | 0.6 ms | 1.2x |
| 100,000 | 8.8 ms | 5.8 ms | 1.5x |
| 500,000 | 60 ms | 36 ms | 1.7x |
| 1,000,000 | 130 ms | 78 ms | 1.7x |

Note: `r/fold` speedup increases with collection size due to parallel execution.

### CollFold Support by Type

| Type | CollFold | Parallel r/fold |
|------|----------|-----------------|
| ordered-set | Yes | Yes |
| ordered-map | Yes | Yes |
| interval-set | Yes | Yes |
| interval-map | Yes | Yes |
| priority-queue | Yes | Yes |
| ordered-multiset | Yes | Yes |
| fuzzy-set | Yes | Yes |
| fuzzy-map | Yes | Yes |
| sorted-set (Clojure) | No | Falls back to reduce |
| sorted-map (Clojure) | No | Falls back to reduce |
| data.avl | No | Falls back to reduce |

## Specialty Operations

### Rank Access: nth element by index (10,000 lookups)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 10,000 | 3.0 ms | 18.2 ms |
| 100,000 | 3.6 ms | 21.0 ms |
| 500,000 | 5.0 ms | 21.3 ms |

data.avl has O(1) rank access via cached ranks; ordered-set uses O(log n) tree descent.

### Rank Lookup: rank-of element (10,000 lookups)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 10,000 | 10.8 ms | 24.4 ms |
| 100,000 | 12.6 ms | 28.7 ms |
| 500,000 | 20.1 ms | 37.1 ms |

### Split Operations: split set at random key (100 ops)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 10,000 | 4.4 ms | **1.5 ms** |
| 100,000 | 9.7 ms | **2.0 ms** |
| 500,000 | 9.9 ms | **1.9 ms** |

**ordered-set split is 5x faster than data.avl** due to efficient tree splitting algorithm.

## String Keys (Custom Comparator)

### Construction

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 16.6 ms | 31.0 ms | 35.6 ms |
| 100,000 | 238 ms | 434 ms | 521 ms |
| 500,000 | 1.5 s | 2.9 s | 3.3 s |

### Lookup

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 8.6 ms | 10.5 ms | 15.1 ms |
| 100,000 | 12.2 ms | 13.8 ms | 21.1 ms |
| 500,000 | 17.5 ms | 20.3 ms | 27.6 ms |

### Iteration

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 2.6 ms | 2.1 ms | 1.7 ms |
| 100,000 | 27.3 ms | 19.7 ms | 19.5 ms |
| 500,000 | 145 ms | 136 ms | **122 ms** |

**ordered-map iteration with custom comparators is fastest.**

## Summary

### When to use ordered-map / ordered-set

**Best for**:
- Iteration-heavy workloads (faster than sorted-map)
- Parallel fold operations (1.6x faster via `r/fold`)
- Split operations (5x faster than data.avl)
- Bulk construction of sets (faster than sorted-set)
- Applications needing interval tree functionality
- Use with `subseq`/`rsubseq` (full `clojure.lang.Sorted` support)

**Comparable to sorted-map**:
- Lookup performance (within 10%)
- Memory footprint

**Slower than sorted-map**:
- Construction from scratch (~2x)
- Sequential insert/delete (~2x)

### Performance Ratios at N=500K

| Operation | ordered-map vs sorted-map | ordered-set vs sorted-set |
|-----------|---------------------------|---------------------------|
| Construction | 2.2x slower | **0.75x faster** |
| Insert | 2.1x slower | 1.6x slower |
| Delete | 1.9x slower | 1.5x slower |
| Lookup | 1.08x slower | 1.21x slower |
| Iteration | **0.92x faster** | **0.64x faster** |
| Parallel fold | **1.6x faster** | **1.6x faster** |
| Split | N/A | **5x faster** |

## Running Benchmarks

### Quick Benchmarks (bench.clj)

The original benchmark suite provides fast, repeatable measurements:

```clojure
(require '[com.dean.ordered-collections.bench :as bench])

;; Full benchmark suite
(bench/run-all)

;; Quick benchmarks (N up to 10K)
(bench/run-quick)

;; Specific benchmark categories
(bench/run-map-benchmarks [10000 100000 500000])
(bench/run-set-benchmarks [10000 100000 500000])
(bench/run-specialty-benchmarks [10000 100000 500000])
(bench/run-string-benchmarks [10000 100000 500000])
(bench/run-parallel-benchmarks [10000 100000 500000 1000000])
```

### Rigorous Benchmarks (criterium_bench.clj)

For statistically rigorous measurements, use the Criterium-based suite:

```clojure
(require '[com.dean.ordered-collections.criterium-bench :as cb])

;; Quick suite (~10 minutes)
(cb/run-quick)

;; Medium suite (~20-30 minutes)
(cb/run-medium)

;; Full suite with complete statistical analysis (~45-60 minutes)
(cb/run-full)

;; Individual benchmarks with full Criterium output
(cb/bench-map-lookup 100000)
(cb/bench-set-fold 500000)
(cb/bench-subseq 100000)

;; Head-to-head comparisons
(cb/compare-lookup 100000)
(cb/compare-iteration 500000)
(cb/compare-fold 1000000)
```

Criterium provides:
- JIT warmup with automatic steady-state detection
- Multiple samples with statistical analysis (mean, std dev, percentiles)
- Outlier detection and reporting
- GC overhead estimation and correction
