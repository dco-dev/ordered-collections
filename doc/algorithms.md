# Algorithm Guide

A visual tour of how weight-balanced trees work.

## Tree Structure

### Basic Node Layout

Each node stores a key, value, left child, right child, and subtree weight:

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

**Weight** = 1 + left.weight + right.weight (leaf weight = 1)

The weight enables O(log n) nth and rank operations by counting nodes.

## Balance Invariant

A tree is balanced when for every node:

```
size(left) + 1 <= δ × (size(right) + 1)
size(right) + 1 <= δ × (size(left) + 1)
```

With δ = 3, no subtree can be more than 3× heavier than its sibling.

### Balanced Example (δ = 3)

```
         [50]
        wt: 7
       /     \
    [25]     [75]
    wt:3     wt:3

Left: 3, Right: 3
Check: 3+1 <= 3×(3+1) → 4 <= 12 ✓
```

### Unbalanced Example

```
         [50]
        wt: 9
       /     \
    [25]     [75]
    wt:7     wt:1

Left: 7, Right: 1
Check: 7+1 <= 3×(1+1) → 8 <= 6 ✗ UNBALANCED!
```

## Rotations

### Single Right Rotation

When the left subtree is too heavy and its left child is the cause:

```
BEFORE:                         AFTER:
       [C]                           [A]
      /   \                         /   \
    [A]    z     ───────►          x    [C]
   /   \         rotate-R              /   \
  x    [B]                           [B]    z
```

Code essence:
```clojure
(defn rotate-right [node]
  (let [l (left node)]
    (create (key l) (val l)
            (left l)
            (create (key node) (val node)
                    (right l)
                    (right node)))))
```

### Single Left Rotation

Mirror image for right-heavy trees:

```
BEFORE:                         AFTER:
    [A]                              [C]
   /   \                            /   \
  x    [C]       ───────►         [A]    z
      /   \      rotate-L        /   \
    [B]    z                    x    [B]
```

### Double Rotation

When the left subtree is heavy but its RIGHT child is the cause:

```
BEFORE:              STEP 1:              STEP 2 (AFTER):
     [C]                [C]                    [B]
    /   \              /   \                  /   \
  [A]    z    ──►    [B]    z     ──►      [A]   [C]
 /   \              /   \                 /  \   /  \
w    [B]          [A]    y               w   x  y   z
    /   \        /   \
   x     y      w     x

         rotate-left(A)        rotate-right(C)
```

## Insertion

### Step 1: Find insertion point

Descend the tree comparing keys:

```
Insert 35 into:

      [50]
     /    \
   [25]   [75]

Compare: 35 < 50 → go left
Compare: 35 > 25 → go right
Found empty slot: insert here
```

### Step 2: Create new node

```
      [50]
     /    \
   [25]   [75]
      \
      [35]  ← NEW
```

### Step 3: Rebalance on the way up

After insertion, check balance at each ancestor:

```
Node [25]: left=0, right=1 → balanced (1 <= 3×1)
Node [50]: left=2, right=1 → balanced (3 <= 3×2)
```

If unbalanced, apply rotations.

## Deletion

### Case 1: Leaf node

Simply remove:

```
Delete 35:

      [50]              [50]
     /    \    ──►     /    \
   [25]   [75]       [25]   [75]
      \
      [35]
```

### Case 2: One child

Replace with child:

```
Delete 25:

      [50]              [50]
     /    \    ──►     /    \
   [25]   [75]       [35]   [75]
      \
      [35]
```

### Case 3: Two children

Replace with in-order successor (leftmost in right subtree):

```
Delete 50:

      [50]              [60]
     /    \    ──►     /    \
   [25]   [75]       [25]   [75]
         /                  /
       [60]               [65]
          \
          [65]
```

## Split Operation

Split divides a tree at a key into two trees:

```
split([50, 25, 75, 10, 30, 60, 90], key=45)

           [50]
          /    \
       [25]    [75]
       /  \    /  \
     [10][30][60][90]

              ↓ split at 45

   LEFT (<45)          RIGHT (>=45)
      [25]                [50]
      /  \               /    \
   [10]  [30]         [60]   [75]
                               \
                               [90]
```

### Split Algorithm

```
split(node, key):
  if node is empty:
    return (empty, empty)

  if key < node.key:
    (ll, lr) = split(node.left, key)
    return (ll, join(lr, node.key, node.right))

  if key > node.key:
    (rl, rr) = split(node.right, key)
    return (join(node.left, node.key, rl), rr)

  else: // key == node.key
    return (node.left, node.right)
```

The magic: each recursive call does O(1) work, and we recurse O(log n) times.

## Join Operation

Join combines two trees with all keys in the left < all keys in the right:

```
join(left, key, right):

  LEFT          KEY         RIGHT
   [25]          50          [75]
   /  \                      /  \
 [10] [30]                [60] [90]

                ↓

            [50]
           /    \
        [25]    [75]
        /  \    /  \
      [10][30][60][90]
```

