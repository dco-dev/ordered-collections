# Algorithms

This document describes the algorithms used in this library.

## Core Data Structure: Weight-Balanced Trees

Each node stores: key, value, left child, right child, and subtree weight.

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

**Weight = 1 + left.weight + right.weight.** Leaves have weight 0 (represented as nil/sentinel).

The weight field enables O(log n) positional access (`nth`): to find the i-th element, compare i against the left subtree's weight and recurse accordingly.

## Balance Invariant

Using Hirai-Yamamoto parameters (δ=3, γ=2):

```
size(left) + 1 ≤ δ × (size(right) + 1)
size(right) + 1 ≤ δ × (size(left) + 1)
```

No subtree can be more than 3× the size of its sibling. When an operation violates this, we rebalance with rotations.

**Balanced example:**
```
       [50]
      wt: 7
     /     \
  [25]     [75]
  wt:3     wt:3

Left: 3, Right: 3
Check: 3+1 ≤ 3×(3+1) → 4 ≤ 12 ✓
```

**Unbalanced example:**
```
       [50]
      wt: 9
     /     \
  [25]     [75]
  wt:7     wt:1

Left: 7, Right: 1
Check: 7+1 ≤ 3×(1+1) → 8 ≤ 6 ✗
```

## Rotations

**Single right rotation** — when the left subtree is heavy and its left child is the cause:

```
BEFORE:                         AFTER:
       [C]                           [A]
      /   \                         /   \
    [A]    z     ───────►          x    [C]
   /   \         rotate-R              /   \
  x    [B]                           [B]    z
```

**Double rotation** — when the left subtree is heavy but its *right* child is the cause:

```
BEFORE:              STEP 1:              AFTER:
     [C]                [C]                  [B]
    /   \              /   \                /   \
  [A]    z    →      [B]    z    →       [A]   [C]
 /   \              /   \               /  \   /  \
w    [B]          [A]    y             w   x  y   z
    /   \        /   \
   x     y      w     x
```

The γ parameter determines when to use single vs double rotation.

## Split and Join

These two operations are the foundation for everything else.

**Split** divides a tree at a key into three parts: left (keys < pivot), found (key = pivot or nil), right (keys > pivot).

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

**Join** combines two trees where all keys in left < all keys in right:

```
join(left, 50, right):

 LEFT           RIGHT
  [25]           [75]
  /  \           /  \
[10] [30]     [60] [90]

            ↓

          [50]
         /    \
      [25]    [75]
      /  \    /  \
    [10][30][60][90]
```

Both operations are O(log n). The key insight: split and join preserve balance with only O(log n) rebalancing work.

## Positional Access: nth and rank

Weight-balanced trees store subtree sizes, enabling efficient positional operations.

### nth (index → element): O(log n)

```
nth(tree, 4):  Find 5th element (0-indexed)

         [50, wt:7]
        /          \
   [25, wt:3]    [75, wt:3]
     /   \         /   \
   [10] [30]    [60]  [90]
   wt:1 wt:1    wt:1  wt:1

Step 1: i=4, left.weight=3
        4 >= 3, so go right, i = 4 - 3 - 1 = 0

Step 2: at [75], i=0, left.weight=1
        0 < 1, so go left

Step 3: at [60], i=0, left.weight=0
        0 == 0, return 60

Answer: 60
```

### rank (element → index): O(log n)

Only available in `ranked-set`. Accumulates left subtree sizes while descending:

```
rank(tree, 60):

         [50, wt:7]         rank = 0
        /          \
   [25, wt:3]    [75, wt:3]
     /   \         /   \
   [10] [30]    [60]  [90]

Step 1: 60 > 50, rank += left.weight + 1 = 3 + 1 = 4
Step 2: 60 < 75, keep rank = 4, go left
Step 3: 60 == 60, rank += 0 = 4

Answer: 4 (60 is the 5th element)
```

**Note:** `ordered-set` supports O(log n) `nth` but not `rank`. Use `ranked-set` when you need both operations efficiently.

## Set Operations

Union, intersection, and difference use Adams' divide-and-conquer approach, built on split and join:

```
intersection(A, B):
  if empty(A) or empty(B): return empty

  (left-B, found, right-B) = split(B, root(A).key)

  left-result  = intersection(left(A), left-B)   ─┐
  right-result = intersection(right(A), right-B) ─┴─ parallel!

  if found:
    return join(left-result, root(A).key, right-result)
  else:
    return concat(left-result, right-result)
```

The two recursive calls are independent and execute in parallel via fork-join. This is why the divide-and-conquer structure is powerful: parallelism falls out naturally.

**Visual example:**

