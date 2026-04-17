# Migrating from clojure.data.avl

This guide is for data.avl users considering ordered-collections. For the full feature and performance comparison, see [Competitive Analysis](competitive-analysis.md) and [Benchmarks](benchmarks.md).

## API Mapping

The core operations are the same, with one syntax difference: ordered-collections uses **keywords** for test operators where data.avl uses **bare symbols**.

```clojure
;; data.avl
(require '[clojure.data.avl :as avl])
(def s (avl/sorted-set 1 2 3 4 5 6 7 8 9))

(avl/nearest s >= 4)                ;=> 4
(avl/nearest s > 4)                 ;=> 5
(avl/subrange s >= 3 < 7)           ;=> #{3 4 5 6}
(avl/split-key 5 s)                 ;=> [#{1 2 3 4} 5 #{6 7 8 9}]
(avl/split-at 3 s)                  ;=> [#{1 2 3} #{4 5 6 7 8 9}]
(avl/rank-of s 5)                   ;=> 4
(nth s 2)                           ;=> 3

;; ordered-collections
(require '[ordered-collections.core :as oc])
(def s (oc/ordered-set [1 2 3 4 5 6 7 8 9]))

(oc/nearest s :>= 4)               ;=> 4       ← keyword
(oc/nearest s :> 4)                 ;=> 5       ← keyword
(oc/subrange s :>= 3 :< 7)         ;=> #{3 4 5 6}  ← keywords
(oc/split-key 5 s)                  ;=> [#{1 2 3 4} 5 #{6 7 8 9}]
(oc/split-at 3 s)                   ;=> [#{1 2 3} #{4 5 6 7 8 9}]
(oc/rank s 5)                       ;=> 4
(nth s 2)                           ;=> 3
```

### Constructor differences

```clojure
;; data.avl — varargs like clojure.core
(avl/sorted-set 3 1 4 1 5)
(avl/sorted-map 1 :a 2 :b)

;; ordered-collections — takes a collection
(oc/ordered-set [3 1 4 1 5])
(oc/ordered-map {1 :a 2 :b})
(oc/ordered-map [[1 :a] [2 :b]])
```

### Additional operations (not in data.avl)

```clojure
(oc/median s)                       ;=> 5
(oc/percentile s 90)                ;=> 9
(oc/slice s 2 5)                    ;=> (3 4 5)
(oc/union s1 s2)                    ;  parallel fork-join
(oc/intersection s1 s2)
(oc/difference s1 s2)
```

## What you gain

- **Parallel set operations** — about 7-51x faster union/intersection/difference in the current Criterium run
- **Parallel fold** — `r/fold` uses tree-based fork-join and is about 3-5x faster than data.avl on large reductions
- **Fast endpoint access** — `java.util.SortedSet.last()` is O(log n); data.avl falls back to seq traversal for `last`
- **`median`, `percentile`, `slice`** — positional operations beyond nth/rank
- **Specialized collections** — interval trees, segment trees, range maps, fuzzy lookup, priority queues, multisets
- **Primitive-specialized nodes** — `long-ordered-set`, `double-ordered-map`, etc.
```
