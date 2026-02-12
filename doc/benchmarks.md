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
| 10,000 | 14 ms | 27 ms | **19 ms** |
| 100,000 | 192 ms | 411 ms | **219 ms** |
| 500,000 | 1.2 s | 2.7 s | **1.2 s** |

**ordered-map construction now matches sorted-map** due to parallel fold during bulk loading.

### Insert: assoc one element at a time from empty

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 14 ms | 31 ms | 31 ms |
| 100,000 | 180 ms | 421 ms | 403 ms |
| 500,000 | 1.1 s | 2.5 s | 2.5 s |

**Ratio vs sorted-map at 500K**: ordered-map 2.3x slower (use batch construction instead)

**Note on insert overhead**: Like lookup, sequential insert pays the cost of heterogeneous key support via `clojure.core/compare` dispatch. For homogeneous numeric keys, `long-ordered-map` closes the gap significantly.

### Delete: dissoc half the elements one at a time

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 6 ms | 15 ms | 13 ms |
| 100,000 | 113 ms | 208 ms | 199 ms |
| 500,000 | 642 ms | 1.3 s | 1.2 s |

**Ratio vs sorted-map at 500K**: ordered-map 1.9x slower

### Lookup: 10,000 random lookups on map of size N

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 5.8 ms | 7.9 ms | 7.8 ms |
| 100,000 | 8.5 ms | 11.8 ms | 10.7 ms |
| 500,000 | 13.8 ms | 15.2 ms | 15.0 ms |

**Ratio vs sorted-map at 500K**: ordered-map 1.08x slower (~equal)

**Note on lookup overhead**: By default, `ordered-map` supports heterogeneous keys—you can mix types freely. This flexibility requires `clojure.core/compare` dispatch on every comparison. For homogeneous numeric keys, use `long-ordered-map` which uses primitive `Long/compare` and is **20% faster** than `sorted-map`.

### Iteration: reduce over all N entries

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 2.0 ms | 1.5 ms | 2.1 ms |
| 100,000 | 23 ms | 16 ms | 21 ms |
| 500,000 | 121 ms | 95 ms | 120 ms |

**Ratio vs sorted-map at 500K**: ordered-map ~equal

### Seq Iteration: traverse via (seq m)

| N | sorted-map | data.avl | ordered-map |
|---|------------|----------|-------------|
| 10,000 | 2.0 ms | 2.9 ms | 2.5 ms |
| 100,000 | 27 ms | 32 ms | 34 ms |
| 500,000 | 136 ms | 173 ms | 168 ms |

**Ratio vs sorted-map at 500K**: ordered-map 23% slower (significantly improved from previous 2x overhead)

Note: Seq iteration now uses efficient direct ISeq implementations (`KeySeq`/`EntrySeq`) that avoid lazy-seq and `map` wrapper overhead.

## Set Benchmarks

### Construction: Build from N random elements

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 17 ms | 28 ms | **18 ms** |
| 100,000 | 248 ms | 390 ms | **212 ms** |
| 500,000 | 1.5 s | 2.5 s | **1.2 s** |

**ordered-set construction is 25% faster than sorted-set** due to parallel fold during bulk loading.

### Insert: conj one element at a time from empty

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 22 ms | 39 ms | 35 ms |
| 100,000 | 289 ms | 508 ms | 430 ms |
| 500,000 | 1.6 s | 2.5 s | 2.5 s |

**Sequential insert is 1.6x slower than sorted-set** (use batch construction instead)

**Note on insert overhead**: Like lookup, sequential insert pays the cost of heterogeneous key support via `clojure.core/compare` dispatch. For homogeneous numeric keys, `long-ordered-set` closes the gap significantly.

### Delete: disj half the elements one at a time

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 10 ms | 16 ms | 15 ms |
| 100,000 | 146 ms | 223 ms | **200 ms** |
| 500,000 | 870 ms | 1.4 s | **1.2 s** |

**ordered-set delete is 14% faster than data.avl**

### Lookup: 10,000 random contains? checks

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 6.7 ms | 9.7 ms | 8.9 ms |
| 100,000 | 9.0 ms | 12.0 ms | 11.0 ms |
| 500,000 | 14.2 ms | 17.7 ms | **15.2 ms** |

**ordered-set lookup is 14% faster than data.avl, 7% slower than sorted-set**

**Note on lookup overhead**: By default, `ordered-set` supports heterogeneous keys—you can mix types freely. This flexibility requires `clojure.core/compare` dispatch on every comparison. For homogeneous numeric keys, use `long-ordered-set` which uses primitive `Long/compare` and is **20% faster** than `sorted-set`.

### Iteration: reduce over all N elements

| N | sorted-set | data.avl | ordered-set |
|---|------------|----------|-------------|
| 10,000 | 1.5 ms | 0.9 ms | 1.3 ms |
| 100,000 | 17 ms | 11 ms | 14 ms |
| 500,000 | 95 ms | 56 ms | **82 ms** |

**ordered-set iteration is 14% faster than sorted-set** via `IReduceInit`.

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

### First/Last Element Access: 1,000 first/last calls

| N | sorted-set | data.avl | ordered-set | speedup vs sorted-set |
|---|------------|----------|-------------|----------------------|
| 1,000 | 192 ms | 335 ms | **3.0 ms** | 64x |
| 10,000 | 1.7 s | 3.2 s | **3.4 ms** | 500x |
| 100,000 | 17.0 s | 32.2 s | **2.4 ms** | **~7000x** |

