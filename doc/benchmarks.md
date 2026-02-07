# Performance Benchmarks

## Test Environment

| Component | Version |
|-----------|---------|
| JVM | OpenJDK 25.0.1 |
| Clojure | 1.12.4 |
| Hardware | Intel Core i9 (16 cores) |
| Memory | 32 GB |
| OS | macOS |

**Methodology**: Each benchmark runs 3 warmup iterations followed by 5 timed iterations. Results shown are the mean of timed iterations. All collections are built from shuffled data to avoid best-case insertion patterns.

**Note**: Results will vary by system. Relative performance ratios are more meaningful than absolute times.

## Libraries Compared

- **sorted-map / sorted-set**: Clojure's built-in Red-Black tree implementations
- **data.avl**: `clojure.data.avl` AVL tree library (version 0.1.0)
- **ordered-map / ordered-set**: This library's persistent weight-balanced trees

## Map Benchmarks

### Construction: Build from N random key-value pairs

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 19 ms | 52 ms | 41 ms |
| 100,000 | 263 ms | 507 ms | 452 ms |
| 500,000 | 1.2 s | 2.7 s | 2.5 s |

**Ratio vs sorted-map at 500K**: ordered-map 2.1x slower

### Insert: assoc one element at a time from empty

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 13 ms | 31 ms | 29 ms |
| 100,000 | 178 ms | 408 ms | 402 ms |
| 500,000 | 1.1 s | 2.5 s | 2.4 s |

**Ratio vs sorted-map at 500K**: ordered-map 2.2x slower

### Delete: dissoc half the elements one at a time

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 6 ms | 16 ms | 15 ms |
| 100,000 | 114 ms | 203 ms | 204 ms |
| 500,000 | 649 ms | 1.3 s | 1.2 s |

**Ratio vs sorted-map at 500K**: ordered-map 1.8x slower

### Lookup: 10,000 random lookups on map of size N

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 6.2 ms | 9.1 ms | 8.3 ms |
| 100,000 | 8.5 ms | 11.8 ms | 11.1 ms |
| 500,000 | 13.6 ms | 17.1 ms | 16.2 ms |

**Ratio vs sorted-map at 500K**: ordered-map 1.19x slower

### Iteration: reduce over all N entries

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 2.3 ms | 1.5 ms | 2.3 ms |
| 100,000 | 22 ms | 17 ms | 21 ms |
| 500,000 | 119 ms | 91 ms | 124 ms |

### Seq Iteration: traverse via (seq m)

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 2.0 ms | 3.0 ms | 5.0 ms |
| 100,000 | 27 ms | 31 ms | 49 ms |
| 500,000 | 134 ms | 165 ms | 269 ms |

Note: Seq iteration is slower because it uses the lazy enumerator path, not the optimized `IReduceInit` path.

## Set Benchmarks

### Construction: Build from N random elements

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 16 ms | 27 ms | **18 ms** |
| 100,000 | 242 ms | 358 ms | **222 ms** |
| 500,000 | 1.5 s | 2.5 s | **1.2 s** |

**ordered-set construction is 20% faster than sorted-set** due to parallel fold during bulk loading.

### Insert: conj one element at a time from empty

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 19 ms | 31 ms | 31 ms |
| 100,000 | 245 ms | 404 ms | 399 ms |
| 500,000 | 1.6 s | 2.5 s | 2.5 s |

### Delete: disj half the elements one at a time

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 10 ms | 16 ms | 16 ms |
| 100,000 | 148 ms | 217 ms | **195 ms** |
| 500,000 | 840 ms | 1.3 s | **1.2 s** |

**ordered-set delete is 10% faster than data.avl**

### Lookup: 10,000 random contains? checks

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 6.8 ms | 9.8 ms | 9.1 ms |
| 100,000 | 8.6 ms | 11.8 ms | 11.6 ms |
| 500,000 | 12.0 ms | 16.4 ms | **15.1 ms** |

**ordered-set lookup is 8% faster than data.avl**

### Iteration: reduce over all N elements

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 1.5 ms | 1.0 ms | 1.4 ms |
| 100,000 | 17 ms | 9 ms | 14 ms |
| 500,000 | 96 ms | 53 ms | 81 ms |

**ordered-set iteration is 16% faster than sorted-set** via `IReduceInit`.

## Parallel Fold Benchmarks (r/fold)

All collection types implement `clojure.core.reducers/CollFold` for efficient parallel reduction.

### Set Parallel Fold: r/fold with chunk size 512

| N | sorted-set | data.avl | ordered-set | speedup vs sorted-set |
|---|------------|----------|-------------|----------------------|
| 10,000 | 1.5 ms | 3.1 ms | 2.0 ms | 0.8x |
| 100,000 | 15 ms | 31 ms | 10 ms | **1.5x** |
| 500,000 | 98 ms | 170 ms | **42 ms** | **2.3x** |

**ordered-set parallel fold is 2.3x faster than sorted-set** at scale.

### Reduce vs Fold Comparison (ordered-set)

| N | reduce | r/fold | speedup |
|---|--------|--------|---------|
| 10,000 | 1.5 ms | 1.1 ms | 1.4x |
| 100,000 | 14 ms | 12 ms | 1.2x |
| 500,000 | 80 ms | 44 ms | **1.8x** |

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