```
A = {1, 3, 5, 7, 9}         B = {2, 3, 5, 8}

Split B at 5 (root of A):
  left-B  = {2, 3}
  found   = true (5 ∈ B)
  right-B = {8}

Recurse on (left-A, left-B) and (right-A, right-B)
Join results with 5 in the middle

Result = {3, 5}
```

Complexity: O(m log(n/m + 1)) where m ≤ n. This is **work-optimal**: it matches the information-theoretic lower bound. When m << n, it's nearly O(m); when m ≈ n, it's O(n). The naive approach of inserting m elements one-by-one would be O(m log n), which is worse when m is large.

## The Join-Based Paradigm

A key insight from Blelloch et al.: **join is the universal primitive**. All tree operations reduce to split and join:

| Operation | Implementation |
|-----------|----------------|
| insert(k) | split at k, join with new node |
| delete(k) | split at k, join left and right |
| union(A,B) | split B at root(A), recurse, join |
| intersect(A,B) | split B at root(A), recurse, join if found |
| difference(A,B) | split B at root(A), recurse, concat |

This unification means:
- Balance logic lives only in `join`
- All operations inherit O(log n) balancing automatically
- Parallel algorithms follow naturally: the recursive calls on left and right subtrees are independent and can execute concurrently via fork-join

## Parallel Construction

Building a tree from a collection uses fork-join parallelism:

```
Input: [10, 25, 30, 50, 60, 75, 90]

Step 1: Partition into chunks (via r/fold)
  Chunk A: [10, 25, 30]    Chunk B: [50, 60, 75, 90]

Step 2: Build subtrees in parallel
  Thread 1:              Thread 2:
      [25]                   [60]
      /  \                   /  \
   [10]  [30]             [50]  [75]
                                   \
                                   [90]

Step 3: Merge via union (which uses split + join)
                [50]
               /    \
            [25]    [75]
            /  \    /  \
         [10][30][60] [90]
```

This achieves O(n) work with O(n/p + log² n) span, compared to O(n log n) for sequential insertion.

## Parallel Fold

The same split capability enables parallel aggregation:

```
         [50]               Fork:
        /    \                Thread 1 → fold [10,25,30]
     [25]    [75]             Thread 2 → fold [60,75,90]
     /  \    /  \           Join:
   [10][30][60][90]           combine(result1, result2)
```

When a subtree exceeds a threshold size, `r/fold` submits it to a worker thread. This gives ~2x speedup on large collections.

## Interval Tree Augmentation

For interval queries, each node stores an additional field: the maximum endpoint in its subtree.

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

The max-end field enables efficient pruning: if `max-end < query-point`, no intervals in that subtree can overlap the query.

### Query Algorithm

```
find-overlapping(node, point):
  if node is leaf: return []

  results = []
  [lo, hi] = node.interval

  # Check this node
  if lo <= point < hi:
    results.add(node.interval)

  # Prune left subtree if max-end too small
  if left.max-end > point:
    results.addAll(find-overlapping(left, point))

  # Prune right subtree if all intervals start after point
  if point >= lo:  # some right intervals might overlap
    results.addAll(find-overlapping(right, point))

  return results
```

Complexity: O(log n + k) where k = number of matching intervals.

## Range Map: Non-Overlapping Intervals

`range-map` enforces that ranges never overlap. When inserting a new range, overlapping portions of existing ranges are carved out.

### Carving Algorithm (assoc)

```
Insert [25, 75) into:
    ┌──────────────────────────────────────────┐
    │               [0, 100) → :a              │
    └──────────────────────────────────────────┘

Step 1: Find overlapping ranges
    overlap = [[0,100) → :a]

Step 2: Remove overlapping ranges
    (empty tree)

Step 3: Add back trimmed portions outside [25, 75)
    [0, 25) → :a     [75, 100) → :a

Step 4: Insert new range
    [0, 25) → :a   [25, 75) → :new   [75, 100) → :a
```

### Coalescing Algorithm (assoc-coalescing)

When inserting, check for adjacent ranges with the same value and merge them:

```
Before: [0, 50) → :a    [50, 100) → :a
        ─────────────────────────────────
        Two separate ranges

Insert [100, 150) → :a with coalescing:

Step 1: Find adjacent-left: [50, 100) → :a (ends at 100, same value)
Step 2: Find adjacent-right: none
Step 3: Merge: remove [50, 100), insert [50, 150) → :a

After:  [0, 50) → :a    [50, 150) → :a
```

Complexity: O(k log n) where k = number of overlapping/adjacent ranges.

## Segment Tree: Range Aggregates

