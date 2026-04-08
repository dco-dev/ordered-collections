# Why Weight-Balanced Trees?

This document explains why this library uses weight-balanced trees instead of the more common red-black trees (used by Clojure's `sorted-map`) or AVL trees (used by `data.avl`).

Weight-balanced trees have a distinguished lineage in functional programming, powering Haskell's `Data.Set` and `Data.Map`, MIT Scheme's `wt-tree`, and several other persistent collection libraries. This isn't an accident — their structure is uniquely suited to functional programming's needs.

In this library, that split/join structure also serves as the extensibility
boundary: one shared tree implementation supports generic, primitive-specialized,
and augmented variants by varying the comparator and node constructor.

## The Three Contenders

### Red-Black Trees (Clojure's sorted-map/sorted-set)

Red-black trees maintain balance through a coloring invariant: no root-to-leaf path has more than twice as many nodes as any other. This gives O(log n) operations with low constant factors.

**Strengths:**
- Minimal rebalancing on insert (at most 2 rotations)
- Well-understood, battle-tested
- Excellent lookup performance

**Weaknesses:**
- No efficient split or join — Tarjan showed that red-black join requires O(log^2 n) color fixups in the worst case, compared to O(log n) for weight-balanced
- No cached subtree sizes by default — positional access (`nth`, `rank`) and split-at-by-index are not natural parts of the representation
- No native split/join-centered set algebra in Clojure's built-in sorted collections

### AVL Trees (data.avl)

AVL trees maintain strict height balance: the heights of left and right subtrees differ by at most 1. This creates slightly shorter trees than red-black, giving marginally faster lookups.

**Strengths:**
- Slightly shorter average paths (height ≤ 1.44 log₂ n vs 2 log₂ n for red-black)
- O(log n) rank access via cached sizes (as in data.avl)
- Efficient `nth` operation

**Weaknesses:**
- More rotations on insert/delete than red-black
- Split/join are O(log n) but require height recomputation at each level, adding constant-factor overhead
- No parallel fold — `r/fold` falls back to sequential reduce
- Set operations use the same split/join approach but with higher constants

### Weight-Balanced Trees (this library)

Weight-balanced trees maintain balance based on subtree sizes: using the Hirai-Yamamoto parameters (δ=3, γ=2), no subtree's weight can exceed 3× its sibling's weight. This seemingly simple invariant unlocks powerful capabilities.

**Strengths:**
- O(log n) split and join with low constants — size is already tracked, no auxiliary recomputation needed
- Natural size tracking enables O(log n) `nth`, `rank`, `median`, `percentile`
- Efficient set algebra via Adams' divide-and-conquer algorithm
- Natural parallelization via tree decomposition
- Augmentable — subtree aggregates (max endpoint, segment sums) compose with the weight invariant
- O(log n) `first`/`last` via SortedSet interface

**Weaknesses:**
- Slightly taller than AVL (height ≤ log_φ n ≈ 1.44 log₂ n for AVL vs ~2 log₂ n)
- Sequential insert marginally slower than red-black (mitigated by batch construction)
- Less common in imperative languages, fewer reference implementations

## The Key Insight: Split and Join

The defining advantage of weight-balanced trees is efficient **split** and **join**:

```
split(tree, key) → (left, present?, right)
join(left, key, value, right) → tree
```

Both operations run in O(log n) time. Adams (1992) showed that given just these two primitives, you can implement union, intersection, and difference as simple recursive algorithms:

```
union(T₁, T₂):
    if T₁ is empty: return T₂
    if T₂ is empty: return T₁
    let (k, v) = root(T₂)
    let (L₁, _, R₁) = split(T₁, k)
    return join(union(L₁, left(T₂)), k, v, union(R₁, right(T₂)))
```

This divide-and-conquer structure has two remarkable properties:

1. **Work-optimality**: The total work is O(m log(n/m + 1)) where m ≤ n are the set sizes. This is information-theoretically optimal — it matches the lower bound for comparison-based set operations. When one set is much smaller than the other, this is significantly better than generic element-wise set-algebra paths over ordered collections.

2. **Low span**: The two recursive calls are independent and can execute in parallel. Blelloch, Ferizovic, and Sun (2016) proved the span is O(log² n), giving near-linear speedup on many cores.

### Why Split/Join Is Natural for Weight-Balanced Trees

Split and join are efficient for weight-balanced trees because the balance invariant is based on **sizes**, and sizes compose trivially: `size(join(L, k, R)) = size(L) + 1 + size(R)`. After a split or join, we know the exact sizes of all subtrees without any recomputation.

Compare with red-black trees, where a join must reconcile potentially incompatible color invariants at the junction point. Or AVL trees, where heights must be recomputed bottom-up. In both cases, the auxiliary balance information doesn't compose as cleanly.

## Size-Based Operations

Every node in a weight-balanced tree stores its subtree size. This is not auxiliary overhead — it *is* the balance invariant. And it enables operations that other tree types cannot provide efficiently:

### O(log n) Positional Access

To find the i-th element, compare i against the left subtree's size and recurse:

```clojure
(def s (ordered-set (range 1000000)))
(nth s 500000)       ;=> 500000    O(log n)
(rank-of s 500000)   ;=> 500000    O(log n)
```

Red-black trees have no size information, so `nth` requires O(n) traversal. AVL trees can add cached sizes (as data.avl does), but it's bolted on rather than inherent to the balance scheme.

### O(log n) Splitting by Position

Because sizes are tracked, we can split a tree at a *position* (not just a key):

```clojure
(split-at 3 (ordered-set [10 20 30 40 50]))
;=> [#{10 20 30} #{40 50}]
```

This is the foundation for parallel fold: split the tree into roughly equal halves, reduce each in parallel, combine results.

## Augmented Trees

The weight-balanced structure supports *augmentation*: storing additional per-node aggregates that are maintained during rotations. This library uses augmentation to build:

- **Interval trees**: Each node stores the maximum right endpoint in its subtree. This enables O(log n + k) overlap queries ("what intervals contain point p?").

- **Segment trees**: Each node stores an aggregate (sum, max, min) over its subtree. This enables O(log n) range queries ("what's the sum from key a to key b?").

Augmentation works naturally with weight-balanced trees because rotations update a fixed number of nodes, and the aggregate at each node is a function of its children's aggregates. The same approach is harder with red-black trees, where color changes can propagate unpredictably.

The PAM framework (Sun, Ferizovic, Blelloch 2018) formalized this: any augmented map where the augment is a monoid over subtree values can be maintained in O(log n) per update, and the full suite of split/join/union/intersection operations carries augmentation through automatically.

## Parallel Fold

The ability to efficiently split trees enables true parallel reduction:

```clojure
(require '[clojure.core.reducers :as r])
(r/fold + (ordered-set (range 500000)))
```

The tree is split into larger balanced chunks, and those chunks are reduced in parallel via `r/fold`. In this library, `CollFold` is implemented explicitly by eager chunking with `node-split` and a minimum chunk-size floor of `4096`, so fold only enters the parallel regime when splitting overhead is worth paying. Clojure's `sorted-set` and `data.avl` do not provide an equivalent tree-aware `CollFold` implementation here, so they effectively remain sequential in these benchmarks.

In these measurements, parallel fold is about 9.7x faster than sorted-set and
3.4x faster than data.avl at N=500K.

## The Balance Invariant

Weight-balanced trees use two parameters, traditionally called δ (delta) and γ (gamma):

- **δ = 3**: A subtree can be at most 3× the weight of its sibling before rebalancing is triggered
- **γ = 2**: Determines whether a single or double rotation is needed during rebalancing

The balance condition is:

```
weight(left) + 1 ≤ δ × (weight(right) + 1)
weight(right) + 1 ≤ δ × (weight(left) + 1)
```

This ensures a height bound of O(log n) — specifically, height ≤ log_{δ/(δ-1)} n = log_{3/2} n ≈ 1.71 log₂ n.

### The Parameter Problem

Adams' original work left the choice of δ and γ somewhat informal. Different implementations used different values, and some combinations led to subtle correctness bugs — trees that could become unbalanced after certain deletion sequences.

Hirai and Yamamoto (2011) resolved this definitively using the Coq proof assistant. They proved that **(δ=3, γ=2)** is the unique integer parameter pair that guarantees:

- O(log n) height bound
- Correct rebalancing for all insert/delete sequences (no degenerate cases)
- The join operation preserves balance

Kazu Yamamoto subsequently [patched MIT Scheme and SLIB](https://github.com/kazu-yamamoto/wttree) to use these parameters.

## Performance

Weight-balanced trees are competitive with red-black and AVL trees on basic operations (lookup, insert, iteration) and significantly faster on operations that leverage split/join.

## Historical Context

Weight-balanced trees have a rich history spanning five decades:

### Origins (1972)

Nievergelt and Reingold introduced "binary search trees of bounded balance" (BB[α] trees). The key insight: balance based on subtree *sizes* rather than heights. This predates red-black trees (Guibas & Sedgewick, 1978) by six years.

### The Functional Renaissance (1992-1993)

**Stephen Adams** revolutionized the use of weight-balanced trees for functional programming:

- *Technical Report CSTR 92-10* (1992): "Implementing Sets Efficiently in a Functional Language" — the foundational work establishing `join` as the single balancing-scheme-specific operation from which all set operations derive.
- *Journal of Functional Programming* (1993): "Efficient sets — a balancing act" — winner of the "elegance category" in a programming competition.

Adams showed that weight-balanced trees need only *one* balancing-scheme-specific function (`join`) to implement all set operations. His divide-and-conquer algorithms for union, intersection, and difference became the standard approach in functional languages.

### Production Implementations

Adams' work directly influenced several major implementations:

**Haskell containers** (Data.Set, Data.Map): The de facto standard collections in Haskell. From the [source](https://hackage.haskell.org/package/containers/docs/Data-Map.html): "The implementation is based on size balanced binary trees as described by Stephen Adams."

**MIT Scheme wt-tree** (mid-1980s onwards): One of the earliest production implementations, providing a comprehensive API for sets and maps. The [MIT Scheme Reference Manual](https://www.gnu.org/software/mit-scheme/documentation/stable/mit-scheme-ref/Weight_002dBalanced-Trees.html) notes: "Weight-balanced binary trees have several advantages over the other data structures for large aggregates."

**FSet** (Common Lisp and Java): Scott Burson's [functional collections library](https://github.com/slburson/fset) uses "an evolution of Stephen Adams' weight-balanced binary trees," providing heterogeneous collections with correct ordering-collision handling.

**SLIB** (Scheme): Aubrey Jaffer's portable Scheme library includes [weight-balanced trees](https://people.csail.mit.edu/jaffer/slib/Weight_002dBalanced-Trees.html) as a core data structure.

### The Parameter Problem Resolved (2011)

**Hirai and Yamamoto** resolved the balance parameter question definitively in "Balancing Weight-Balanced Trees" (Journal of Functional Programming, 2011). Using the Coq proof assistant, they proved that (δ=3, γ=2) is the unique integer solution for correct balancing. Kazu Yamamoto patched MIT Scheme and SLIB accordingly.

### Parallelism and Augmentation (2016-2018)

**Blelloch, Ferizovic, and Sun** published "Just Join for Parallel Ordered Sets" (SPAA 2016), proving that Adams' algorithms are both *work-optimal* — O(m log(n/m + 1)) — and *highly parallel* — O(log² n) span. Their [PAM library](https://cmuparlay.github.io/PAMWeb/) demonstrates 45x+ speedup on 64 cores.

Sun, Ferizovic, and Blelloch followed with "PAM: Parallel Augmented Maps" (PPoPP 2018), showing that the same join-based framework extends to augmented trees (interval trees, segment trees, range trees) while preserving work-optimality and parallelism.

These papers vindicated Adams' 1992 design: the elegant `join`-based approach wasn't just beautiful — it was optimal.

## Why Weight-Balanced Trees Won in Functional Languages

The pattern is clear: when functional programmers need ordered collections, they reach for weight-balanced trees. Why?

1. **Persistence is natural**: Subtree sharing just works — no mutable color bits or height counters to worry about
2. **Split and join compose**: Functional programming values composition; these operations are the natural decomposition/recomposition primitives for ordered data
3. **Size tracking is the invariant, not overhead**: nth, rank, split-at-position, and range counting come for free
4. **Augmentation generalizes cleanly**: Interval queries, range aggregation, and other augmented operations compose with the existing balance scheme
5. **Parallelism follows from structure**: The ability to split enables divide-and-conquer parallelism with provably optimal work and low span

As the MIT Scheme manual puts it: "Weight-balanced binary trees have several advantages over the other data structures for large aggregates... The implementation is functional rather than imperative."

## References

### Foundational Papers

- Nievergelt, J. & Reingold, E. (1972). "[Binary Search Trees of Bounded Balance](https://dl.acm.org/doi/10.1137/0202005)". *SIAM Journal of Computing* 2(1).

- Adams, S. (1992). "Implementing Sets Efficiently in a Functional Language". *Technical Report CSTR 92-10*, University of Southampton.

- Adams, S. (1993). "[Efficient sets — a balancing act](https://www.cambridge.org/core/journals/journal-of-functional-programming/article/functional-pearls-efficient-setsa-balancing-act/0CAA1C189B4F7C15CE9B8C02D0D4B54E)". *Journal of Functional Programming* 3(4):553-562.

### Correctness and Optimization

- Hirai, Y. & Yamamoto, K. (2011). "[Balancing Weight-Balanced Trees](https://www.cambridge.org/core/journals/journal-of-functional-programming/article/balancing-weightbalanced-trees/7281C4DE7E56B74F2D13F06E31DCBC5B)". *Journal of Functional Programming* 21(3):287-307.

- Blelloch, G., Ferizovic, D., & Sun, Y. (2016). "[Just Join for Parallel Ordered Sets](https://dl.acm.org/doi/10.1145/2935764.2935768)". *ACM SPAA*.

- Sun, Y., Ferizovic, D., & Blelloch, G. (2018). "[PAM: Parallel Augmented Maps](https://dl.acm.org/doi/10.1145/3178487.3178509)". *ACM PPoPP*.

### Implementations

- [Haskell containers (Data.Set, Data.Map)](https://hackage.haskell.org/package/containers)
- [MIT Scheme Weight-Balanced Trees](https://www.gnu.org/software/mit-scheme/documentation/stable/mit-scheme-ref/Weight_002dBalanced-Trees.html)
- [FSet for Common Lisp](https://github.com/slburson/fset)
- [FSet for Java](https://github.com/slburson/fset-java)
- [SLIB Weight-Balanced Trees](https://people.csail.mit.edu/jaffer/slib/Weight_002dBalanced-Trees.html)
- [PAM: Parallel Augmented Maps](https://cmuparlay.github.io/PAMWeb/)
