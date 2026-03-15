# Algorithms

## Weight-Balanced Trees

Each node stores a key, value, left and right children, and **subtree weight** (= 1 + size(left) + size(right)). Specialized node types exist for performance: `LongKeyNode` (unboxed `long` key), `DoubleKeyNode` (unboxed `double` key), and `IntervalNode` (additional max-endpoint field for interval augmentation).

```
        ┌─────────────────┐
        │  key: 50        │
        │  val: "fifty"   │
        │  weight: 7      │
        └────────┬────────┘
                 │
      ┌──────────┴──────────┐
      ▼                     ▼
 ┌─────────┐          ┌─────────┐
 │ key: 25 │          │ key: 75 │
 │ wt: 3   │          │ wt: 3   │
 └────┬────┘          └────┬────┘
      │                    │
   ┌──┴──┐              ┌──┴──┐
   ▼     ▼              ▼     ▼
 [10]   [30]          [60]   [90]
 wt:1   wt:1          wt:1   wt:1
```

Leaves are a sentinel value (weight 0). The weight field serves double duty: it *is* the balance invariant and it enables O(log n) positional access.

## Balance Invariant

Hirai-Yamamoto parameters (δ=3, γ=2):

```
weight(left)  + 1 ≤ δ × (weight(right) + 1)
weight(right) + 1 ≤ δ × (weight(left)  + 1)
```

No subtree can be more than 3× its sibling's weight. When violated, `stitch-wb` applies a single or double rotation:

- **Single rotation** when the heavy child's *outer* subtree dominates (checked via γ)
- **Double rotation** when the heavy child's *inner* subtree dominates

```
Single right:                Double right:
       [C]       [A]              [C]           [B]
      /   \     /   \            /   \         /   \
    [A]    z  x    [C]         [A]    z      [A]   [C]
   /   \          /   \       /   \         /  \   /  \
  x    [B]      [B]    z    w    [B]       w   x  y   z
                                /   \
                               x     y
```

Hirai & Yamamoto (2011) proved using Coq that (δ=3, γ=2) is the unique integer parameter pair guaranteeing O(log n) height and correct rebalancing for all insert/delete sequences. Height bound: ≤ log₃/₂ n ≈ 1.71 log₂ n.

## Split and Join

Everything reduces to these two primitives.

**Split** divides a tree at a key into (left, found, right):

```
split(tree, 50):

         [40]
        /    \
     [20]    [60]
     /  \    /  \
   [10][30][50][80]

            ↓

 LEFT (<50)       FOUND     RIGHT (>50)
    [40]            50          [60]
    /  \                          \
 [20]  [30]                       [80]
  /
[10]
```

**Join** (`node-concat3`) combines two trees with a pivot key, rebalancing as needed. When the trees are similarly sized, the pivot becomes the root directly. When one side is much heavier, join walks down the heavy side until it finds a subtree of comparable weight, inserts the pivot there, and rebalances upward.

```
join(left, 50, right):

 LEFT           RIGHT             [50]
  [25]           [75]            /    \
  /  \           /  \    →    [25]    [75]
[10] [30]     [60] [90]      /  \    /  \
                            [10][30][60][90]
```

**Join without pivot** (`node-concat2`) extracts the greatest element from the left tree and uses it as the pivot for `node-concat3`. Used by intersection and difference when the split key is absent.

Both split and join are O(log n). The key property of weight-balanced trees: weight composes trivially — `weight(join(L, k, R)) = weight(L) + 1 + weight(R)` — so no auxiliary recomputation (height, color) is needed after joining. This gives WBTs lower constant factors for split/join than AVL or red-black trees.

## The Join-Based Paradigm

All tree operations reduce to split and join (Adams 1992, Blelloch et al. 2016):

| Operation | Implementation |
|-----------|----------------|
| insert(k, v) | split at k, join with new node |
| delete(k) | split at k, join left and right |
| union(A, B) | split A at root(B), recurse on halves, join |
| intersection(A, B) | split A at root(B), recurse, join if found, concat if not |
| difference(A, B) | split A at root(B), recurse, concat halves |

Balance logic lives only in join. All operations inherit O(log n) balancing automatically.

## Set Operations

Union, intersection, and difference use Adams' divide-and-conquer:

```
union(T₁, T₂):
  if T₁ empty: return T₂
  if T₂ empty: return T₁
  (k, v) = root(T₂)
  (L₁, _, R₁) = split(T₁, k)
  return join(union(L₁, left(T₂)), k, v, union(R₁, right(T₂)))

intersection(T₁, T₂):
  if T₁ empty or T₂ empty: return ∅
  (k, v) = root(T₂)
  (L₁, present, R₁) = split(T₁, k)
  L = intersection(L₁, left(T₂))
  R = intersection(R₁, right(T₂))
  if present: return join(L, k, v, R)
  else:       return concat(L, R)

difference(T₁, T₂):
  if T₁ empty: return ∅
  if T₂ empty: return T₁
  (k, _) = root(T₂)
  (L₁, _, R₁) = split(T₁, k)
  return concat(difference(L₁, left(T₂)), difference(R₁, right(T₂)))
```

