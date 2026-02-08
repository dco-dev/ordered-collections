# Performance Optimization Plan

## Current Performance Gaps

Based on analysis of the codebase and benchmarks at N=500,000:

| Operation | vs sorted-* | vs data.avl | Root Cause |
|-----------|-------------|-------------|------------|
| Lookup | 7% slower | ~equal | Deeper tree (1.44× log₂n vs 2× log₂n) |
| Sequential insert | 1.6-2.3× slower | 1.5× slower | Heavier rebalancing, no transients |
| Delete | 1.38× slower | ~equal | concat3 cascades |
| String keys | 1.5× slower | 1.3× slower | Extra depth × expensive comparator |
| Seq iteration | 2× slower | 1.5× slower | Lazy seq overhead vs reduce |

## Optimization Strategies

### Tier 1: High Impact, Low Risk

#### 1.1 Transient Mode for Sequential Operations
**Impact: 2-3× faster sequential insert/delete**
**Effort: Medium**

Implement mutable transient versions similar to Clojure's transient collections:

```clojure
(defprotocol ITransientTree
  (persistent! [this])
  (conj! [this elem])
  (disj! [this elem]))

(deftype TransientOrderedSet [^:volatile-mutable root cmp alloc stitch]
  ITransientTree
  (conj! [this elem]
    (set! root (tree/node-add! root elem cmp alloc))
    this)
  (persistent! [this]
    (OrderedSet. root cmp alloc stitch {})))
```

Key optimizations:
- Use mutable `^:volatile-mutable` fields
- Skip path-copying during mutations
- Only copy on `persistent!`
- Thread-local ownership check (like Clojure transients)

**Files to modify:**
- `tree/tree.clj`: Add `node-add!`, `node-remove!` mutable variants
- `tree/ordered_set.clj`: Add `TransientOrderedSet` deftype
- `tree/ordered_map.clj`: Add `TransientOrderedMap` deftype
- `core.clj`: Add `transient`, `persistent!` support

#### 1.2 Enable ArrayLeaf by Default
**Impact: 10-15% faster lookup, 10-20% faster iteration**
**Effort: Low**

ArrayLeaf provides cache-friendly leaf storage but is currently disabled:

```clojure
;; Current (tree.clj:615)
(def ^:dynamic *use-array-leaf* false)

;; Proposed
(def ^:dynamic *use-array-leaf* true)
```

Benefits:
- Binary search in contiguous arrays is faster than pointer chasing
- Better CPU cache utilization
- Reduces memory fragmentation

Trade-offs:
- ~5-10% slower small inserts (array copying)
- Slightly more complex code paths

**Action:** Benchmark with ArrayLeaf enabled, update default if positive.

#### 1.3 Specialize Common Comparators
**Impact: 15-25% faster for Long/Integer keys**
**Effort: Medium**

Avoid virtual dispatch for common types:

