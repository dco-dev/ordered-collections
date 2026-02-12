# Algorithms

This document describes the algorithms used in this library.

## Core Data Structure

Each node stores: key, value, left child, right child, and subtree size (weight).

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

Weight = 1 + left.weight + right.weight. Leaves have weight 1.

The weight at each node enables O(log n) positional access: to find the nth element, compare n against the left subtree's weight and recurse accordingly.

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

**Split** divides a tree at a key into three parts:

```
split(tree, 45):

         [50]
        /    \
     [25]    [75]
     /  \    /  \
   [10][30][60][90]

            ↓

 LEFT (<45)          RIGHT (≥45)
    [25]                [50]
    /  \               /    \
 [10]  [30]         [60]   [75]
                             \
                             [90]
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

## Set Operations

Union, intersection, and difference use Adams' divide-and-conquer approach, built on split and join:

```
intersection(A, B):
  if empty(A) or empty(B): return empty

  (left-B, found, right-B) = split(B, root(A).key)

  left-result  = intersection(left(A), left-B)
  right-result = intersection(right(A), right-B)

  if found:
    return join(left-result, root(A).key, right-result)
  else:
    return concat(left-result, right-result)
```

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

Complexity: O(m log(n/m + 1)) where m ≤ n. This is work-optimal.

## Parallel Fold

The ability to split trees enables divide-and-conquer parallelism:

```
         [50]               Fork:
        /    \                Thread 1 → fold [10,25,30]
     [25]    [75]             Thread 2 → fold [60,75,90]
     /  \    /  \           Join:
   [10][30][60][90]           Combine results
```

When a subtree exceeds a threshold size, we submit it to ForkJoinPool. This gives ~2x speedup on large collections.

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
│ max: 6  │          │ max: 15 │
└────┬────┘          └────┬────┘
     │                    │
  ┌──┴──┐              ┌──┴──┐
  ▼     ▼              ▼     ▼
[0,2] [4,6]         [6,10] [12,15]
```

The max-end field enables efficient pruning: if `max-end < query-point`, no intervals in that subtree can overlap the query.

Complexity: O(log n + k) where k = number of matching intervals.

## Fuzzy Lookup

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

Custom distance functions work when the nearest element by distance is always a sort-order neighbor (floor or ceiling).

Complexity: O(log n).

## Complexity Summary

| Operation | Time | Notes |
|-----------|------|-------|
| Lookup | O(log n) | |
| Insert | O(log n) | O(log n) path copying |
| Delete | O(log n) | O(log n) path copying |
| nth | O(log n) | Via subtree weights |
| rank | O(log n) | Via subtree weights |
| Split | O(log n) | |
| Join | O(log n) | |
| Union | O(m log(n/m+1)) | m ≤ n |
| Intersection | O(m log(n/m+1)) | m ≤ n |
| Difference | O(m log(n/m+1)) | m ≤ n |
| Parallel fold | O(n/p + log n) | p = processors |
| Interval query | O(log n + k) | k = result size |
| Fuzzy lookup | O(log n) | |

## References

- Adams (1993): "Efficient sets—a balancing act" — divide-and-conquer set operations
- Hirai & Yamamoto (2011): "Balancing Weight-Balanced Trees" — correct δ/γ parameters
- Blelloch et al. (2016): "Just Join for Parallel Ordered Sets" — parallel algorithms, work-optimality proof