Each node stores a pre-computed aggregate of its entire subtree, enabling O(log n) range queries.

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
    │ agg: 30 ◄───────      │ agg: 80 ◄───────
    └──────┬──────┘   │     └──────┬──────┘   │
           │          │            │          │
    ┌──────┴──────┐   │     ┌──────┴──────┐   │
    │ key: 0      │   │     │ key: 5      │   │
    │ val: 10     │   │     │ val: 30     │   │
    │ agg: 10     │   │     │ agg: 30     │   │
    └─────────────┘   │     └─────────────┘   │
                      │                       │
           10 + 20 = 30              50 + 30 = 80
```

### Range Query Algorithm

```
query(node, lo, hi):
  if node is leaf: return identity

  k = node.key

  # Entire subtree outside range
  if subtree.max < lo or subtree.min > hi:
    return identity

  # Entire subtree inside range - use pre-computed aggregate!
  if lo <= subtree.min and subtree.max <= hi:
    return node.agg

  # Partial overlap - recurse
  left-result  = query(left, lo, hi)
  right-result = query(right, lo, hi)
  this-result  = if lo <= k <= hi then node.val else identity

  return op(left-result, op(this-result, right-result))
```

The key insight: when a subtree is entirely within the query range, we use its pre-computed aggregate instead of visiting all nodes.

Complexity: O(log n) for both queries and updates.

## Fuzzy Lookup: Nearest Neighbor

Fuzzy collections find the closest element when an exact match doesn't exist.

```
Query: find nearest to 7 in {1, 5, 10, 20}

Step 1: Split at query point
   FLOOR (≤7)          CEILING (≥7)
      [5]                  [10]
      /                       \
    [1]                      [20]

Step 2: Find candidates
   floor   = 5  (rightmost in left tree)
   ceiling = 10 (leftmost in right tree)

Step 3: Compare distances
   |7 - 5|  = 2
   |7 - 10| = 3

   Return 5 (closer)
```

When equidistant, the tiebreaker (`:< `or `:>`) determines preference.

**Invariant:** The nearest element by distance is always a sort-order neighbor (floor or ceiling). This allows O(log n) lookup via split.

Complexity: O(log n).

## Handling Duplicates: Sequence Numbers

Both `ordered-multiset` and `priority-queue` allow duplicate values. They distinguish duplicates using an internal sequence counter.

### Multiset Entry Structure

```
Logical view: [3, 1, 4, 1, 5, 1]  (three 1s)

Internal storage: [value, seqnum] pairs
  [1, 0]  ← first 1 inserted
  [1, 3]  ← second 1 inserted (seqnum 3)
  [1, 5]  ← third 1 inserted (seqnum 5)
  [3, 1]
  [4, 2]
  [5, 4]
```

Comparison: first by value, then by seqnum. This provides:
- Stable insertion order for equal values
- O(log n) operations (each entry is unique)
- FIFO behavior for duplicates

### Priority Queue Entry Structure

```
Entries: [priority, seqnum, value]

Insert order: push(5, :a), push(3, :b), push(5, :c)

Internal storage:
  [3, 1, :b]  ← lowest priority first
  [5, 0, :a]  ← first 5 inserted
  [5, 2, :c]  ← second 5 inserted

peek returns :b (priority 3)
```

Seqnum ensures FIFO ordering among equal priorities.

## Complexity Summary

| Operation | Time | Notes |
|-----------|------|-------|
| Lookup | O(log n) | All collections |
| Insert | O(log n) | Path copying |
| Delete | O(log n) | Path copying |
| nth | O(log n) | Via subtree weights |
| rank | O(log n) | `ranked-set` only |
| Split | O(log n) | |
| Join | O(log n) | Universal primitive |
| Union | O(m log(n/m+1)) | Work-optimal, fork-join parallel |
| Intersection | O(m log(n/m+1)) | Work-optimal, fork-join parallel |
| Difference | O(m log(n/m+1)) | Work-optimal, fork-join parallel |
| Batch construction | O(n) | Via parallel fold + union |
| Parallel fold | O(n/p + log²n) | p = processors |
| Interval query | O(log n + k) | k = result size |
| Range-map assoc | O(k log n) | k = overlapping ranges |
| Segment-tree query | O(log n) | Pre-computed aggregates |
| Fuzzy lookup | O(log n) | Split + floor/ceiling |

## References

- Adams (1993): "Efficient sets—a balancing act" — divide-and-conquer set operations
- Hirai & Yamamoto (2011): "Balancing Weight-Balanced Trees" — correct δ/γ parameters
- Blelloch et al. (2016): "Just Join for Parallel Ordered Sets" — parallel algorithms, work-optimality proof