**ordered-set first/last is O(log n)** via `java.util.SortedSet` interface, while `sorted-set` must traverse via seq (O(n) for `last`).

**Note**: Clojure's `first` on sorted-set is O(1), but `last` requires full seq traversal. ordered-set provides O(log n) access to both endpoints via the `java.util.SortedSet` interface methods `.first` and `.last`.

## Interval Tree Benchmarks

### Interval Set Construction: Build from N random intervals

| N | interval-set |
|---|--------------|
| 10,000 | 111 ms |
| 100,000 | 1.5 s |
| 500,000 | 8.7 s |

Interval tree construction includes maintaining augmented max values at each node.

### Interval Set Query: 1,000 overlap queries

| N | interval-set |
|---|--------------|
| 10,000 | 46 ms |
| 100,000 | 166 ms |
| 500,000 | 697 ms |

Queries return all intervals that overlap with the query interval. Query time scales with both tree size and number of matching intervals.

### Interval Map Construction

| N | interval-map |
|---|--------------|
| 10,000 | 106 ms |
| 100,000 | 1.5 s |
| 500,000 | 8.7 s |

### Interval Map Query: 1,000 overlap queries

| N | interval-map |
|---|--------------|
| 10,000 | 43 ms |
| 100,000 | 176 ms |
| 500,000 | 722 ms |

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
- Bulk construction (25% faster than sorted-set via parallel fold)
- Set operations: union, intersection, difference (5-9x faster than clojure.set)
- First/last element access (~7000x faster than sorted-set at scale)
- Parallel fold operations (2.3x faster via `r/fold`)
- Split operations (4.5x faster than data.avl)
- Delete operations (14% faster than data.avl)
- Applications needing interval tree functionality
- Use with `subseq`/`rsubseq` (full `clojure.lang.Sorted` support)

**Comparable to**:
- Lookup performance (7% slower than sorted-set with default comparator, 14% faster than data.avl)
- Iteration via reduce (14% faster than sorted-set)

**Slower than sorted-set**:
- Sequential insert (~1.6x) — use batch construction instead

**Note on heterogeneous key support**: The default `ordered-set` supports mixed key types, requiring `clojure.core/compare` dispatch on every comparison. This affects both lookup and insert performance. For homogeneous collections, use `long-ordered-set` (20% faster than sorted-set for both operations) or `string-ordered-set` (5% faster).

### When to use ordered-map

**Best for**:
- Bulk construction (matches sorted-map via parallel fold)
- Applications needing consistent API with ordered-set
- Interval map functionality
- `subseq`/`rsubseq` support
- Homogeneous numeric keys (`long-ordered-map` is 20% faster than sorted-map)

**Trade-offs**:
- Sequential insert 2.3x slower than sorted-map with default comparator (heterogeneous key support); use batch construction or `long-ordered-map` for numeric keys
- Lookup 8% slower than sorted-map with default comparator (heterogeneous key support); use `long-ordered-map` for numeric keys to beat sorted-map by 20%

### Performance Ratios at N=500K

**ordered-set vs alternatives:**

| Operation | vs sorted-set | vs data.avl |
|-----------|---------------|-------------|
| Construction | **1.25x faster** | **2.1x faster** |
| Insert (heterogeneous) | 1.56x slower | same |
| Insert (long-ordered-set) | ~equal | **1.56x faster** |
| Delete | 1.38x slower | **1.17x faster** |
| Lookup (heterogeneous) | 1.07x slower | **1.16x faster** |
| Lookup (long-ordered-set) | **1.20x faster** | **1.40x faster** |
| Iteration | **1.16x faster** | 1.46x slower |
| First/last | **~7000x faster** | same |
| Parallel fold | **2.3x faster** | **4.0x faster** |
| Split | N/A | **4.5x faster** |
| Union | **5.8x faster** vs clojure.set | — |
| Intersection | **5.3x faster** vs clojure.set | — |
| Difference | **8.6x faster** vs clojure.set | — |

*Heterogeneous insert/lookup uses `clojure.core/compare` for mixed-type support. For homogeneous numeric keys, `long-ordered-set` uses primitive `Long/compare` and beats `sorted-set`.*

**ordered-map vs alternatives:**

| Operation | vs sorted-map | vs data.avl |
|-----------|---------------|-------------|
| Construction | **equal** | **2.3x faster** |
| Insert (heterogeneous) | 2.27x slower | same |
| Insert (long-ordered-map) | ~equal | **2.27x faster** |
| Delete | 1.87x slower | **1.08x faster** |
| Lookup (heterogeneous) | 1.08x slower | **1.01x faster** |
| Lookup (long-ordered-map) | **1.20x faster** | **1.25x faster** |
| Iteration | ~equal | 1.26x slower |

*Heterogeneous insert/lookup uses `clojure.core/compare` for mixed-type support. For homogeneous numeric keys, `long-ordered-map` uses primitive `Long/compare` and beats `sorted-map`.*

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
(bench/run-interval-benchmarks [10000 100000 500000])
(bench/run-specialty-benchmarks [10000 100000 500000])
(bench/bench-first-last-access [10000 100000])
(bench/run-string-benchmarks [10000 100000 500000])
(bench/run-parallel-benchmarks [10000 100000 500000])
```
