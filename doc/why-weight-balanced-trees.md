# Why Weight-Balanced Trees?

This document explains why this library uses weight-balanced trees instead of the more common red-black trees (used by Clojure's `sorted-map`) or AVL trees (used by `data.avl`).

## The Three Contenders

### Red-Black Trees (Clojure's sorted-map/sorted-set)

Red-black trees maintain balance through a coloring invariant: no path from root to leaf has more than twice as many nodes as any other. This gives O(log n) operations with low constant factors.

**Strengths:**
- Minimal rebalancing on insert (at most 2 rotations)
- Well-understood, battle-tested
- Excellent lookup performance

**Weaknesses:**
- No efficient split/join operations
- No size information at nodes (nth requires O(n) traversal)
- Complex deletion logic

### AVL Trees (data.avl)

AVL trees maintain strict height balance: the heights of left and right subtrees differ by at most 1. This creates shorter trees than red-black.

**Strengths:**
- Slightly faster lookup (shorter average path)
- O(1) rank access via cached sizes
- Efficient nth operation

**Weaknesses:**
- More rotations on insert/delete
- Split/join still O(log n) but with higher constants
- Height tracking adds complexity

### Weight-Balanced Trees (this library)

Weight-balanced trees maintain balance based on subtree sizes: no subtree can be more than ~3.74x larger than its sibling. This seemingly simple invariant unlocks powerful capabilities.

**Strengths:**
- O(log n) split and join with low constants
- Natural size tracking enables O(log n) nth and rank
- Efficient set operations (union, intersection, difference)
- Natural parallelization via tree splitting
- Simpler rebalancing logic than red-black

**Weaknesses:**
- Slightly deeper than AVL (~20% more comparisons on lookup)
- Less common, fewer reference implementations

## The Key Insight: Split and Join

The defining advantage of weight-balanced trees is efficient **split** and **join** operations:

```
split(tree, key) → (left-tree, right-tree)
join(left-tree, key, right-tree) → tree
```

These operations take O(log n) time and form the basis for efficient set algebra:

```clojure
;; Union of two sets with 500K elements each
(def a (ordered-set (range 0 1000000 2)))      ; evens
(def b (ordered-set (range 0 1000000 3)))      ; multiples of 3

(time (intersection a b))  ; ~200ms for 166K result elements
```

In contrast, `clojure.set/intersection` on `sorted-set` iterates element-by-element: O(n) regardless of overlap.

## Size-Based Operations

Every node in a weight-balanced tree knows its subtree size. This enables:

### O(log n) nth access
```clojure
(def s (ordered-set (range 1000000)))
(nth s 500000)  ; => 500000, in microseconds
```

### O(log n) rank queries
```clojure
(rank-of s 500000)  ; => 500000, position in sorted order
```

### O(log n) range counting
```clojure
(count (subseq s >= 100000 < 200000))  ; count without materializing
```

## Parallel Fold

The ability to efficiently split trees enables true parallel reduction:

```clojure
(require '[clojure.core.reducers :as r])

(def million (ordered-set (range 1000000)))

;; Sequential reduce
(time (reduce + million))           ; ~130ms

;; Parallel fold (splits tree, reduces in parallel, combines)
(time (r/fold + million))           ; ~78ms (1.7x speedup)
```

Clojure's `sorted-set` falls back to sequential reduce because red-black trees can't efficiently split.

## The Balance Invariant

Weight-balanced trees use two parameters, traditionally called δ (delta) and γ (gamma):

- **δ = 3**: A subtree can be at most 3x the size of its sibling before rebalancing
- **γ = 2**: During rebalancing, determines single vs double rotation

These parameters were proven optimal by Hirai and Yamamoto (2011), ensuring:
- O(log n) height bound
- Amortized O(1) rotations per insert/delete
- No degenerate cases

## When to Choose Each

| Use Case | Best Choice | Why |
|----------|-------------|-----|
| Simple key-value storage | sorted-map | Fastest lookup, built-in |
| Need nth/rank access | ordered-map | O(log n) vs O(n) |
| Set algebra (union, intersection) | ordered-set | O(log n) split/join |
| Parallel reduction | ordered-set/map | True parallel via CollFold |
| Interval queries | interval-map | Only option with this feature |
| Memory-constrained | sorted-map | Slightly smaller nodes |
| Maximum lookup speed | sorted-map | ~10% faster lookups |

## Empirical Comparison

At N = 500,000 elements:

| Operation | sorted-map | data.avl | ordered-map | Notes |
|-----------|------------|----------|-------------|-------|
| Lookup | 1.0x | 1.1x | 1.1x | Red-black wins slightly |
| Iteration | 1.0x | 0.85x | **0.75x** | Weight-balanced wins |
| Construction | 1.0x | 2.2x | 2.2x | Red-black wins |
| Split | N/A | 1.0x | **0.2x** | Weight-balanced 5x faster |
| Parallel fold | 1.0x | 1.0x | **0.6x** | Only weight-balanced parallelizes |

## Historical Context

Weight-balanced trees were introduced by Nievergelt and Reingold in 1972, predating red-black trees (1978). They fell out of favor because:

1. Early parameter choices led to edge cases
2. Red-black trees dominated textbooks
3. Split/join weren't valued in imperative programming

The functional programming renaissance revived interest: Adams (1992) showed weight-balanced trees are ideal for persistent data structures, and Hirai/Yamamoto (2011) finally proved correct balance parameters.

## References

- Adams, S. (1992). "Implementing Sets Efficiently in a Functional Language"
- Hirai, Y. & Yamamoto, K. (2011). "Balancing Weight-Balanced Trees"
- Nievergelt, J. & Reingold, E. (1972). "Binary Search Trees of Bounded Balance"
- Blelloch, G., Ferizovic, D., & Sun, Y. (2016). "Just Join for Parallel Ordered Sets"