```clojure
;; Current: always goes through Comparator interface
(.compare ^Comparator cmp k key)

;; Optimized: inline for primitives
(defmacro fast-compare [cmp k1 k2]
  `(let [k1# ~k1 k2# ~k2]
     (cond
       (and (instance? Long k1#) (instance? Long k2#))
       (Long/compare (long k1#) (long k2#))

       (and (instance? String k1#) (instance? String k2#))
       (.compareTo ^String k1# k2#)

       :else
       (.compare ~cmp k1# k2#))))
```

Or use protocol-based dispatch:

```clojure
(defprotocol FastCompare
  (fast-cmp [k1 k2]))

(extend-protocol FastCompare
  Long
  (fast-cmp [k1 k2] (Long/compare k1 k2))
  String
  (fast-cmp [k1 k2] (.compareTo k1 k2))
  Object
  (fast-cmp [k1 k2] (compare k1 k2)))
```

### Tier 2: Medium Impact, Medium Risk

#### 2.1 Primitive-Specialized Collections
**Impact: 30-50% faster for numeric keys/values**
**Effort: High**

Create specialized versions for common primitive types:

```clojure
;; Specialized for long keys
(deftype LongNode [^long k v l r ^long x]
  IBalancedNode (x [_] x)
  INode
  (k [_] k)
  (v [_] v)
  (l [_] l)
  (r [_] r))

(defn long-ordered-set [coll]
  ;; Uses LongNode internally, primitive comparison
  ...)
```

Benefits:
- No boxing overhead
- Primitive comparison (1 instruction vs method call)
- Better memory layout

#### 2.2 Lazy/Batched Rebalancing
**Impact: 20-30% faster sequential insert**
**Effort: Medium**

Defer rebalancing for small imbalances:

```clojure
;; Current: rebalance on every insert
(stitch-wb create key val (add l) r)

;; Proposed: skip if imbalance is small
(defn stitch-wb-lazy [create k v l r]
  (let [lw (node-weight l)
        rw (node-weight r)
        imbalance (/ (max lw rw) (inc (min lw rw)))]
    (if (< imbalance +lazy-threshold+)  ;; e.g., 2.5
      (create k v l r)  ;; Skip rotation
      (stitch-wb create k v l r))))  ;; Full rebalance
```

Then rebalance on next access or periodically.

#### 2.3 Reduce Tree Depth via B-tree Hybrid
**Impact: 20% faster lookup**
**Effort: High**

Instead of binary nodes, use nodes with 4-8 children (B-tree style):

```clojure
(deftype BTreeNode [^objects keys ^objects vals ^objects children ^int n]
  ;; n keys, n+1 children
  ;; Binary search within node, then descend
  )
```

Benefits:
- Fewer levels: log₄(n) vs log₂(n)
- Better cache utilization per node access

Trade-offs:
- More complex implementation
- May hurt insert/delete performance

### Tier 3: Lower Impact or Experimental

#### 3.1 SIMD-Friendly Binary Search
**Impact: 5-10% faster ArrayLeaf lookup**
**Effort: Low**

Use Java's Arrays.binarySearch which may use SIMD:

```clojure
;; Current custom binary search
(loop [lo 0 hi (dec n)] ...)

;; Proposed: leverage JVM optimizations
(java.util.Arrays/binarySearch ks 0 n k cmp)
```

#### 3.2 Path Compression
**Impact: 10% faster for sparse trees**
**Effort: Medium**

Collapse chains of single-child nodes:

```clojure
;; Before: A -> B -> C (each with one child)
;; After: A[B,C] -> leaf (compressed path)
```

#### 3.3 Interned Small Values
**Impact: 5% memory reduction**
**Effort: Low**

Intern common small integer keys to reduce allocations:

```clojure
(def ^:private small-ints (mapv identity (range -128 128)))
(defn intern-key [k]
  (if (and (int? k) (<= -128 k 127))
    (nth small-ints (+ k 128))
    k))
```

## Implementation Priority

### Phase 1: Quick Wins (1-2 weeks)
1. Enable ArrayLeaf by default (measure first)
2. Specialize Long/Integer comparators
3. Add SIMD-friendly binary search

### Phase 2: Transient Mode (2-3 weeks)
1. Implement `TransientOrderedSet`
2. Implement `TransientOrderedMap`
3. Add `transient`/`persistent!` to public API

### Phase 3: Advanced Optimizations (4-6 weeks)
1. Primitive-specialized collections (`long-ordered-set`, etc.)
2. Lazy rebalancing mode
3. B-tree hybrid for ultra-fast lookup

## Benchmarking Plan

For each optimization:

1. **Micro-benchmark** the specific operation
2. **Macro-benchmark** full use cases
3. **Memory profile** to catch regressions
4. **Compare against** sorted-set, data.avl, Scala TreeSet

Key benchmarks to run:
```clojure
(require '[criterium.core :as crit])

;; Lookup
(crit/bench (get my-set some-key))

;; Sequential insert
(crit/bench (reduce conj (ordered-set) data))

;; Batch construction
(crit/bench (ordered-set data))

;; Set operations
(crit/bench (union s1 s2))

;; Iteration
(crit/bench (reduce + my-set))
```

## Risk Assessment

| Optimization | Risk | Mitigation |
|--------------|------|------------|
| ArrayLeaf default | Low | Extensive benchmarks first |
| Transients | Medium | Follow Clojure's proven design |
| Lazy rebalancing | Medium | May affect worst-case bounds |
| Primitive specialization | Low | Additive, doesn't change core |
| B-tree hybrid | High | Major architecture change |

## Expected Outcomes

After Phase 1+2:
- Sequential insert: **1.2-1.5× sorted-set** (from 2.3× slower)
- Lookup: **within 3%** of sorted-set (from 7% slower)
- Delete: **within 15%** of sorted-set (from 38% slower)

After Phase 3:
- Primitive keys: **faster than sorted-set** for long/int
- Lookup-heavy: **competitive with HashMap** for small N