**Work complexity:** O(m log(n/m + 1)) where m ≤ n. This is information-theoretically optimal — it matches the comparison-based lower bound. When m ≪ n (e.g., merging a small set into a large one), this approaches O(m log n). When m ≈ n, it's O(n). The naive element-by-element insertion approach is always O(m log n), which is worse when m is large.

### Parallelism

The two recursive calls in each operation are independent. The implementation forks the left recursion as a `ForkJoinTask` and computes the right recursion inline, then joins:

```
fork-join:
  left-task  = fork(union(L₁, left(T₂)))   ← submitted to ForkJoinPool
  right-result = union(R₁, right(T₂))       ← computed inline
  left-result  = left-task.join()            ← wait for fork
  return join(left-result, k, v, right-result)
```

Two thresholds control granularity:
- **`+parallel-threshold+` = 210,000**: when the combined subtree size falls below this, switch to sequential recursion (fork overhead exceeds benefit)
- **`+sequential-cutoff+` = 64**: below this, use direct linear merge

Span is O(log² n), giving near-linear speedup on many cores (Blelloch et al. 2016).

## Fork-Join Parallelism

Six operations use Java's `ForkJoinPool` for automatic parallelism: union, intersection, difference, merge-with, fold-keys, and fold-entries. All share the same structural pattern.

### The pattern

Every parallel operation has three layers:

```
┌─────────────────────────────────────────────────────────────┐
│  Entry point                                                │
│  Is the caller already in a ForkJoinPool worker thread?     │
│    yes → call par-fn directly                               │
│    no  → submit par-fn to the common pool via .invoke       │
├─────────────────────────────────────────────────────────────┤
│  par-fn (parallel recursion)                                │
│  Is the subtree large enough to justify forking?            │
│    yes → fork left subtree, compute right inline, join      │
│    no  → call seq-fn                                        │
├─────────────────────────────────────────────────────────────┤
│  seq-fn (sequential recursion)                              │
│  Same algorithm, no fork overhead                           │
└─────────────────────────────────────────────────────────────┘
```

### Why the pool entry point is needed

`ForkJoinTask.fork()` can only be called from within a `ForkJoinPool` worker thread. When user code calls `union` from a regular thread (e.g. the main thread or a core.async thread), the operation must first be submitted to the pool. Once inside the pool, recursive calls are already on worker threads and can fork directly:

```
(if (ForkJoinTask/inForkJoinPool)
  (par-fn root)                          ;; already inside pool
  (.invoke fork-join-pool                ;; enter pool
    (ForkJoinTask/adapt (fn [] (par-fn root)))))
```

### The fork-join macro

A shared macro handles the fork/compute/join choreography:

```
(fork-join [left-result  (par-fn left-subtree)
            right-result (par-fn right-subtree)]
  (combine left-result right-result))
```