### Join Algorithm

```
join(left, key, right):
  if weight(left) > δ × weight(right):
    // Left is much heavier, insert into left's right spine
    return create(left.key, left.val,
                  left.left,
                  join(left.right, key, right))

  if weight(right) > δ × weight(left):
    // Right is much heavier, insert into right's left spine
    return create(right.key, right.val,
                  join(left, key, right.left),
                  right.right)

  else:
    // Balanced enough, create node directly
    return create(key, val, left, right)
```

## Set Intersection via Split/Join

```clojure
intersection(A, B):
  if A is empty or B is empty:
    return empty

  (left-B, found, right-B) = split-lookup(B, root(A).key)

  left-result = intersection(left(A), left-B)
  right-result = intersection(right(A), right-B)

  if found:
    return join(left-result, root(A).key, right-result)
  else:
    return concat(left-result, right-result)
```

Visual:

```
A = {1, 3, 5, 7, 9}         B = {2, 3, 5, 8}

Split B at 5 (root of A):
  left-B = {2, 3}
  found = true (5 is in B)
  right-B = {8}

Recurse on (left-A, left-B) and (right-A, right-B)
Join results with 5 in the middle

Result = {3, 5}
```

Complexity: O(m log(n/m + 1)) where m ≤ n

## Parallel Fold

Trees split naturally for parallel processing:

```
           [50]               Thread 1: fold [10,25,30]
          /    \              Thread 2: fold [60,75,90]
       [25]    [75]           Then combine results
       /  \    /  \
     [10][30][60][90]
```

### Chunked Fold Algorithm

```
chunked-fold(tree, chunk-size, combine, reduce):
  if weight(tree) <= chunk-size:
    // Small enough, reduce sequentially
    return reduce(identity, tree)

  // Split and fork
  left-future = fork(chunked-fold(left, ...))
  right-result = chunked-fold(right, ...)
  left-result = join(left-future)

  return combine(left-result,
                 reduce(identity, [root]),
                 right-result)
```

## Interval Tree Augmentation

For interval queries, each node stores the maximum endpoint in its subtree:

```
        ┌─────────────────────┐
        │  interval: [3,7]    │
        │  max-end: 15        │  ← max of all endpoints below
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

### Interval Query Algorithm

```
find-overlapping(node, query-point):
  if node is empty:
    return []

  results = []

  // Check if this interval overlaps
  if query-point >= interval.start AND query-point <= interval.end:
    results += this interval

  // Check left subtree if it might contain overlaps
  if left.max-end >= query-point:
    results += find-overlapping(left, query-point)

  // Check right subtree if intervals might start before query-point
  if interval.start <= query-point:
    results += find-overlapping(right, query-point)

  return results
```

Complexity: O(log n + k) where k = number of overlapping intervals

## Fuzzy Lookup (Nearest Neighbor)

Fuzzy collections find the closest element when an exact match doesn't exist:

```
Query: find nearest to 7 in {1, 5, 10, 20}

Step 1: Split tree at query point
           [10]
          /    \
        [5]    [20]
        /
      [1]
              ↓ split at 7

   FLOOR (<=7)          CEILING (>=7)
      [5]                  [10]
      /                    /  \
    [1]                 (empty) [20]

Step 2: Find floor (greatest <= query)
   floor = 5 (rightmost in left tree)

Step 3: Find ceiling (least >= query)
   ceiling = 10 (leftmost in right tree)

Step 4: Compare distances
   distance(7, 5) = 2
   distance(7, 10) = 3

   floor is closer → return 5
```

### Tiebreaker

When two elements are equidistant, use tiebreaker:

```
Query: find nearest to 7.5 in {5, 10}

distance(7.5, 5) = 2.5
distance(7.5, 10) = 2.5

:< tiebreak → return 5 (prefer smaller)
:> tiebreak → return 10 (prefer larger)
```

### Custom Distance Functions

The default distance is |a - b| for numeric types. Custom distance
functions work when the closest element by distance is always a
sort-order neighbor (floor or ceiling).

Complexity: O(log n) - single tree split operation

## Complexity Summary

| Operation | Time | Space |
|-----------|------|-------|
| Lookup | O(log n) | O(1) |
| Insert | O(log n) | O(log n) path copy |
| Delete | O(log n) | O(log n) path copy |
| nth | O(log n) | O(1) |
| rank-of | O(log n) | O(1) |
| Split | O(log n) | O(log n) |
| Join | O(log n) | O(log n) |
| Union | O(m log(n/m+1)) | O(m + n) |
| Intersection | O(m log(n/m+1)) | O(min(m,n)) |
| Difference | O(m log(n/m+1)) | O(m) |
| Fold (parallel) | O(n/p + log n) | O(log n) |
| Interval query | O(log n + k) | O(k) |
| Fuzzy lookup | O(log n) | O(log n) |

Where n ≥ m, p = processors, k = result size.
