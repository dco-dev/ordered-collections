# Performance Analysis

This document provides a detailed analysis of the performance characteristics of ordered-collections compared to Clojure's built-in sorted collections and clojure.data.avl.

## Executive Summary

*All benchmarks performed using [Criterium](https://github.com/hugoduncan/criterium) on JDK 25, Apple M1 Pro.*

### Performance at Scale (N=500,000)

The library's advantages grow with collection size. At N=500,000:

| Operation | sorted-set | data.avl | ordered-set | vs sorted | vs avl |
|-----------|------------|----------|-------------|-----------|--------|
| Last element (1000 calls) | 35.9s | 47.8s | **0.39ms** | **~92,000x** | **~122,000x** |
| Union (50% overlap) | 321ms | 376ms | **40ms** | **8x** | **9x** |
| Intersection | 213ms | 172ms | **36ms** | **6x** | **5x** |
| Difference | 213ms | 149ms | **31ms** | **7x** | **5x** |
| Reduce | 57ms | 11ms | **17ms** | **3.4x** | — |

### Parallel Fold (r/fold)

| N | sorted-set | data.avl | ordered-set | vs sorted | vs avl |
|---|------------|----------|-------------|-----------|--------|
| 500,000 | 54ms | 11ms | **3.4ms** | **16x** | **3.2x** |
| 1,000,000 | 71ms | 18ms | **7.2ms** | **10x** | **2.5x** |
| 2,000,000 | 197ms | 45ms | **15ms** | **13x** | **3x** |

ordered-set implements true parallel `r/fold` via tree-based fork-join. sorted-set and data.avl fall back to sequential reduce.

### Lookup Performance (N=100,000)

| Type | Time (10K lookups) | vs sorted-set |
|------|-------------------|---------------|
| sorted-set | 2.93ms | baseline |
| data.avl | ~3ms | on par |
| `ordered-set` | 2.80ms | on par |
| `long-ordered-set` | 2.11ms | **28% faster** |

**Bottom line**:
- For large-scale set operations, ordered-collections is **5-9x faster** than both sorted-set and data.avl
- For `last` element access, it's **100,000x+ faster** at scale (O(log n) vs O(n))
- For parallel fold, ordered-collections is **10-16x faster** than sorted-set and **2.5-3x faster** than data.avl
- For lookup-intensive workloads with Long keys, use `long-ordered-set`

## Construction Performance

### Parallel Fold Construction

All ordered-collections constructors use `clojure.core.reducers/fold` for parallel construction:

```clojure
;; Internal implementation pattern
(r/fold chunk-size
        (fn
          ([] (node/leaf))
          ([n0 n1] (tree/node-set-union n0 n1)))
        (fn [n elem] (tree/node-add n elem))
        coll)
```

This divides the input collection into chunks, builds subtrees in parallel, and merges them using the efficient `node-set-union` operation.

### Benchmark Results (N = 500,000)

| Type | sorted-* | data.avl | ordered-* | Speedup |
|------|----------|----------|-----------|---------|
| Set | 1.5s | 2.5s | **1.2s** | 1.25x faster |
| Map | 1.2s | 2.7s | **1.2s** | equal |

### Why It Works

1. **Parallel chunk building**: Each thread builds a small tree from its chunk
2. **Efficient tree merging**: `node-set-union` is O(m log(n/m)) for merging trees of size m and n
3. **Work stealing**: Fork-join pool balances load across cores

### When to Use Batch Construction

```clojure
;; FAST: Use constructor with collection
(def s (ordered-set (range 1000000)))      ;; 1.2s
(def m (ordered-map (map #(vector % %) (range 1000000))))  ;; 1.2s

;; SLOW: Sequential insert
(def s (reduce conj (ordered-set) (range 1000000)))  ;; 2.5s
(def m (reduce #(assoc %1 %2 %2) (ordered-map) (range 1000000)))  ;; 2.5s
```

## Lookup Performance

Lookup performance depends on the comparator used.

### Benchmark Results (10,000 lookups, N=100,000)

| Type | Time | vs sorted-set |
|------|------|---------------|
| `sorted-set` | 2.93ms | baseline |
| `ordered-set` | 2.80ms | on par |
| `long-ordered-set` | 2.11ms | **28% faster** |

### Why the Difference?

1. **Specialized comparators**: `long-ordered-set` uses primitive `Long/compare` directly
2. **Generic comparator**: `ordered-set` uses flexible `clojure.core/compare` (handles mixed types)

### Specialized Constructors

| Key Type | Constructor | Performance |
|----------|-------------|-------------|
| Long | `long-ordered-set` / `long-ordered-map` | **28% faster** than sorted-set |
| Double | `double-ordered-set` / `double-ordered-map` | On par with sorted-set |
| String | `string-ordered-set` / `string-ordered-map` | On par with sorted-set |
| Custom | `ordered-set-with` / `ordered-map-with` | Pass your own Comparator |

### Recommendation

Use specialized constructors when your key type is known:

```clojure
;; For Long keys - 28% faster than sorted-set
(def s (long-ordered-set data))

;; For String keys
(def s (string-ordered-set data))

;; For Double keys
(def s (double-ordered-set data))

;; For custom comparators (pass java.util.Comparator directly)
(def s (ordered-set-with my-comparator data))

;; Generic ordered-set is on par with sorted-set
(def s (ordered-set data))
```

## First/Last Element Access

The most dramatic performance difference—grows with collection size due to O(log n) vs O(n) complexity.

### Benchmark Results (last element)

| N | sorted-set | data.avl | ordered-set | vs sorted | vs avl |
|---|------------|----------|-------------|-----------|--------|
| 100,000 (1K calls) | 7.98s | 9.11s | **256µs** | **31,000x** | **36,000x** |
| 500,000 (1K calls) | 35.9s | 47.8s | **0.39ms** | **~92,000x** | **~122,000x** |

### Why the Difference?

| Collection | first | last | Complexity |
|------------|-------|------|------------|
| sorted-set | O(1) via seq | O(n) via seq | Must traverse entire sequence |
| data.avl | O(1) via seq | O(n) via seq | Must traverse entire sequence |
| ordered-set | O(log n) | O(log n) | Direct tree navigation |

```clojure
;; sorted-set & data.avl: (last s) must realize entire lazy sequence
(last sorted-set-with-500k-elements)  ;; 39.8ms per call
(last avl-set-with-500k-elements)     ;; 46.0ms per call

;; ordered-set: Direct tree descent
(.last ^java.util.SortedSet ordered-set-with-500k-elements)  ;; 0.34µs per call
```

### Implementation

ordered-set implements `java.util.SortedSet`, providing O(log n) `.first` and `.last` methods that directly navigate to the leftmost/rightmost nodes. Neither sorted-set nor data.avl provide this optimization.

## Parallel Fold Performance

ordered-collections implements `clojure.core.reducers/CollFold` for true parallel reduction.

### Benchmark Results (N = 500,000)

| Operation | sorted-set | ordered-set | Speedup |
|-----------|------------|-------------|---------|
| reduce | 95ms | 82ms | 1.16x |
| r/fold | 95ms* | **42ms** | **2.3x** |

*sorted-set falls back to sequential reduce

### Implementation

```clojure
clojure.core.reducers.CollFold
(coll-fold [this n combinef reducef]
  (tree/node-chunked-fold n root combinef
    (fn [acc node] (reducef acc (node/-k node)))))
```

The tree is split into chunks of size n, each chunk is reduced in parallel, and results are combined using `combinef`.

## Set Operations

Divide-and-conquer algorithms with parallel execution provide **5-9x speedups** at scale.

### Benchmark Results at N=500,000 (two sets with 50% overlap)

| Operation | sorted-set | data.avl | ordered-set | vs sorted | vs avl |
|-----------|------------|----------|-------------|-----------|--------|
| Union | 321ms | 376ms | **40ms** | **8x** | **9x** |
| Intersection | 213ms | 172ms | **36ms** | **6x** | **5x** |
| Difference | 213ms | 149ms | **31ms** | **7x** | **5x** |

### At N=100,000

| Operation | sorted-set | data.avl | ordered-set | vs sorted | vs avl |
|-----------|------------|----------|-------------|-----------|--------|
| Union | 60ms | 60ms | **13ms** | 4.6x | 4.6x |
| Intersection | 46ms | 31ms | **13ms** | 3.5x | 2.4x |
| Difference | 55ms | 31ms | **10ms** | 5.5x | 3.1x |

**Performance advantages grow with collection size** because the parallel threshold (65,536 combined elements) enables fork-join parallelism. At N=500K, the speedup is roughly double that of N=100K.

### Why It's Faster

Both `sorted-set` and `data.avl` fall back to `clojure.set` which uses linear reduce:
```clojure
(reduce conj s1 s2)  ;; O(m * log(n+m))
```

**ordered-set approach** (parallel divide-and-conquer):
```clojure
;; Split s1 at root of s2, recursively union subtrees in parallel
(node-set-union-parallel s1 s2)  ;; O(m * log(n/m)) when m << n
```

For collections above 65,536 combined elements, set operations automatically use fork-join parallelism to process left and right subtrees concurrently.

## Map Merge Operations

Parallel divide-and-conquer merge for ordered maps.

### Benchmark Results (Two maps of 15,000 and 15,000 elements, 33% overlap)

| Operation | clojure.core/merge-with | ordered-merge-with | Speedup |
|-----------|------------------------|-------------------|---------|
| merge-with | ~50ms | **~10ms** | ~5x |

```clojure
(require '[com.dean.ordered-collections.core :as dean])

(def m1 (dean/ordered-map (map #(vector % %) (range 15000))))
(def m2 (dean/ordered-map (map #(vector % (* 2 %)) (range 10000 25000))))

;; Fast parallel merge
(dean/ordered-merge-with (fn [k a b] (+ a b)) m1 m2)
```

## Split Operations

3x faster than data.avl for splitting at a key.

### Benchmark Results (100 splits on N = 500,000)

| Library | Time | Speedup |
|---------|------|---------|
| data.avl | 1.5ms | 1.0x |
| ordered-set | **0.49ms** | 3x |

### Implementation

Weight-balanced trees maintain subtree sizes, enabling O(log n) split without reconstruction:

```clojure
(defn node-split [n k]
  ;; Returns [left-tree, present?, right-tree]
  ;; No node allocation during descent
  ...)
```

## Iteration Performance

All collection types have optimized iteration paths via IReduceInit.

### Benchmark Results (reduce)

| N | sorted-set | data.avl | ordered-set | vs sorted |
|---|------------|----------|-------------|-----------|
| 100,000 | 6.5ms | 1.3ms | **1.5ms** | **4.3x** |
| 500,000 | 57ms | 11ms | **17ms** | **3.4x** |

ordered-set is **3-4x faster** than sorted-set due to direct tree traversal. data.avl has a slight edge due to simpler node structure, but ordered-set provides additional features (parallel fold, O(log n) nth, set operations).

## Parallel Fold (r/fold)

ordered-set implements `clojure.core.reducers/CollFold` using a tree-based fork-join algorithm. sorted-set and data.avl fall back to sequential reduce.

### Benchmark Results

| N | sorted-set | data.avl | ordered-set | vs sorted | vs avl |
|---|------------|----------|-------------|-----------|--------|
| 500,000 | 54ms | 11ms | **3.4ms** | **16x** | **3.2x** |
| 1,000,000 | 71ms | 18ms | **7.2ms** | **10x** | **2.5x** |
| 2,000,000 | 197ms | 45ms | **15ms** | **13x** | **3x** |

ordered-set's parallel fold is **10-16x faster** than sorted-set and **2.5-3x faster** than data.avl.

### Implementation

The tree-based fold uses natural parallelism from the tree structure:
1. Below threshold (8K elements): sequential in-order traversal
2. Above threshold: fork left subtree, compute right inline, combine results

This avoids the overhead of creating intermediate sequences or offset vectors.

### Why It's Fast

1. **Direct ISeq implementation**: `KeySeq` and `EntrySeq` types implement `clojure.lang.ISeq` directly without lazy-seq or `map` wrappers
2. **IReduceInit on seq types**: Seq types also implement IReduceInit for fast reduce operations
3. **Enumerator-based traversal**: Uses stack-based tree enumerator for O(1) amortized `next`
4. **Counted seqs**: Track element count to avoid re-traversal for `count`

```clojure
(deftype KeySeq [enum cnt _meta]
  clojure.lang.ISeq
  (first [_] (-k (node-enum-first enum)))
  (next [_]
    (when-let [e (node-enum-rest enum)]
      (KeySeq. e (when cnt (unchecked-dec-int cnt)) nil)))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum acc init]
      (if e
        (let [ret (f acc (-k (node-enum-first e)))]
          (if (reduced? ret) @ret (recur (node-enum-rest e) ret)))
        acc)))
  ...)
```

## Memory Usage

Comparable to alternatives, with slight overhead for weight tracking.

| Implementation | Bytes per entry (approx) |
|----------------|--------------------------|
| sorted-map | 40-48 |
| data.avl | 48-56 |
| ordered-map | 48-56 |

The ~8 byte overhead stores subtree weights for O(log n) nth/rank operations.

## Recommendations

### Use ordered-set when working at scale (N > 100K):
- Need `last` element access (**~92,000x faster** at N=500K)
- Performing set algebra (**6-8x faster** at N=500K)
- Need reduce over large collections (**3.4x faster** at N=500K)
- Need nth/rank access (O(log n) vs O(n))

### Use long-ordered-set/long-ordered-map when:
- Working with Long keys (**28% faster** lookups than sorted-set)
- Need both fast lookup and ordered operations

### Use ordered-map when:
- Need nth/rank access (O(log n) vs O(n))
- Need consistent API with ordered-set

### Avoid ordered-* when:
- Exclusively doing sequential inserts (use batch construction instead)
- Working only with small collections (N < 1000) where overhead dominates

## Profiling Tips

For accurate benchmarking, use the Criterium-based test suite:

```clojure
(require '[com.dean.ordered-collections.criterium-bench :as cb])

;; Quick benchmark suite (~10 minutes)
(cb/run-quick)

;; Medium suite (~20-30 minutes)
(cb/run-medium)

;; Full statistical analysis (~45-60 minutes)
(cb/run-full)

;; Individual benchmarks
(cb/bench-set-union 100000)
(cb/bench-set-iteration 100000)
(cb/compare-set-operations 500000)

;; Quick mode for development
(cb/with-quick-bench
  (cb/bench-map-lookup 10000))
```

For custom benchmarks, use Criterium directly:

```clojure
(require '[criterium.core :as crit])

(crit/bench (ordered-set my-data))
(crit/quick-bench (get my-ordered-map some-key))
```
