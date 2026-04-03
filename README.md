# com.dean/ordered-collections

**Collections that do more than sort.**

Fast, complete ordered collections. Drop-in replacements for
`sorted-set` and `sorted-map` — with O(log n) positional access,
parallel fold, and specialized collections for problems you didn't know
you could solve efficiently:

- **Interval maps** for overlap queries ("what's scheduled at 3pm?")
- **Range maps** for non-overlapping regions ("which subnet owns this IP?")
- **Segment trees** for range aggregation ("total sales from day 10 to 50?")
- **Fuzzy collections** for nearest-neighbor lookup ("snap 9.3 to the closest valid size")
- **Priority queues**, **multisets**, and more

All built from a fast, modular, extensible weight-balanced tree platform with shared
foundation for splitting, joining, and parallel operations.

![tests](https://github.com/dco-dev/ordered-collections/actions/workflows/clojure.yml/badge.svg)
[![Clojars Project](https://img.shields.io/clojars/v/com.dean/ordered-collections.svg)](https://clojars.org/com.dean/ordered-collections)

### Documentation

- [Zorp's Sneaker Emporium](doc/zorp-example.md) — Narrative guide with extended examples
- [Cookbook](doc/cookbook.md) — Practical patterns: leaderboards, time-series, scheduling, IP ranges
- [When to Use](doc/when-to-use.md) — Decision guide for choosing the right collection type
- [Benchmarks](doc/benchmarks.md) — Detailed performance measurements
- [Competitive Analysis](doc/competitive-analysis.md) — Comparison with other libraries
- [vs clojure.data.avl](doc/vs-clojure-data-avl.md) — For data.avl users considering a switch
- [Algorithms](doc/algorithms.md) — Tree structure, rotations, split/join, interval augmentation
- [Why Weight-Balanced Trees?](doc/why-weight-balanced-trees.md) — Comparison with red-black and AVL trees

---

## Quick Start

Use `ordered-set` and `ordered-map` exactly like
`clojure.core/sorted-set` and `clojure.core/sorted-map`. All the functions you know work the same way. The difference is under the
hood — and in the new things you can do.


```clojure

(require '[com.dean.ordered-collections.core :as oc])

;; Sets
(def s (oc/ordered-set [3 1 4 1 5 9 2 6]))
(s 4)           ;=> 4
(s 7)           ;=> nil
(conj s 0)      ;=> #{0 1 2 3 4 5 6 9}
(disj s 4)      ;=> #{1 2 3 5 6 9}
(first s)       ;=> 1
(last s)        ;=> 9
(subseq s > 3)  ;=> (4 5 6 9)

;; Maps
(def m (oc/ordered-map {:b 2 :a 1 :c 3}))
(m :b)                  ;=> 2
(assoc m :d 4)          ;=> {:a 1, :b 2, :c 3, :d 4}
(subseq m >= :b <= :c)  ;=> ([:b 2] [:c 3])
```
---

## Performance

Across the measured set-heavy workloads, `ordered-collections` is faster than
both `sorted-set` and `data.avl` at every cardinality measured. Lookups stay
close to parity and are not a headline differentiator. Set algebra is the standout:
the current Criterium run ranges from high-single-digit wins at 10K to
30-46x wins at 500K. Even against the unfair `clojure.set` + hash-set baseline,
the current set-algebra benchmarks still show roughly 4-24x wins.

### vs sorted-set (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Construction | **2.9x** | **2.8x** | **2.7x** |
| Lookup | 1.3x | 1.2x | 1.2x |
| Union | **13.9x** | **22.4x** | **44.3x** |
| Intersection | **8.7x** | **15.8x** | **32.4x** |
| Difference | **9.4x** | **21.7x** | **46.1x** |
| Fold | **3.7x** | **7.3x** | **9.7x** |

### vs data.avl (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **11.5x** | **19.3x** | **39.6x** |
| Intersection | **7.2x** | **13.5x** | **27.5x** |
| Difference | **7.3x** | **13.6x** | **32.3x** |
| Split | **3.1x** | **3.6x** | **3.7x** |
| Fold | 1.0x | **3.0x** | **3.4x** |
| Construction | **1.5x** | **1.4x** | **1.5x** |

### vs clojure.set on hash-set (speedup)

| Operation | N=10K | N=100K | N=500K |
|-----------|------:|-------:|-------:|
| Union | **4.1x** | **7.3x** | **19.7x** |
| Intersection | **4.3x** | **7.6x** | **17.3x** |
| Difference | **5.1x** | **9.2x** | **24.2x** |

*[Criterium](https://github.com/hugoduncan/criterium) at all sizes.
See [Benchmarks](doc/benchmarks.md) for full results.*

### Beyond speed

| | sorted-set | data.avl | ordered-set |
|--|:---:|:---:|:---:|
| Endpoint access | O(1) `first`, O(n) seq `last` | O(1) `first`, O(n) seq `last` | **O(1) `first`, O(log n) `.last`** |
| `nth` / rank | — | O(log n) | O(log n) |
| `nearest` / subrange | — | O(log n) | O(log n) |
| `split` | — | O(log n) | O(log n) |
| Parallel `r/fold` | Sequential | Sequential | **Parallel** |
| Intervals / ranges / segments | — | — | **Built-in** |

---

## How It Works

The core is a **weight-balanced binary tree**.  Each node knows its subtree size, enabling O(log n) positional access and efficient parallel decomposition.

**Split and join** are the fundamental primitives — splitting at a key produces two trees in O(log n); joining is also O(log n). Set operations, subrange extraction, and parallel fold all reduce to split/join. Set operations use Adams' divide-and-conquer algorithm (1992) extended with the parallel join-based approach from Blelloch, Ferizovic & Sun (2016).

Collection constructors provide the comparator and node-construction hooks, so
the same tree algorithms can back generic, primitive-specialized, and augmented
variants.

**Augmented trees** extend the basic structure: interval trees store max-endpoint per subtree for O(log n + k) overlap queries; segment trees store aggregates for O(log n) range queries.

See [Algorithms](doc/algorithms.md) for implementation details and [Why Weight-Balanced Trees?](doc/why-weight-balanced-trees.md) for comparison with red-black and AVL trees.

---

## Collections

| Constructor | Description |
|-------------|-------------|
| `(oc/ordered-set coll)` | Sorted set (drop-in for `sorted-set`) |
| `(oc/ordered-set-by pred coll)` | Sorted set with custom comparator |
| `(oc/long-ordered-set coll)` | Sorted set optimized for Long keys |
| `(oc/string-ordered-set coll)` | Sorted set optimized for String keys |
| `(oc/ordered-map coll)` | Sorted map (drop-in for `sorted-map`) |
| `(oc/ordered-map-by pred coll)` | Sorted map with custom comparator |
| `(oc/long-ordered-map coll)` | Sorted map optimized for Long keys |
| `(oc/string-ordered-map coll)` | Sorted map optimized for String keys |
| `(oc/interval-set coll)` | Set supporting interval overlap queries |
| `(oc/interval-map coll)` | Map supporting interval overlap queries |
| `(oc/range-map)` | Non-overlapping ranges (Guava TreeRangeMap) |
| `(oc/segment-tree f identity coll)` | O(log n) range aggregate queries |
| `(oc/segment-tree-by pred f identity coll)` | Segment tree with custom ordering predicate |
| `(oc/segment-tree-with cmp f identity coll)` | Segment tree with custom Comparator |
| `(oc/priority-queue pairs)` | Priority queue from `[priority value]` pairs |
| `(oc/ordered-multiset coll)` | Sorted multiset (allows duplicates) |
| `(oc/fuzzy-set coll)` | Returns closest element to query |
| `(oc/fuzzy-map coll)` | Returns value for closest key to query |

---

## Capabilities

Operations that `sorted-set` and `sorted-map` don't provide — at any collection size.

### Positional Access & Rank

```clojure
(def s (oc/ordered-set [10 20 30 40 50]))

(nth s 2)              ;=> 30     O(log n)
(oc/rank s 30)         ;=> 2      O(log n)
(oc/median s)          ;=> 30     O(log n)
(oc/percentile s 90)   ;=> 50     O(log n)
(oc/slice s 1 4)       ;=> (20 30 40)
```

### Nearest / Floor / Ceiling

```clojure
(def s (oc/ordered-set [100 200 300 400 500]))

(oc/nearest s :<= 350)  ;=> 300  (floor)
(oc/nearest s :>= 350)  ;=> 400  (ceiling)
(oc/nearest s :< 300)   ;=> 200  (predecessor)
(oc/nearest s :> 300)   ;=> 400  (successor)
```

### Split & Subrange

```clojure
(def s (oc/ordered-set [1 2 3 4 5]))

(oc/split-key 3 s)  ;=> [#{1 2} 3 #{4 5}]    O(log n)
(oc/split-at 2 s)   ;=> [#{1 2} #{3 4 5}]     O(log n)

;; subrange returns a collection, not a seq
(oc/subrange s :>= 2 :<= 4)  ;=> #{2 3 4}
```

### Interval Queries

```
 meeting: +-------+
   lunch:       +-------+
  review:                 +-------+
         9==10==11==12==13==14==15==16==17
```

```clojure
(def schedule
  (oc/interval-map
    {[9 12] "meeting" [14 17] "review" [11 15] "lunch"}))

(schedule 11)       ;=> ("meeting" "lunch")       point query
(schedule [10 14])  ;=> ("meeting" "lunch" "review")  range query
(oc/span schedule)  ;=> [9 17]
```

### Range Maps

Non-overlapping ranges — each point maps to exactly one value. Inserting
a new range automatically carves out whatever it overlaps.

```clojure
(def tiers
  (-> (oc/range-map)
      (assoc [0 100] :bronze)
      (assoc [100 500] :silver)
      (assoc [500 5000] :gold)))

(tiers 250)                      ;=> :silver
(oc/get-entry tiers 250)         ;=> [[100 500] :silver]
```

Insert a flash-sale range — bronze and silver are automatically split:

```clojure
(oc/ranges (assoc tiers [50 200] :flash))
;; => ([[0 50]    :bronze]        ← auto-trimmed
;;     [[50 200]  :flash]         ← inserted
;;     [[200 500] :silver]        ← auto-trimmed
;;     [[500 5000] :gold])
```

### Segment Trees

```clojure
(def sales (oc/sum-tree {1 100, 2 200, 3 150, 4 300, 5 250}))

(oc/query sales 2 4)     ;=> 650    O(log n)
(oc/aggregate sales)      ;=> 1000   O(1)

;; Update and re-query
(def sales' (assoc sales 3 500))
(oc/query sales' 2 4)     ;=> 1000

;; Also: min-tree, max-tree, or any associative operation
(def peaks (oc/segment-tree max 0 {1 100, 2 200, 3 150}))
(oc/query peaks 1 3)      ;=> 200
```

### Fuzzy Lookup

```clojure
(def sizes (oc/fuzzy-set [6 7 8 9 10 11 12 13]))
(sizes 9.3)                    ;=> 9
(oc/fuzzy-nearest sizes 9.3)   ;=> [9 0.30]

(def tiers (oc/fuzzy-map {0 :bronze 500 :silver 1000 :gold}))
(tiers 480)                    ;=> :silver
```

### Set Operations

```clojure
(def s1 (oc/ordered-set (range 1000)))
(def s2 (oc/ordered-set (range 500 1500)))

(oc/union s1 s2)         ;=> #{0..1499}
(oc/intersection s1 s2)  ;=> #{500..999}
(oc/difference s1 s2)    ;=> #{0..499}
(oc/subset? s1 s2)       ;=> false
```

### Priority Queue & Multiset

```clojure
;; Priority queue (min-heap)
(def pq (oc/priority-queue [[3 :medium] [1 :urgent] [5 :low]]))
(peek pq)       ;=> [1 :urgent]
(peek (pop pq)) ;=> [3 :medium]

;; Multiset (sorted bag, allows duplicates)
(def ms (oc/ordered-multiset [3 1 4 1 5 9 2 6 5 3 5]))
(oc/multiplicity ms 5)  ;=> 3
```

### Parallel Fold

All collection types implement `CollFold` for efficient `r/fold`:

```clojure
(require '[clojure.core.reducers :as r])
(r/fold + (oc/ordered-set (range 500000)))
```
---

## Testing

```
$ lein test

Ran 454 tests containing 466,000+ assertions.
0 failures, 0 errors.
```

The test suite includes generative tests via `test.check` and equivalence
tests against `sorted-set`, `sorted-map`, and `clojure.data.avl`.

### Benchmarks

```
$ lein bench                  # Criterium, N=100K (~5 min)
$ lein bench --full           # Criterium, N=10K,100K,500K (~30 min)
$ lein bench --readme --full  # README tables only (~10 min)
$ lein bench --sizes 50000    # Custom sizes

$ lein bench-simple           # Quick iteration bench (100 to 100K)
$ lein bench-simple --full    # Full suite (100 to 1M)
$ lein bench-range-map        # Range-map vs Guava TreeRangeMap
$ lein bench-parallel         # Parallel threshold crossover analysis
```

[Criterium](https://github.com/hugoduncan/criterium) results are written to
`bench-results/<timestamp>.edn`.

---

## Inspiration

The implementation of this weight-balanced binary tree data
structure library was inspired by the following:

-  Adams (1992)
   'Implementing Sets Efficiently in a Functional Language'
   Technical Report CSTR 92-10, University of Southampton.
   <http://groups.csail.mit.edu/mac/users/adams/BB/92-10.ps>

-  Hirai and Yamamoto (2011)
   'Balancing Weight-Balanced Trees'
   Journal of Functional Programming / 21 (3):
   Pages 287-307
   <https://yoichihirai.com/bst.pdf>

-  Oleg Kiselyov
   'Towards the best collection API, A design of the overall optimal
   collection traversal interface'
   <https://okmij.org/ftp/Scheme/enumerators-callcc.html>

-  Nievergelt and Reingold (1972)
   'Binary Search Trees of Bounded Balance'
   STOC '72 Proceedings
   4th Annual ACM symposium on Theory of Computing
   Pages 137-142

-  Driscoll, Sarnak, Sleator, and Tarjan (1989)
   'Making Data Structures Persistent'
   Journal of Computer and System Sciences Volume 38 Issue 1, February 1989
   18th Annual ACM Symposium on Theory of Computing
   Pages 86-124

-  MIT Scheme weight balanced tree as reimplemented by Yoichi Hirai
   and Kazuhiko Yamamoto using the revised non-variant algorithm recommended
   integer balance parameters from (Hirai/Yamamoto 2011).

-  Wikipedia
   'Interval Tree'
   <https://en.wikipedia.org/wiki/Interval_tree>

-  Wikipedia
   'Segment Tree'
   <https://en.wikipedia.org/wiki/Segment_tree>

-  Google Guava
   'RangeMap'
   <https://guava.dev/releases/snapshot/api/docs/com/google/common/collect/RangeMap.html>

-  Wikipedia
   'Weight Balanced Tree'
   <https://en.wikipedia.org/wiki/Weight-balanced_tree>

-  Andrew Baine (2007)
   'Purely Functional Data Structures in Common Lisp'
   Google Summer of Code 2007, mentored by Rahul Jain
   <https://funds.common-lisp.dev/funds.pdf>
   <https://developers.google.com/open-source/gsoc/2007/>

- Scott L. Burson
   'Functional Set-Theoretic Collections for Common Lisp'
   <https://fset.common-lisp.dev/>

-  Adams (1993)
   'Efficient sets—a balancing act'
   Journal of Functional Programming 3(4): 553-562
   <https://www.cambridge.org/core/journals/journal-of-functional-programming/article/functional-pearls-efficient-setsa-balancing-act/0CAA1C189B4F7C15CE9B8C02D0D4B54E>

-  Blelloch, Ferizovic, and Sun (2016)
   'Just Join for Parallel Ordered Sets'
   ACM SPAA 2016
   <https://dl.acm.org/doi/10.1145/2935764.2935768>

-  Haskell containers library (Data.Set, Data.Map)
   <https://hackage.haskell.org/package/containers>

-  SLIB Weight-Balanced Trees (Aubrey Jaffer)
   <https://people.csail.mit.edu/jaffer/slib/Weight_002dBalanced-Trees.html>

-  PAM: Parallel Augmented Maps
   <https://cmuparlay.github.io/PAMWeb/>

---

## License

The use and distribution terms for this software are covered by the [Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php), which can be found in the file LICENSE.txt at the root of this distribution. By using this software in any fashion, you are agreeing to be bound by the terms of this license. You must not remove this notice, or any other, from this software.

---

*For extended examples featuring Zorp, Kevin the sentient flip-flop, and Big Toe Tony's 47 feet, see [Zorp's Sneaker Emporium](doc/zorp-example.md).*