## Set Operations (Union, Intersection, Difference)

These benchmarks compare `dean/union`, `dean/intersection`, and `dean/difference` against `clojure.set` equivalents.

### Union: Merge two sets of size N/2 each (50% overlap)

| N | clojure.set | ordered-set | speedup |
|---|-------------|-------------|---------|
| 10,000 | 24 ms | 4 ms | **6.0x** |
| 100,000 | 210 ms | 38 ms | **5.5x** |
| 500,000 | 1.1 s | 190 ms | **5.8x** |

### Intersection: Find common elements in two sets of size N/2 each (50% overlap)

| N | clojure.set | ordered-set | speedup |
|---|-------------|-------------|---------|
| 10,000 | 18 ms | 3 ms | **6.0x** |
| 100,000 | 175 ms | 32 ms | **5.5x** |
| 500,000 | 870 ms | 164 ms | **5.3x** |

### Difference: Remove elements of one set from another (50% overlap)

| N | clojure.set | ordered-set | speedup |
|---|-------------|-------------|---------|
| 10,000 | 19 ms | 2 ms | **9.5x** |
| 100,000 | 191 ms | 22 ms | **8.7x** |
| 500,000 | 977 ms | 114 ms | **8.6x** |

**ordered-set set operations are 5-9x faster than clojure.set** due to divide-and-conquer algorithms that exploit tree structure.

## Specialty Operations

### Rank Access: nth element by index (10,000 lookups)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 10,000 | 3.3 ms | 18 ms |
| 100,000 | 4.3 ms | 18 ms |
| 500,000 | 5.5 ms | 21 ms |

data.avl has O(1) rank access via cached ranks; ordered-set uses O(log n) tree descent.

### Rank Lookup: rank-of element (10,000 lookups)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 10,000 | 11 ms | 24 ms |
| 100,000 | 14 ms | 27 ms |
| 500,000 | 19 ms | 29 ms |

### Split Operations: split set at random key (100 ops)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 10,000 | 4.7 ms | **1.8 ms** |
| 100,000 | 8.9 ms | **2.1 ms** |
| 500,000 | 11.2 ms | **2.5 ms** |

**ordered-set split is 4.5x faster than data.avl** due to efficient tree splitting algorithm.

## String Keys (Custom Comparator)

### Construction

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 16 ms | 31 ms | 38 ms |
| 100,000 | 217 ms | 436 ms | 507 ms |
| 500,000 | 1.5 s | 2.9 s | 3.1 s |

### Lookup

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 9.7 ms | 11.3 ms | 15.6 ms |
| 100,000 | 12.8 ms | 15.5 ms | 20.1 ms |
| 500,000 | 19.0 ms | 20.9 ms | 27.5 ms |

### Iteration

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 2.1 ms | 1.8 ms | 2.3 ms |
| 100,000 | 27 ms | 21 ms | 26 ms |
| 500,000 | 143 ms | 126 ms | 155 ms |

## Summary

### When to use ordered-set

**Best for**:
- Set operations: union, intersection, difference (5-9x faster than clojure.set)
- Bulk construction (20% faster than sorted-set)
- Parallel fold operations (2.3x faster via `r/fold`)
- Split operations (4.5x faster than data.avl)
- Delete operations (10% faster than data.avl)
- Applications needing interval tree functionality
- Use with `subseq`/`rsubseq` (full `clojure.lang.Sorted` support)

**Comparable to**:
- Lookup performance (within 10% of data.avl)
- Iteration via reduce (faster than sorted-set)

**Slower than sorted-set**:
- Sequential insert (~1.6x)

### When to use ordered-map

**Best for**:
- Applications needing consistent API with ordered-set
- Interval map functionality
- `subseq`/`rsubseq` support

**Trade-offs**:
- Construction and mutation slower than sorted-map (~2x)
- Lookup slightly slower (~1.2x)

### Performance Ratios at N=500K

| Operation | ordered-set vs sorted-set | ordered-set vs data.avl |
|-----------|---------------------------|-------------------------|
| Construction | **0.80x faster** | **0.48x faster** |
| Insert | 1.56x slower | same |
| Delete | 1.43x slower | **0.92x faster** |
| Lookup | 1.26x slower | **0.92x faster** |
| Iteration | **0.84x faster** | 1.51x slower |
| Parallel fold | **2.3x faster** | **4.0x faster** |
| Split | N/A | **4.5x faster** |
| Union | **5.8x faster** vs clojure.set | — |
| Intersection | **5.3x faster** vs clojure.set | — |
| Difference | **8.6x faster** vs clojure.set | — |

## Running Benchmarks

### Quick Benchmarks (bench.clj)

The benchmark suite provides fast, repeatable measurements:

```clojure
(require '[com.dean.ordered-collections.bench :as bench])

;; Full benchmark suite
(bench/run-all)

;; Quick benchmarks (N up to 10K)
(bench/run-quick)

;; Specific benchmark categories
(bench/run-map-benchmarks [10000 100000 500000])
(bench/run-set-benchmarks [10000 100000 500000])
(bench/run-set-operations-benchmarks [10000 100000 500000])
(bench/run-specialty-benchmarks [10000 100000 500000])
(bench/run-string-benchmarks [10000 100000 500000])
(bench/run-parallel-benchmarks [10000 100000 500000])
```
