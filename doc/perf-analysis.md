# Performance Analysis

This document provides a detailed analysis of the performance characteristics of ordered-collections compared to Clojure's built-in sorted collections and clojure.data.avl.

## Executive Summary

| Feature | ordered-set | ordered-map |
|---------|-------------|-------------|
| Construction | **25% faster** than sorted-set | **Equal** to sorted-map |
| Lookup | 7% slower | 8% slower |
| First/Last | **7000x faster** | **7000x faster** |
| Parallel fold | **2.3x faster** | **2.3x faster** |
| Set operations | **5-9x faster** | N/A |
| Split | **4.5x faster** vs data.avl | **4.5x faster** |
| Sequential insert | 1.6x slower | 2.3x slower |

**Bottom line**: Use batch construction (via constructor functions) rather than sequential `conj`/`assoc` to get the best performance. All bulk operations are faster than or equal to alternatives.

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

Lookup is within 10% of sorted-map/sorted-set across all collection sizes.

### Why the Small Difference?

1. **Tree depth**: Weight-balanced trees are slightly deeper than red-black trees
2. **Node structure**: Additional weight field adds minor overhead
3. **ArrayLeaf optimization**: For small subtrees, binary search within ArrayLeaf nodes

### Benchmark Results (10,000 lookups on N = 500,000)

| Type | sorted-* | ordered-* | Ratio |
|------|----------|-----------|-------|
| Set | 14.2ms | 15.2ms | 0.93x |
| Map | 13.8ms | 15.0ms | 0.92x |

## First/Last Element Access

The most dramatic performance difference: **~7000x faster at scale**.

### Why the Difference?

| Collection | first | last | Complexity |
|------------|-------|------|------------|
| sorted-set | O(1) via seq | O(n) via seq | Must traverse entire sequence |
| ordered-set | O(log n) | O(log n) | Direct tree navigation |

```clojure
;; sorted-set: (last s) must realize entire lazy sequence
(last sorted-set-with-100k-elements)  ;; 17 seconds for 1000 calls

;; ordered-set: Direct tree descent
(.last ^java.util.SortedSet ordered-set-with-100k-elements)  ;; 2.4ms for 1000 calls
```

### Implementation

ordered-set implements `java.util.SortedSet`, providing O(log n) `.first` and `.last` methods that directly navigate to the leftmost/rightmost nodes.

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

Divide-and-conquer algorithms with parallel execution provide 7-9x speedups over `clojure.set`.

### Benchmark Results (Two sets of 500,000 elements, 50% overlap)

| Operation | clojure.set | ordered-set | Speedup |
|-----------|-------------|-------------|---------|
| union | 1.1s | **129ms** | 7.8x |
| intersection | 870ms | **91ms** | 9.0x |
| difference | 977ms | **102ms** | 7.7x |

### Why It's Faster

**clojure.set approach** (linear):
```clojure
(reduce conj s1 s2)  ;; O(m * log(n+m))
```

**ordered-set approach** (parallel divide-and-conquer):
```clojure
;; Split s1 at root of s2, recursively union subtrees in parallel
(node-set-union-parallel s1 s2)  ;; O(m * log(n/m)) when m << n
```

For collections above 10,000 elements, set operations automatically use fork-join parallelism to process left and right subtrees concurrently.

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

4.5x faster than data.avl for splitting at a key.

### Benchmark Results (100 splits on N = 500,000)

| Library | Time | Speedup |
|---------|------|---------|
| data.avl | 10.5ms | 1.0x |
| ordered-set | **2.2ms** | 4.5x |

### Implementation

Weight-balanced trees maintain subtree sizes, enabling O(log n) split without reconstruction:

```clojure
(defn node-split [n k]
  ;; Returns [left-tree, present?, right-tree]
  ;; No node allocation during descent
  ...)
```

## Iteration Performance

ordered-set iteration is 14% faster than sorted-set via optimized `IReduceInit`.

### Benchmark Results (reduce over N = 500,000)

| Type | sorted-* | ordered-* | Speedup |
|------|----------|-----------|---------|
| Set | 95ms | **82ms** | 1.16x |
| Map | 121ms | 120ms | ~equal |

### Why Sets Are Faster

The optimized `node-iter-kv` function avoids synthetic node allocation:

```clojure
(defn node-iter-kv [n f]
  (cond
    (leaf? n) nil
    (array-leaf? n)  ;; Fast path for ArrayLeaf
    (let [ks (.ks n) vs (.vs n)]
      (dotimes [i (.size n)]
        (f (aget ks i) (aget vs i))))
    :else
    (do (node-iter-kv (-l n) f)
        (f (-k n) (-v n))
        (node-iter-kv (-r n) f))))
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

### Use ordered-set when:
- Building from collections (25% faster construction)
- Need first/last access (7000x faster)
- Performing set algebra (5-9x faster)
- Using parallel fold (2.3x faster)
- Need split operations (4.5x faster)

### Use ordered-map when:
- Building from collections (matches sorted-map)
- Need nth/rank access (O(log n) vs O(n))
- Using parallel fold (2.3x faster)
- Need consistent API with ordered-set

### Avoid ordered-* when:
- Exclusively doing sequential inserts (use batch construction instead)
- Zero dependencies required
- Lookup-only workload with no other features needed

## Profiling Tips

To profile your specific workload:

```clojure
(require '[com.dean.ordered-collections.bench :as bench])

;; Quick benchmark
(bench/run-quick)

;; Specific sizes
(bench/run-map-benchmarks [10000 100000])
(bench/run-set-benchmarks [10000 100000])
(bench/run-set-operations-benchmarks [10000 100000])
```

For production profiling, use Criterium:

```clojure
(require '[criterium.core :as crit])

(crit/bench (ordered-set my-data))
(crit/bench (get my-ordered-map some-key))
```
