# Performance Benchmarks

## Test Environment

| Component | Version |
|-----------|---------|
| JVM | OpenJDK 25.0.1 |
| Clojure | 1.12.4 |
| Hardware | Intel i9 |
| Memory | 32 GB |
| OS | macOS |

**Methodology**: Benchmarks use [Criterium](https://github.com/hugoduncan/criterium) for statistically valid JVM measurements with automatic JIT warmup, multiple samples, and outlier detection. All collections are built from shuffled data to avoid best-case insertion patterns.

**Note**: Results will vary by system. Relative performance ratios are more meaningful than absolute times.

**Reproducibility**: Run `(require '[com.dean.ordered-collections.criterium-bench :as cb])` then `(cb/run-all :sizes [500000] :quick true)` to reproduce these benchmarks.

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
| 500,000 | 890 ms | 604 ms | **371 ms** |

**ordered-set construction is 2.4x faster than sorted-set** (and 1.6x faster than data.avl) due to parallel fold during bulk loading.

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
| 500,000 | 55 ms | **10.1 ms** | 16.2 ms |

**ordered-set iteration is 3.4x faster than sorted-set** via `IReduceInit`. data.avl is fastest at pure iteration.

## Parallel Fold Benchmarks (r/fold)

All collection types implement `clojure.core.reducers/CollFold` for efficient parallel reduction.

### Set Parallel Fold: r/fold

| N | sorted-set | data.avl | ordered-set | speedup vs sorted-set |
|---|------------|----------|-------------|----------------------|
| 10,000 | 1.5 ms | 3.1 ms | 2.0 ms | 0.8x |
| 100,000 | 15 ms | 31 ms | 10 ms | **1.5x** |
| 500,000 | 60.3 ms | 13.0 ms | **4.1 ms** | **14.8x** |

**ordered-set parallel fold is 14.8x faster than sorted-set** and **3.2x faster than data.avl** at N=500K. Both sorted-set and data.avl fall back to sequential reduce; only ordered-set uses true parallel fork-join.

### Reduce vs Fold Comparison (ordered-set)

| N | reduce | r/fold | speedup |
|---|--------|--------|---------|
| 500,000 | 16.2 ms | 4.1 ms | **4.0x** |

Note: `r/fold` provides significant speedup via true parallel fork-join execution.

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

These benchmarks compare `dean/union`, `dean/intersection`, and `dean/difference` against `clojure.set` equivalents on sorted-set and data.avl.

### Union: Merge two sets of size N each (50% overlap)

| N | sorted-set | data.avl | ordered-set | speedup |
|---|------------|----------|-------------|---------|
| 10,000 | 24 ms | 31 ms | 4 ms | **6-8x** |
| 100,000 | 210 ms | 270 ms | 38 ms | **5.5-7x** |
| 500,000 | 288 ms | 371 ms | **38 ms** | **7.6-10x** |

### Intersection: Find common elements in two sets of size N each (50% overlap)

| N | sorted-set | data.avl | ordered-set | speedup |
|---|------------|----------|-------------|---------|
| 10,000 | 18 ms | 22 ms | 3 ms | **6-7x** |
| 100,000 | 175 ms | 140 ms | 32 ms | **4.4-5.5x** |
| 500,000 | 217 ms | 176 ms | **35 ms** | **5.0-6.2x** |

### Difference: Remove elements of one set from another (50% overlap)

| N | sorted-set | data.avl | ordered-set | speedup |
|---|------------|----------|-------------|---------|
| 10,000 | 19 ms | 15 ms | 2 ms | **7.5-9.5x** |
| 100,000 | 191 ms | 145 ms | 22 ms | **6.6-8.7x** |
| 500,000 | 211 ms | 144 ms | **29 ms** | **5.0-7.3x** |

**ordered-set set operations are 5-10x faster than clojure.set on sorted-set/data.avl** due to parallel divide-and-conquer algorithms that exploit tree structure.

## Specialty Operations

### Rank Access: nth element by index (10,000 lookups)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 500,000 | 2.48 ms | 2.64 ms |

**Verdict:** Both use O(log n) tree descent with subtree sizes. Performance is now essentially equal (within 6%).

### Rank Lookup: rank-of element (10,000 lookups)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 500,000 | 7.0 ms | 9.5 ms |

**Verdict:** ordered-set is ~35% slower due to dynamic binding overhead for comparator. Both are O(log n).

### Split Operations: split set at random key (100 ops)

| N | data.avl | ordered-set |
|---|----------|-------------|
| 10,000 | 4.7 ms | **1.8 ms** |
| 100,000 | 8.9 ms | **2.1 ms** |
| 500,000 | 1.5 ms | **0.49 ms** |

**ordered-set split is 3x faster than data.avl** due to efficient tree splitting algorithm.

### First/Last Element Access: 1,000 first/last calls

| N | sorted-set last | data.avl last | ordered-set last | speedup vs sorted-set |
|---|-----------------|---------------|------------------|----------------------|
| 1,000 | 192 ms | 335 ms | **3.0 ms** | 64x |
| 10,000 | 1.7 s | 3.2 s | **3.4 ms** | 500x |
| 100,000 | 7.98 s | 9.11 s | **0.26 ms** | **~31,000x** |
| 500,000 | 35.9 s | 47.8 s | **0.39 ms** | **~92,000x** |

**ordered-set first/last is O(log n)** via `java.util.SortedSet` interface, while `sorted-set` and `data.avl` must traverse via seq (O(n) for `last`).

**Note**: Clojure's `first` on sorted-set is O(1), but `last` requires full seq traversal. ordered-set provides O(log n) access to both endpoints via the `java.util.SortedSet` interface methods `.first` and `.last`.

## Interval Tree Benchmarks

### Interval Set Construction: Build from N random intervals

| N | interval-set |
|---|--------------|
| 10,000 | 111 ms |
| 100,000 | 332 ms |
| 500,000 | 2.4 s |

Interval tree construction includes maintaining augmented max values at each node.

### Interval Set Query: 10,000 point queries

| N | interval-set |
|---|--------------|
| 10,000 | 46 ms |
| 100,000 | 147 ms |
| 500,000 | 179 ms |

Queries return all intervals that overlap with the query point. Query time scales with both tree size and number of matching intervals.

### Interval Map Construction

| N | interval-map |
|---|--------------|
| 10,000 | 106 ms |
| 100,000 | 409 ms |
| 500,000 | 2.9 s |

### Interval Map Query: 10,000 point queries

| N | interval-map |
|---|--------------|
| 10,000 | 43 ms |
| 100,000 | 176 ms |
| 500,000 | 179 ms |

### Interval Set Fold

| N | reduce | r/fold (parallel) |
|---|--------|-------------------|
| 500,000 | 23 ms | 27 ms |

Note: Interval sets support `r/fold` for parallel reduction.

## String Keys (Custom Comparator)

### Construction

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 16 ms | 31 ms | 38 ms |
| 100,000 | 217 ms | 436 ms | 507 ms |
| 500,000 | 960 ms | 1.0 s | **439 ms** |

**ordered-map with strings is 2.2x faster than sorted-map-by** at N=500K via parallel batch construction.

### Lookup

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 9.7 ms | 11.3 ms | 15.6 ms |
| 100,000 | 12.8 ms | 15.5 ms | 20.1 ms |
| 500,000 | 14.3 ms | 10.2 ms | 12.3 ms |

**Lookup is competitive**: ordered-map is 14% faster than sorted-map-by, 20% slower than data.avl at N=500K.

### Iteration

| N | sorted-map-by | data.avl | ordered-map |
|---|---------------|----------|-------------|
| 10,000 | 2.1 ms | 1.8 ms | 2.3 ms |
| 100,000 | 27 ms | 21 ms | 26 ms |
| 500,000 | 111 ms | 35 ms | **34 ms** |

**ordered-map iteration matches data.avl** and is 3.3x faster than sorted-map-by at N=500K.

## Summary

### When to use ordered-set

**Best for**:
- Bulk construction (2.4x faster than sorted-set, 1.6x faster than data.avl)
- Set operations: union, intersection, difference (5-10x faster than clojure.set)
- First/last element access (~31,000x faster at N=100K, ~92,000x at N=500K)
- Parallel fold operations (14.8x faster vs sorted-set, 3.2x faster vs data.avl at N=500K)
- Split operations (3x faster than data.avl)
- Iteration via reduce (3.4x faster than sorted-set at N=500K)
- Applications needing interval tree functionality
- Use with `subseq`/`rsubseq` (full `clojure.lang.Sorted` support)

**Comparable to**:
- Lookup performance (7% slower than sorted-set with default comparator, 14% faster than data.avl)

**Slower than**:
- Sequential insert (~1.6x vs sorted-set) — use batch construction instead
- Pure iteration vs data.avl (data.avl is fastest at iteration)

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
| Construction | **2.4x faster** | **1.6x faster** |
| Insert (heterogeneous) | 1.56x slower | same |
| Insert (long-ordered-set) | ~equal | **1.56x faster** |
| Delete | 1.38x slower | **1.17x faster** |
| Lookup (heterogeneous) | 1.07x slower | **1.16x faster** |
| Lookup (long-ordered-set) | **1.20x faster** | **1.40x faster** |
| Iteration | **3.4x faster** | 1.6x slower |
| First/last | **~92,000x faster** | **~122,000x faster** |
| Parallel fold | **14.8x faster** | **3.2x faster** |
| Split | N/A | **3x faster** |
| Union | **7.6x faster** | **10x faster** |
| Intersection | **6.2x faster** | **5.0x faster** |
| Difference | **7.3x faster** | **5.0x faster** |

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

### Criterium Benchmarks (Recommended for Reproducibility)

The Criterium suite provides statistically rigorous benchmarks with JIT warmup, GC correction, and confidence intervals:

```clojure
(require '[com.dean.ordered-collections.criterium-bench :as cb])

;; Run with quick-bench for faster iteration
(cb/with-quick-bench
  (cb/bench-set-fold 500000))

;; Run full Criterium analysis (slower but more accurate)
(cb/bench-set-construction 500000)
(cb/bench-set-fold 500000)
(cb/bench-first-last 500000)
(cb/bench-set-iteration 500000)

;; Set operations comparison
(cb/with-quick-bench
  (cb/run-set-operations-benchmarks 500000))

;; Full suite (30-60 minutes)
(cb/run-all :sizes [100000 500000])
```

All benchmarks in this document are reproducible using the Criterium suite. Results may vary by hardware but relative ratios should be consistent.

### Quick Benchmarks (bench.clj)

The quick benchmark suite provides fast, repeatable measurements for development:

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
