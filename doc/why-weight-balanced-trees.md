# Why Weight-Balanced Trees?

This document explains why this library uses weight-balanced trees instead of the more common red-black trees (used by Clojure's `sorted-map`) or AVL trees (used by `data.avl`).

Weight-balanced trees have a distinguished lineage in functional programming, powering Haskell's `Data.Set` and `Data.Map`, MIT Scheme's `wt-tree`, and several other persistent collection libraries. This isn't an accident—their structure is uniquely suited to functional programming's needs.

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
- O(log n) rank access via cached sizes (same as weight-balanced)
- Efficient nth operation
- Transient support for batch mutations

**Weaknesses:**
- More rotations on insert/delete
- Split/join still O(log n) but with higher constants
- Height tracking adds complexity
- No parallel fold support

### Weight-Balanced Trees (this library)

Weight-balanced trees maintain balance based on subtree sizes: no subtree can be more than ~3.74x larger than its sibling. This seemingly simple invariant unlocks powerful capabilities.

**Strengths:**
- O(log n) split and join with low constants
- Natural size tracking enables O(log n) nth and rank
- Efficient set operations (union, intersection, difference) — 5-9x faster
- Natural parallelization via tree splitting — 10-16x faster fold, equal construction
- Simpler rebalancing logic than red-black
- O(log n) first/last access via SortedSet interface — 118,000x faster than sorted-set at N=500K

**Weaknesses:**
- Sequential insert ~1.5x slower (mitigated by parallel batch construction)
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

(def half-million (ordered-set (range 500000)))

;; Sequential reduce
(time (reduce + half-million))      ; ~82ms

;; Parallel fold (splits tree, reduces in parallel, combines)
(time (r/fold + half-million))      ; ~42ms (2.3x speedup)
```

Clojure's `sorted-set` falls back to sequential reduce because red-black trees can't efficiently split. At 500K elements, ordered-set parallel fold is 16x faster than sorted-set's sequential fallback.

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
| Lookup | 1.0x | 1.1x | 1.08x | Nearly equal |
| Iteration | 1.0x | 0.79x | 0.99x | Comparable |
| Construction | 1.0x | 2.2x | **1.0x** | Equal via parallel fold |
| Split | N/A | 1.0x | **0.22x** | Weight-balanced 4.5x faster |
| Parallel fold | 1.0x | 1.0x | **0.43x** | Only weight-balanced parallelizes |

For sets at N = 500,000:

| Operation | sorted-set | data.avl | ordered-set | Notes |
|-----------|------------|----------|-------------|-------|
| Lookup | 1.0x | 1.25x | 1.07x | Nearly equal |
| Iteration | 1.0x | 0.59x | **0.86x** | 14% faster than sorted-set |
| Construction | 1.0x | 1.7x | **0.8x** | 25% faster via parallel fold |
| First/last | 1.0x | 1.9x | **0.000008x** | 118,000x faster (O(log n)) |
| Union | 1.0x | — | **0.17x** | 5.8x faster |
| Intersection | 1.0x | — | **0.19x** | 5.3x faster |
| Difference | 1.0x | — | **0.12x** | 8.6x faster |

## Historical Context

Weight-balanced trees have a rich history spanning five decades:

### Origins (1972)

Nievergelt and Reingold introduced "binary search trees of bounded balance" (BB[α] trees). The key insight: balance based on subtree *sizes* rather than heights. This predates red-black trees (1978) by six years.

### The Functional Renaissance (1992-1993)

**Stephen Adams** revolutionized the use of weight-balanced trees for functional programming:

- *Technical Report CSTR 92-10* (1992): "Implementing Sets Efficiently in a Functional Language" — the foundational work
- *Journal of Functional Programming* (1993): "Efficient sets—a balancing act" — winner of the "elegance category" in a programming competition

Adams showed that weight-balanced trees need only *one* balancing-scheme-specific function (`join`) to implement all set operations elegantly. His algorithms for union, intersection, and difference became the standard approach.

### Production Implementations

Adams' work directly influenced several major implementations:

**MIT Scheme wt-tree** (mid-1980s onwards): One of the earliest production implementations, providing a comprehensive API for sets and maps. The [MIT Scheme Reference Manual](https://www.gnu.org/software/mit-scheme/documentation/stable/mit-scheme-ref/Weight_002dBalanced-Trees.html) notes: "Weight-balanced binary trees have several advantages over the other data structures for large aggregates."

**Haskell containers** (Data.Set, Data.Map): The de facto standard collections in Haskell cite Adams directly. From the [source](https://hackage.haskell.org/package/containers/docs/Data-Map.html): "The implementation is based on size balanced binary trees as described by Stephen Adams."

**FSet** (Common Lisp and Java): Scott Burson's [functional collections library](https://github.com/slburson/fset) uses "an evolution of Stephen Adams' weight-balanced binary trees," providing heterogeneous collections with correct ordering-collision handling.

**SLIB** (Scheme): Aubrey Jaffer's portable Scheme library includes [weight-balanced trees](https://people.csail.mit.edu/jaffer/slib/Weight_002dBalanced-Trees.html) as a core data structure.

### The Parameter Problem (2011)

Adams' original analysis had a subtle flaw. Various implementations used different balance parameters, some leading to edge cases.

**Hirai and Yamamoto** resolved this definitively in "Balancing Weight-Balanced Trees" (Journal of Functional Programming, 2011). Using the Coq proof assistant, they proved that **(δ=3, γ=2)** is the unique integer solution for correct balancing. Kazu Yamamoto [patched MIT Scheme and SLIB](https://github.com/kazu-yamamoto/wttree) accordingly.

### Parallelism (2016)

**Blelloch, Ferizovic, and Sun** published "[Just Join for Parallel Ordered Sets](https://www.cs.cmu.edu/~guyb/papers/BFS16.pdf)" (SPAA 2016), proving that Adams' algorithms are both *work-optimal* and *highly parallel* (polylogarithmic span). Their [PAM library](https://cmuparlay.github.io/PAMWeb/) demonstrates 45x+ speedup on 64 cores.

This paper vindicated Adams' 1992 design: the elegant `join`-based approach wasn't just beautiful—it was optimal.

## Why Weight-Balanced Trees Won in Functional Languages

The pattern is clear: when functional programmers need ordered collections, they reach for weight-balanced trees. Why?

1. **Persistence is free**: The functional/referential-transparent nature means subtree sharing just works
2. **Split and join are fundamental**: Functional programming values composition; these operations compose naturally
3. **Size tracking enables more operations**: nth, rank, and range queries come "for free"
4. **Parallelism**: The ability to split enables divide-and-conquer parallelism

As the MIT Scheme manual puts it: "The implementation is functional rather than imperative... The trees are referentially transparent thus the programmer need not worry about copying the trees."

## References

### Foundational Papers

- Nievergelt, J. & Reingold, E. (1972). "[Binary Search Trees of Bounded Balance](https://dl.acm.org/doi/10.1137/0202005)". *SIAM Journal of Computing* 2(1).

- Adams, S. (1992). "Implementing Sets Efficiently in a Functional Language". *Technical Report CSTR 92-10*, University of Southampton.

- Adams, S. (1993). "[Efficient sets—a balancing act](https://www.cambridge.org/core/journals/journal-of-functional-programming/article/functional-pearls-efficient-setsa-balancing-act/0CAA1C189B4F7C15CE9B8C02D0D4B54E)". *Journal of Functional Programming* 3(4):553-562.

### Correctness and Optimization

- Hirai, Y. & Yamamoto, K. (2011). "[Balancing Weight-Balanced Trees](https://www.cambridge.org/core/journals/journal-of-functional-programming/article/balancing-weightbalanced-trees/7281C4DE7E56B74F2D13F06E31DCBC5B)". *Journal of Functional Programming* 21(3):287-307.

- Blelloch, G., Ferizovic, D., & Sun, Y. (2016). "[Just Join for Parallel Ordered Sets](https://dl.acm.org/doi/10.1145/2935764.2935768)". *ACM SPAA*.

### Implementations

- [MIT Scheme Weight-Balanced Trees](https://www.gnu.org/software/mit-scheme/documentation/stable/mit-scheme-ref/Weight_002dBalanced-Trees.html)
- [Haskell containers (Data.Set, Data.Map)](https://hackage.haskell.org/package/containers)
- [FSet for Common Lisp](https://github.com/slburson/fset)
- [FSet for Java](https://github.com/slburson/fset-java)
- [SLIB Weight-Balanced Trees](https://people.csail.mit.edu/jaffer/slib/Weight_002dBalanced-Trees.html)
- [PAM: Parallel Augmented Maps](https://cmuparlay.github.io/PAMWeb/)