This expands to:
1. Wrap the left expression in a `Callable`, adapt it to a `ForkJoinTask`, and fork it (submits to the pool's work-stealing queue)
2. Compute the right expression inline on the current thread
3. `.join` the left task (blocks until complete, or steals work while waiting)
4. Combine the two results

The asymmetry — fork left, compute right — is deliberate. Forking both sides would double the task creation overhead with no benefit, since the current thread would just block immediately.

### Dynamic binding capture

Clojure's dynamic vars (`binding`) are thread-local. Since forked tasks run on different threads, each parallel operation captures the current comparator and node constructor into locals before entering the `letfn`:

```
(let [cmp  order/*compare*
      join *t-join*]
  (letfn [(seq-fn [n1 n2]
            (binding [order/*compare* cmp, *t-join* join]
              ...))
          (par-fn [n1 n2]
            (binding [order/*compare* cmp, *t-join* join]
              ...))]))
```

The captured values are closed over by both `seq-fn` and `par-fn`, then re-bound on each thread that executes them. Without this, forked tasks would see the root binding (default comparator) instead of the collection's comparator.

### Threshold tuning

Two thresholds prevent fork overhead from dominating on small inputs:

| Threshold | Value | Purpose |
|-----------|-------|---------|
| `+parallel-threshold+` | 210,000 | Combined subtree size below which set operations switch from `par-fn` to `seq-fn` |
| `+sequential-cutoff+` | 64 | Subtree size below which set operations use direct linear merge |
| `+fold-parallel-threshold+` | 8,192 | Subtree size below which fold operations use sequential in-order traversal |

The set operation threshold is high because split/join have significant constant factors — the parallelism only pays off when there's enough work to amortize task creation. The fold threshold is lower because fold tasks do less work per element (just apply `reducef`).

These values were empirically determined via `parallel_threshold_bench.clj`. The crossover point depends on hardware; 210K is conservative and benefits most multi-core machines.

### Operations using this pattern

**Set operations** (union, intersection, difference, merge-with): The tree's divide-and-conquer structure maps directly to fork-join. Split T₁ at T₂'s root, fork the left halves, compute the right halves inline, join results. Work O(m log(n/m + 1)), span O(log² n).

**Parallel fold** (fold-keys, fold-entries): Walk the tree in-order. At each node, fork the left subtree fold, compute the right subtree fold inline, combine with `combinef`, then apply `reducef` to the node's element. Work O(n), span O(log n).

## Positional Access

Weight at each node enables O(log n) index operations without any additional data structure.

### nth (index → element)

```
nth(tree, i):
  left-size = weight(left)
  if i < left-size:  recurse into left
  if i == left-size: return this node
  else:              recurse into right with i' = i - left-size - 1
```

### rank (element → index)

Accumulate left subtree sizes while descending:

```
rank(tree, key):
  acc = 0
  if key < node.key: recurse left, keep acc
  if key = node.key: return acc + weight(left)
  if key > node.key: acc += weight(left) + 1, recurse right
```

### Derived operations

- **slice(start, end)**: split-at start, split-at (end - start) on the right half
- **median**: nth at ⌊n/2⌋
- **percentile(p)**: nth at ⌊n × p / 100⌋

All O(log n). Available on `ordered-set`, `ordered-map`, `fuzzy-set`, `fuzzy-map`.

## Nearest (Floor / Ceiling)

`ordered-set` and `ordered-map` implement directional nearest-neighbor via tree descent:

| Test | Meaning |
|------|---------|
| `:<=` | floor — greatest element ≤ k |
| `:<` | predecessor — greatest element < k |
| `:>=` | ceiling — least element ≥ k |
| `:>` | successor — least element > k |

Standard BST descent with candidate tracking. O(log n).

## Parallel Fold

Collections implement `clojure.core.reducers/CollFold`. The tree is recursively split at the root: left and right subtrees are reduced in parallel via `ForkJoinPool`, results combined with the user's combining function.

Threshold: **`+fold-parallel-threshold+` = 8,192** (below this, sequential reduce).

Span: O(n/p + log² n) where p = number of processors.

## Interval Tree Augmentation

`IntervalNode` adds a field `z`: the maximum right endpoint in the subtree. This is maintained during rotations — each node's `z` is the max of its own interval's endpoint and its children's `z` values.

```
      ┌─────────────────────┐
      │  interval: [3,7]    │
      │  max-end: 15        │  ← max of all endpoints in subtree
      └─────────┬───────────┘
                │
     ┌──────────┴──────────┐
     ▼                     ▼
┌─────────┐          ┌─────────┐
│ [1,5]   │          │ [8,15]  │
│ max: 5  │          │ max: 15 │
└────┬────┘          └────┬────┘
     │                    │
  ┌──┴──┐              ┌──┴──┐
  ▼     ▼              ▼     ▼
[0,2] [4,6]         [6,10] [12,15]
max:2 max:6         max:10 max:15
```

### Query algorithm

The implementation supports both point queries and interval-vs-interval overlap queries. Given a query interval `i`:

```
search(node, i):
  if leaf: return

  # Search right if query's endpoint ≥ node's start point
  if b(i) >= a(node.key):
    search(right, i)

  # Check current node for intersection
  if intersects?(i, node.key):
    collect(node)

  # Search left only if query's start ≤ max endpoint in left subtree
  if a(i) <= left.z:
    search(left, i)
```

`intersects?` checks for any common point between two intervals (overlap, containment in either direction). The `z` field enables pruning: if `left.z < a(query)`, no interval in the left subtree can overlap the query.

Complexity: O(log n + k) where k = number of matching intervals.

Point queries are a special case: a point `p` is treated as the interval `[p, p]`.

## Range Map

`range-map` enforces non-overlapping ranges. Each point maps to exactly one value. Ranges are half-open: `[lo, hi)`.

### Insert (assoc)

Inserting `[25, 75) → :new` into a tree containing `[0, 100) → :a`:

```
Step 1: Find all ranges overlapping [25, 75)
  → [[0, 100) → :a]

Step 2: Remove overlapping ranges from tree

Step 3: Re-insert trimmed portions outside [25, 75)
  → [0, 25) → :a,  [75, 100) → :a

Step 4: Insert new range
  → [0, 25) → :a,  [25, 75) → :new,  [75, 100) → :a
```

### Coalescing insert (assoc-coalescing)

When adjacent ranges have the same value, merge them:

```
Before: [0, 50) → :a    [50, 100) → :a   (two ranges)
Insert [100, 150) → :a with coalescing:
After:  [0, 50) → :a    [50, 150) → :a   (adjacent merged)
```

Complexity: O(k log n) where k = number of overlapping/adjacent ranges.

## Segment Tree

Each `AggregateNode` stores a pre-computed aggregate (`agg`) of its entire subtree under a user-specified associative operation. Created via a custom node constructor that computes `agg = op(left.agg, op(value, right.agg))` at every node.

```
                ┌─────────────┐
                │ key: 3      │
                │ val: 40     │
                │ agg: 150 ◄──────── sum of entire tree
                └──────┬──────┘
           ┌───────────┴───────────┐
    ┌──────┴──────┐         ┌──────┴──────┐
    │ key: 1      │         │ key: 4      │
    │ val: 20     │         │ val: 50     │
    │ agg: 30     │         │ agg: 80     │
    └──────┬──────┘         └──────┬──────┘
           │                       │
    ┌──────┴──────┐         ┌──────┴──────┐
    │ key: 0      │         │ key: 5      │
    │ val: 10     │         │ val: 30     │
    │ agg: 10     │         │ agg: 30     │
    └─────────────┘         └─────────────┘
```

### Range query

Two implementations: `query-range` (basic) and `query-range-fast` (uses subtree bounds to short-circuit).

```
query-range-fast(node, lo, hi):
  if leaf: return identity

  # Find subtree's actual key range
  l-lo = min key in left subtree (or node.key if left is leaf)
  r-hi = max key in right subtree (or node.key if right is leaf)

  # Entire subtree outside range
  if r-hi < lo or l-lo > hi: return identity

  # Entire subtree inside range → use pre-computed aggregate!
  if lo ≤ l-lo and r-hi ≤ hi: return node.agg

  # Partial overlap → recurse
  L = query-range-fast(left, lo, hi)
  V = node.val if lo ≤ node.key ≤ hi, else identity
  R = query-range-fast(right, lo, hi)
  return op(L, op(V, R))
```

The key optimization: when a subtree is entirely within the query range, return its `agg` directly instead of recursing. This gives O(log n) for both queries and updates.

## Fuzzy Lookup

Fuzzy collections find the closest element by distance. The algorithm uses split:

```
find-nearest(tree, query):
  (left, exact, right) = split(tree, query)

  if exact: return exact

  floor   = greatest(left)    ← O(log n)
  ceiling = least(right)      ← O(log n)

  return argmin(|query - floor|, |query - ceiling|)
```

When equidistant, a configurable tiebreaker (`:< ` or `:>`) determines preference. The `distance-fn` is also configurable (defaults to numeric absolute difference).

**Invariant:** The nearest element by distance is always a sort-order neighbor (floor or ceiling), so split gives us the only two candidates. O(log n).

## Handling Duplicates

`ordered-multiset` and `priority-queue` allow duplicate keys by appending an internal sequence counter.

**Multiset** stores `[value, seqnum]` pairs. Comparison: first by value, then by seqnum. This gives stable insertion order for equal values and FIFO behavior on removal.

**Priority queue** stores `[priority, seqnum, value]` triples. Sorted by priority first, then seqnum. `peek` returns the minimum-priority element; among equal priorities, the earliest inserted.

## Complexity Summary

| Operation | Time | Notes |
|-----------|------|-------|
| Lookup | O(log n) | All collections |
| Insert / Delete | O(log n) | Persistent (path copying) |
| nth / rank | O(log n) | Via subtree weights |
| median / percentile | O(log n) | Via nth |
| nearest (floor/ceiling) | O(log n) | Ordered sets and maps |
| Split (by key or index) | O(log n) | |
| Join | O(log n) | Universal primitive |
| Union / Intersection / Difference | O(m log(n/m+1)) | Work-optimal, fork-join parallel |
| Parallel fold | O(n/p + log²n) | p = processors |
| Interval query | O(log n + k) | k = result count |
| Range-map assoc | O(k log n) | k = overlapping ranges |
| Segment-tree query | O(log n) | Pre-computed aggregates |
| Fuzzy lookup | O(log n) | Split + floor/ceiling |

## References

- Adams (1992): "Implementing Sets Efficiently in a Functional Language" — split/join paradigm, divide-and-conquer set operations
- Adams (1993): "Efficient sets—a balancing act" — elegant functional pearls treatment
- Hirai & Yamamoto (2011): "Balancing Weight-Balanced Trees" — Coq-verified (δ=3, γ=2) parameters
- Blelloch, Ferizovic & Sun (2016): "Just Join for Parallel Ordered Sets" — work-optimality proof, parallel algorithms
- Sun, Ferizovic & Blelloch (2018): "PAM: Parallel Augmented Maps" — augmented tree framework (interval/segment trees)
