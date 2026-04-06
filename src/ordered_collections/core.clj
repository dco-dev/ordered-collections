(ns ordered-collections.core
  "Public API for ordered-collections."
  (:refer-clojure :exclude [split-at])
  (:require [clojure.core.reducers                         :as r]
            [ordered-collections.types.interop]
            [ordered-collections.kernel.interval             :as interval]
            [ordered-collections.kernel.node                 :as node]
            [ordered-collections.kernel.order                :as order]
            [ordered-collections.kernel.tree                 :as tree]
            [ordered-collections.types.fuzzy-map           :as fuzzy-map]
            [ordered-collections.types.fuzzy-set           :as fuzzy-set]
            [ordered-collections.types.interval-map        :refer [->IntervalMap]]
            [ordered-collections.types.interval-set        :refer [->IntervalSet]]
            [ordered-collections.types.ordered-map         :refer [->OrderedMap]
                                                                    :as omap]
            [ordered-collections.types.ordered-multiset    :as multiset]
            [ordered-collections.types.ordered-set         :refer [->OrderedSet]]
            [ordered-collections.types.priority-queue      :as pq]
            [ordered-collections.types.range-map           :as rmap]
            [ordered-collections.types.rope               :as rope]
            [ordered-collections.types.segment-tree        :as segtree]
            [ordered-collections.protocol                  :as proto]
            [ordered-collections.util                      :refer [defalias]])
  (:import  [ordered_collections.types.ordered_map OrderedMap]
            [ordered_collections.types.ordered_set OrderedSet]
            [ordered_collections.kernel.root INodeCollection IOrderedCollection IBalancedCollection]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defalias long-compare
  "Specialized java.util.Comparator for Long keys.
   Uses Long/compare directly for ~15-25% faster comparisons than default."
  order/long-compare)

(defalias double-compare
  "Specialized java.util.Comparator for Double keys.
   Uses Double/compare directly for faster numeric comparisons."
  order/double-compare)

(defalias string-compare
  "Specialized java.util.Comparator for String keys.
   Uses String.compareTo directly for faster string comparisons."
  order/string-compare)

(defalias general-compare
  "General-purpose java.util.Comparator that provides a deterministic total
   order over all values, including types that clojure.core/compare does not
   order (such as Namespace and Var).

   Use with ordered-set-with / ordered-map-with:
     (ordered-set-with general-compare (all-ns))
     (ordered-map-with general-compare [[#'clojure.core/map :map]])

   Expect roughly 20% slower lookups compared to the default comparator on
   Comparable types. Set algebra overhead is smaller and masked by parallelism
   at scale."
  order/general-compare)

(defalias compare-by
  "Given a predicate that defines a total order (e.g., <), return a java.util.Comparator.
   Example: (compare-by <) returns a comparator for ascending order."
  order/compare-by)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Algebra
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defalias union
  "Return a set that is the union of the input sets.

   For ordered-sets: Uses Adams' divide-and-conquer algorithm with parallel
   execution for large sets. In the current benchmark suite, roughly 4-20x
   faster than clojure.set/union on the tested workloads.

   Complexity: O(m log(n/m + 1)) where m <= n

   Examples:
     (union (ordered-set [1 2]) (ordered-set [2 3]))  ; #{1 2 3}
     (union s1 s2 s3)                                  ; multiple sets"
  proto/union)

(defalias intersection
  "Return a set that is the intersection of the input sets.

   For ordered-sets: Uses Adams' divide-and-conquer algorithm with parallel
   execution for large sets. In the current benchmark suite, roughly 4-17x
   faster than clojure.set/intersection on the tested workloads.

   Complexity: O(m log(n/m + 1)) where m <= n

   Examples:
     (intersection (ordered-set [1 2 3]) (ordered-set [2 3 4]))  ; #{2 3}"
  proto/intersection)

(defalias difference
  "Return a set that is s1 without elements in s2.

   For ordered-sets: Uses Adams' divide-and-conquer algorithm with parallel
   execution for large sets. In the current benchmark suite, roughly 5-24x
   faster than clojure.set/difference on the tested workloads.

   Complexity: O(m log(n/m + 1)) where m <= n

   Examples:
     (difference (ordered-set [1 2 3]) (ordered-set [2]))  ; #{1 3}"
  proto/difference)

(defalias subset?
  "True if s1 is a subset of s2 (every element of s1 is in s2).

   Examples:
     (subset? (ordered-set [1 2]) (ordered-set [1 2 3]))  ; true
     (subset? (ordered-set [1 4]) (ordered-set [1 2 3]))  ; false"
  proto/subset?)

(defalias superset?
  "True if s1 is a superset of s2 (s1 contains every element of s2).

   Examples:
     (superset? (ordered-set [1 2 3]) (ordered-set [1 2]))  ; true"
  proto/superset?)

(defalias disjoint?
  "True if s1 and s2 share no elements.
   Short-circuits on the first common element found.

   Examples:
     (disjoint? (ordered-set [1 2 3]) (ordered-set [4 5 6]))  ; true
     (disjoint? (ordered-set [1 2 3]) (ordered-set [3 4 5]))  ; false"
  proto/disjoint?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Parallel construction chunk size for batch operations.
;; Parallel fold requires the collection to implement CollFold (e.g., vectors).
;; Custom comparator functions (ordered-set-by, etc.) pass (seq coll) to force
;; sequential construction, because dynamic bindings don't propagate to r/fold
;; worker threads and the comparator would be lost.

(def ^:private +chunk-size+ 2048)

(defn- ordered-set* [compare-fn coll]
  (binding [order/*compare* compare-fn]
    (->OrderedSet
      (r/fold +chunk-size+
              (fn
                ([]      (node/leaf))
                ([n0 n1] (tree/node-set-union n0 n1))) tree/node-add coll)
      compare-fn tree/node-create-weight-balanced tree/node-create-weight-balanced {})))

(defn- ordered-set-prim*
  "Variant of ordered-set* that uses primitive node types for numeric keys."
  [compare-fn node-create coll]
  (binding [order/*compare* compare-fn
            tree/*t-join* node-create]
    (->OrderedSet
      (r/fold +chunk-size+
              (fn
                ([]      (node/leaf))
                ([n0 n1] (tree/node-set-union n0 n1)))
              (fn [n k] (tree/node-add n k k compare-fn node-create))
              coll)
      compare-fn node-create node-create {})))

(defn ordered-set
  "Create a persistent sorted set backed by a weight-balanced binary tree.

   Drop-in replacement for clojure.core/sorted-set with these enhancements:
   - O(log n) first/last via java.util.SortedSet (vs O(n) for sorted-set)
   - O(log n) nth positional access
   - Parallel r/fold (2.3x faster than sorted-set)
   - 7-9x faster set operations (union, intersection, difference)

   Elements are sorted by clojure.core/compare. For custom ordering,
   use ordered-set-by. For numeric keys, use long-ordered-set.

   Examples:
     (ordered-set)                      ; empty set
     (ordered-set [3 1 4 1 5 9])        ; #{1 3 4 5 9}
     (first (ordered-set (range 1e6)))  ; 0, in O(log n)
     (nth (ordered-set (range 100)) 50) ; 50, in O(log n)

   Memory: ~64 bytes/element (vs ~61 for sorted-set, ~6% overhead)"
  ([]
   (ordered-set* order/normal-compare nil))
  ([coll]
   (ordered-set* order/normal-compare coll)))

(defn ordered-set-by
  "Create an ordered set with custom ordering via a predicate.

   The predicate should define a total order (like < or >).

   Examples:
     (ordered-set-by > [1 2 3])  ; descending: #{3 2 1}
     (ordered-set-by #(compare (count %1) (count %2)) [\"a\" \"bb\" \"ccc\"])"
  [pred coll]
  (-> pred order/compare-by (ordered-set* (seq coll))))

(defn long-ordered-set
  "Create an ordered set optimized for Long keys.
   Uses primitive long storage and specialized Long.compare for maximum performance.
   Typically 15-25% faster than ordered-set for numeric workloads."
  ([]
   (ordered-set-prim* order/long-compare tree/node-create-weight-balanced-long nil))
  ([coll]
   (ordered-set-prim* order/long-compare tree/node-create-weight-balanced-long coll)))

(defn double-ordered-set
  "Create an ordered set optimized for Double keys.
   Uses primitive double storage and specialized Double.compare for faster comparisons."
  ([]
   (ordered-set-prim* order/double-compare tree/node-create-weight-balanced-double nil))
  ([coll]
   (ordered-set-prim* order/double-compare tree/node-create-weight-balanced-double coll)))

(defn string-ordered-set
  "Create an ordered set optimized for String keys.
   Uses String.compareTo directly for faster string comparisons."
  ([]
   (ordered-set* order/string-compare nil))
  ([coll]
   (ordered-set* order/string-compare coll)))

(defn ordered-set-with
  "Create an ordered set with a custom java.util.Comparator.
   For best performance, use a Comparator rather than a predicate.

   Examples:
     ;; Using a pre-built comparator
     (ordered-set-with long-compare [1 2 3])

     ;; Using compare-by with a predicate (slightly slower)
     (ordered-set-with (compare-by >) [1 2 3])  ; descending order"
  ([^java.util.Comparator comparator]
   (ordered-set* comparator nil))
  ([^java.util.Comparator comparator coll]
   (ordered-set* comparator coll)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ordered-map* [compare-fn coll]
  (binding [order/*compare* compare-fn]
    (->OrderedMap
      (r/fold +chunk-size+
              (fn
                ([]      (node/leaf))
                ([n0 n1] (tree/node-set-union n0 n1)))
              (fn
                ([n [k v]] (tree/node-add n k v))  ;; for seqs of pairs
                ([n k v]   (tree/node-add n k v))) ;; for maps (kvreduce)
              coll)
      compare-fn tree/node-create-weight-balanced tree/node-create-weight-balanced {})))

(defn- ordered-map-prim*
  "Variant of ordered-map* that uses primitive node types for numeric keys."
  [compare-fn node-create coll]
  (binding [order/*compare* compare-fn
            tree/*t-join* node-create]
    (->OrderedMap
      (r/fold +chunk-size+
              (fn
                ([]      (node/leaf))
                ([n0 n1] (tree/node-set-union n0 n1)))
              (fn
                ([n [k v]] (tree/node-add n k v compare-fn node-create))
                ([n k v]   (tree/node-add n k v compare-fn node-create)))
              coll)
      compare-fn node-create node-create {})))

(defn ordered-map
  "Create a persistent sorted map backed by a weight-balanced binary tree.

   Drop-in replacement for clojure.core/sorted-map with these enhancements:
   - O(log n) first/last via java.util.SortedMap (vs O(n) for sorted-map)
   - O(log n) nth positional access
   - Parallel r/fold (2.3x faster than sorted-map)
   - Fast merge-with via ordered-merge-with

   Keys are sorted by clojure.core/compare. For custom ordering,
   use ordered-map-by. For numeric keys, use long-ordered-map.

   Examples:
     (ordered-map)                          ; empty map
     (ordered-map [[3 :c] [1 :a] [2 :b]])   ; {1 :a, 2 :b, 3 :c}
     (ordered-map {3 :c, 1 :a, 2 :b})       ; {1 :a, 2 :b, 3 :c}
     (first (ordered-map (zipmap (range 1e6) (range))))  ; [0 0], in O(log n)

   Memory: ~88 bytes/entry (vs ~85 for sorted-map, ~4% overhead)"
  ([]
   (ordered-map* order/normal-compare nil))
  ([coll]
   (ordered-map* order/normal-compare coll)))

(defn ordered-map-by
  "Create an ordered map with custom key ordering via a predicate.

   The predicate should define a total order (like < or >).

   Examples:
     (ordered-map-by > [[1 :a] [2 :b]])  ; descending keys: {2 :b, 1 :a}"
  [pred coll]
  (-> pred order/compare-by (ordered-map* (seq coll))))

(defn long-ordered-map
  "Create an ordered map optimized for Long keys.
   Uses primitive long storage and specialized Long.compare for maximum performance.
   Typically 15-25% faster than ordered-map for numeric workloads."
  ([]
   (ordered-map-prim* order/long-compare tree/node-create-weight-balanced-long nil))
  ([coll]
   (ordered-map-prim* order/long-compare tree/node-create-weight-balanced-long coll)))

(defn double-ordered-map
  "Create an ordered map optimized for Double keys.
   Uses primitive double storage and specialized Double.compare for faster comparisons."
  ([]
   (ordered-map-prim* order/double-compare tree/node-create-weight-balanced-double nil))
  ([coll]
   (ordered-map-prim* order/double-compare tree/node-create-weight-balanced-double coll)))

(defn string-ordered-map
  "Create an ordered map optimized for String keys.
   Uses String.compareTo directly for faster string comparisons."
  ([]
   (ordered-map* order/string-compare nil))
  ([coll]
   (ordered-map* order/string-compare coll)))

(defn ordered-map-with
  "Create an ordered map with a custom java.util.Comparator.
   For best performance, use a Comparator rather than a predicate.

   Examples:
     ;; Using a pre-built comparator
     (ordered-map-with long-compare [[1 :a] [2 :b]])

     ;; Using compare-by with a predicate (slightly slower)
     (ordered-map-with (compare-by >) {1 :a 2 :b})  ; descending key order"
  ([^java.util.Comparator comparator]
   (ordered-map* comparator nil))
  ([^java.util.Comparator comparator coll]
   (ordered-map* comparator coll)))

(defalias assoc-new
  "Associate key with value only if key is not already present.
   Returns the new collection with the key added, or the original
   collection unchanged if the key already exists.

   Example:
     (assoc-new m :new-key :value)  ; => {... :new-key :value}
     (assoc-new m :existing-key :v) ; => m (unchanged)"
  proto/assoc-new)

(defalias ordered-merge-with
  "Merge ordered maps with a function to resolve conflicts.
   When the same key appears in multiple maps, (f key val-in-result val-in-latter) is called.
   Uses the same conservative fork-join threshold as ordered-set algebra for
   large compatible ordered-maps.

   Examples:
     (ordered-merge-with (fn [k a b] (+ a b)) m1 m2)
     (ordered-merge-with (fn [k a b] b) m1 m2 m3)  ; last-wins"
  omap/ordered-merge-with)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn interval-map
  "Create an interval map from [interval value] entries.
   Intervals are [lo hi] pairs. Supports O(log n + k) overlap queries.

   Query by invoking as a function:
     (imap point)       — entries whose interval contains point
     (imap [lo hi])     — entries overlapping the range

   Examples:
     (interval-map [[[1 5] :a] [[3 8] :b]])
     (def imap (interval-map [[[0 10] \"x\"] [[5 20] \"y\"]]))
     (imap 7)         ; => [[[0 10] \"x\"] [[5 20] \"y\"]]
     (imap [11 15])   ; => [[[5 20] \"y\"]]"
  ([]
   (interval-map nil))
  ([coll]
   (let [cmp   order/normal-compare
         alloc tree/node-create-weight-balanced-interval]
     (->IntervalMap
       (binding [tree/*t-join*   alloc
                 order/*compare* cmp]
         (reduce (fn [n [k v]] (tree/node-add n (interval/ordered-pair k) v cmp alloc))
                 (node/leaf)
                 coll))
       cmp alloc nil {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn interval-set
  "Create an interval set from intervals or points.
   Intervals are [lo hi] pairs; bare values become [x x] point intervals.
   Supports O(log n + k) overlap queries.

   Query by invoking as a function:
     (iset point)       — intervals containing point
     (iset [lo hi])     — intervals overlapping the range

   Examples:
     (interval-set [[1 3] [2 4] [5 9]])
     (def iset (interval-set [[0 10] [5 20]]))
     (iset 7)         ; => [[0 10] [5 20]]
     (iset [11 15])   ; => [[5 20]]"
  ([]
   (interval-set nil))
  ([coll]
   (let [cmp   order/normal-compare
         alloc tree/node-create-weight-balanced-interval]
     (->IntervalSet
       (binding [tree/*t-join*   alloc
                 order/*compare* cmp]
         (reduce (fn [n k] (tree/node-add n (interval/ordered-pair k) (interval/ordered-pair k) cmp alloc))
                 (node/leaf)
                 coll))
       cmp alloc nil {}))))

(defalias overlapping
  "Return all intervals overlapping the given point or interval.
   Works with interval-set and interval-map.

   For interval-set: returns seq of intervals
   For interval-map: returns seq of [interval value] entries

   Example:
     (overlapping iset 5)           ; intervals containing point 5
     (overlapping iset [3 7])       ; intervals overlapping range [3,7]
     (overlapping imap 5)           ; entries for intervals containing 5"
  proto/overlapping)

(defalias span
  "Return [min max] covering all elements, or nil if empty.
   Works with interval-set, interval-map, and range-map.

   Examples:
     (span (interval-set [[1 5] [3 8] [10 15]]))          ; => [1 15]
     (span (range-map [[[100 200] :a] [[500 600] :b]]))   ; => [100 600]"
  proto/span)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn priority-queue
  "Create a persistent priority queue from [priority value] pairs.

  Supports O(log n) push/peek/pop operations, plus parallel fold.
  Priorities are ordered by clojure.core/compare (natural ordering).
  For custom ordering, use priority-queue-by or priority-queue-with.

  Examples:
    (priority-queue)
    (priority-queue [[1 :urgent] [5 :low] [3 :medium]])
    (priority-queue [[\"beta\" :b] [\"alpha\" :a]])"
  ([] (pq/priority-queue))
  ([pairs] (pq/priority-queue pairs)))

(defn priority-queue-by
  "Create a priority queue with custom ordering via a predicate.

  Examples:
    (priority-queue-by > [[1 :a] [3 :c] [2 :b]])  ; max-heap
    (priority-queue-by > [])                         ; empty max-heap"
  [pred pairs]
  (pq/priority-queue-by pred pairs))

(defn priority-queue-with
  "Create a priority queue with a custom java.util.Comparator for priorities.

  Examples:
    (priority-queue-with long-compare [[1 :a] [2 :b]])
    (priority-queue-with string-compare)"
  ([^java.util.Comparator comparator]
   (pq/priority-queue-with comparator))
  ([^java.util.Comparator comparator pairs]
   (pq/priority-queue-with comparator pairs)))

(defalias push
  "Add an element to a priority queue with the given priority.
  (push pq priority value) => new-pq

  Example:
    (push pq 1 :urgent)"
  pq/push)

(defalias push-all
  "Add multiple [priority value] pairs to a priority queue.
  (push-all pq [[p1 v1] [p2 v2]]) => new-pq"
  pq/push-all)

(defalias peek-min
  "Return [priority value] of the first element in queue order.
  (peek-min pq) => [priority value] or nil"
  pq/peek-min)

(defalias peek-min-val
  "Return just the value (not priority) of the first element in queue order.
  (peek-min-val pq) => value or nil

  Note: (peek-min pq) returns [priority value]."
  pq/peek-min-val)

(defalias pop-min
  "Remove the first element in queue order.
  Returns the queue unchanged if empty.
  (pop-min pq) => new-pq"
  pq/pop-min)

(defalias peek-max
  "Return [priority value] of the last element in queue order.
  (peek-max pq) => [priority value] or nil"
  pq/peek-max)

(defalias peek-max-val
  "Return just the value of the last element in queue order.
  (peek-max-val pq) => value or nil"
  pq/peek-max-val)

(defalias pop-max
  "Remove the last element in queue order.
  Returns the queue unchanged if empty.
  (pop-max pq) => new-pq"
  pq/pop-max)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Multiset
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ordered-multiset
  "Create an ordered multiset (sorted bag) from a collection.
  Unlike ordered-set, allows duplicate elements.

  Supports O(log n) add/remove, nth access, and parallel fold.

  Examples:
    (ordered-multiset)                                ; empty multiset
    (ordered-multiset [3 1 4 1 5 9 2 6 5 3 5])
    ;; => #OrderedMultiset[1 1 2 3 3 4 5 5 5 6 9]"
  ([] (multiset/ordered-multiset))
  ([coll]
   (multiset/ordered-multiset coll)))

(defn ordered-multiset-by
  "Create an ordered multiset with custom ordering via a predicate.

  Example:
    (ordered-multiset-by > [3 1 4 1 5])"
  [pred coll]
  (multiset/ordered-multiset-by pred coll))

(defn ordered-multiset-with
  "Create an ordered multiset with a custom java.util.Comparator.

  Example:
    (ordered-multiset-with long-compare [3 1 4 1 5])"
  ([^java.util.Comparator comparator]
   (multiset/ordered-multiset-with comparator))
  ([^java.util.Comparator comparator coll]
   (multiset/ordered-multiset-with comparator coll)))

(defalias multiplicity
  "Return the number of occurrences of x in a multiset.
  (multiplicity ms x) => count"
  multiset/multiplicity)

(defalias disj-one
  "Remove one occurrence of x from a multiset.
  (disj-one ms x) => new-ms"
  multiset/disj-one)

(defalias disj-all
  "Remove all occurrences of x from a multiset.
  (disj-all ms x) => new-ms"
  multiset/disj-all)

(defalias distinct-elements
  "Return a lazy seq of distinct elements in sorted order.
  (distinct-elements ms) => seq"
  multiset/distinct-elements)

(defalias element-frequencies
  "Return a map of {element -> count} for all elements.
  (element-frequencies ms) => map"
  multiset/element-frequencies)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fuzzy-set
  "Create a fuzzy set that returns the closest element to a query.

   When looking up a value, returns the element in the set that is closest
   to the query. For numeric keys, distance is |query - element|.

   Options:
     :tiebreak - :< (prefer smaller, default) or :> (prefer larger) when equidistant
     :distance - custom distance function (fn [a b] -> number)

   Examples:
     (def fs (fuzzy-set [1 5 10 20]))
     (fs 7)   ; => 5 (closest to 7)
     (fs 15)  ; => 10 or 20 depending on tiebreak

     ;; With tiebreak
     (def fs (fuzzy-set [1 5 10 20] :tiebreak :>))
     (fs 15)  ; => 20 (prefer larger when equidistant)

     ;; With custom distance
     (def fs (fuzzy-set [\"apple\" \"banana\" \"cherry\"]
               :distance (fn [a b] (Math/abs (- (count a) (count b))))))
     (fs \"pear\")  ; => closest by string length"
  [coll & {:keys [tiebreak distance] :or {tiebreak :< distance fuzzy-set/numeric-distance}}]
  (binding [order/*compare* order/normal-compare]
    (fuzzy-set/->FuzzySet
      (r/fold +chunk-size+
              (fn
                ([]      (node/leaf))
                ([n0 n1] (tree/node-set-union n0 n1)))
              (fn [n k] (tree/node-add n k k))
              coll)
      order/normal-compare
      distance
      tiebreak
      {})))

(defn fuzzy-set-by
  "Create a fuzzy set with a custom comparator.

   Example:
     (fuzzy-set-by > [1 5 10 20])  ; reverse order"
  [comparator coll & {:keys [tiebreak distance] :or {tiebreak :< distance fuzzy-set/numeric-distance}}]
  (let [cmp (order/compare-by comparator)]
    (binding [order/*compare* cmp]
      (fuzzy-set/->FuzzySet
        (r/fold +chunk-size+
                (fn
                  ([]      (node/leaf))
                  ([n0 n1] (tree/node-set-union n0 n1)))
                (fn [n k] (tree/node-add n k k))
                coll)
        cmp
        distance
        tiebreak
        {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fuzzy-map
  "Create a fuzzy map that returns the value for the closest key.

   When looking up a key, returns the value for the key in the map that is
   closest to the query. For numeric keys, distance is |query - key|.

   Options:
     :tiebreak - :< (prefer smaller, default) or :> (prefer larger) when equidistant
     :distance - custom distance function (fn [a b] -> number)

   Examples:
     (def fm (fuzzy-map {0 :zero 10 :ten 100 :hundred}))
     (fm 7)   ; => :ten (closest key to 7 is 10)
     (fm 42)  ; => :ten (closest key to 42 is 10 or 100)

     ;; With tiebreak
     (def fm (fuzzy-map {0 :zero 10 :ten 100 :hundred} :tiebreak :>))
     (fm 55)  ; => :hundred (prefer larger when equidistant)

   The collection should be a map or sequence of [key value] pairs."
  [coll & {:keys [tiebreak distance] :or {tiebreak :< distance fuzzy-set/numeric-distance}}]
  (binding [order/*compare* order/normal-compare]
    (fuzzy-map/->FuzzyMap
      (r/fold +chunk-size+
              (fn
                ([]      (node/leaf))
                ([n0 n1] (tree/node-set-union n0 n1)))
              (fn
                ([n [k v]] (tree/node-add n k v))  ;; for seqs of pairs
                ([n k v]   (tree/node-add n k v))) ;; for maps (kvreduce)
              coll)
      order/normal-compare
      distance
      tiebreak
      {})))

(defn fuzzy-map-by
  "Create a fuzzy map with a custom comparator.

   Example:
     (fuzzy-map-by > {1 :a 5 :b 10 :c})  ; reverse key order"
  [comparator coll & {:keys [tiebreak distance] :or {tiebreak :< distance fuzzy-set/numeric-distance}}]
  (let [cmp (order/compare-by comparator)]
    (binding [order/*compare* cmp]
      (fuzzy-map/->FuzzyMap
        (r/fold +chunk-size+
                (fn
                  ([]      (node/leaf))
                  ([n0 n1] (tree/node-set-union n0 n1)))
                (fn
                  ([n [k v]] (tree/node-add n k v))  ;; for seqs of pairs
                  ([n k v]   (tree/node-add n k v))) ;; for maps (kvreduce)
                coll)
        cmp
        distance
        tiebreak
        {}))))

;; Re-export fuzzy-specific functions via protocol
(defalias fuzzy-nearest
  "Find the nearest element/entry and its distance.
   For fuzzy-set: (fuzzy-nearest fs query) => [element distance]
   For fuzzy-map: (fuzzy-nearest fm query) => [key value distance]"
  proto/nearest-with-distance)

(defalias fuzzy-exact-contains?
  "Check if the fuzzy collection contains exactly the given element/key.
   Unlike regular lookup, this does not do fuzzy matching."
  proto/exact-contains?)

(defalias fuzzy-exact-get
  "Get the value for exactly the given key (no fuzzy matching).
   Only for fuzzy-map."
  proto/exact-get)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rank Operations (work on ordered-set, ordered-map, etc.)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rank
  "Return the 0-based index of element x, or nil if not present. O(log n).
   Works on any collection implementing PRanked (ordered-set, ordered-map, etc.)."
  [coll x]
  (let [r (proto/rank-of coll x)]
    (when-not (neg? r) r)))

(defalias slice
  "Return elements from index start (inclusive) to end (exclusive). O(log n + k).
   Works on any collection implementing PRanked."
  proto/slice)

(defalias median
  "Return the median element. O(log n).
   Works on any collection implementing PRanked."
  proto/median)

(defalias percentile
  "Return element at given percentile (0-100). O(log n).
   Works on any collection implementing PRanked."
  proto/percentile)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defalias range-map
  "Create a map from non-overlapping ranges to values.

   Unlike interval-map, ranges never overlap. Inserting a range removes
   any overlapping portions of existing ranges.

   Ranges are half-open: [lo, hi) includes lo but excludes hi.

   Example:
     (def rm (range-map {[0 10] :a [20 30] :b}))
     (rm 5)            ; => :a
     (rm 15)           ; => nil (gap)
     (assoc rm [5 25] :c)  ; splits existing ranges"
  rmap/range-map)

(defalias ranges
  "Return seq of [range value] pairs from a range-map."
  proto/ranges)

(defalias gaps
  "Return a seq of [lo hi) ranges that have no mapping in a range-map."
  proto/gaps)

(defalias assoc-coalescing
  "Insert range with coalescing. Adjacent ranges with the same value
   are automatically merged. Equivalent to Guava's putCoalescing.

   Use this instead of assoc when you want adjacent same-value ranges
   to be merged into a single range.

   Example:
     (-> (range-map)
         (assoc-coalescing [0 100] :a)
         (assoc-coalescing [100 200] :a))
     ;; => single range [0 200) :a"
  proto/assoc-coalescing)

(defalias get-entry
  "Return [range value] for the range containing point x, or nil.
   Equivalent to Guava's getEntry(K).

   Example:
     (get-entry rm 50) ;; => [[0 100] :a]"
  proto/get-entry)

(defalias range-remove
  "Remove all mappings in the given range [lo hi).
   Any overlapping ranges are trimmed; ranges fully contained are removed.
   Equivalent to Guava's remove(Range).

   Example:
     (range-remove rm [25 75])
     ;; [0 100]:a becomes [0 25):a and [75 100):a"
  proto/range-remove)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Segment Tree
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defalias segment-tree
  "Create a segment tree for O(log n) range aggregate queries.

   Arguments:
     op       - associative operation (+, min, max, etc.)
     identity - identity element (0 for +, Long/MAX_VALUE for min)
     coll     - map or seq of [key value] pairs

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30, 3 40}))
     (query st 1 3)  ; => 90 (sum of indices 1,2,3)"
  segtree/segment-tree)

(defalias segment-tree-with
  "Create a segment tree with a custom java.util.Comparator."
  segtree/segment-tree-with)

(defalias segment-tree-by
  "Create a segment tree with a custom ordering predicate."
  segtree/segment-tree-by)

(defalias sum-tree
  "Create a segment tree for range sums."
  segtree/sum-tree)

(defalias min-tree
  "Create a segment tree for range minimum queries."
  segtree/min-tree)

(defalias max-tree
  "Create a segment tree for range maximum queries."
  segtree/max-tree)

(defalias query
  "Query the aggregate over key range [lo, hi] inclusive. O(log n).

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30, 3 40}))
     (query st 0 3)  ; => 100
     (query st 1 2)  ; => 50"
  proto/aggregate-range)

(defalias aggregate
  "Return the aggregate over the entire segment tree. O(1)."
  proto/aggregate)

(defalias update-val
  "Update the value at index k. O(log n).

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30}))
     (def st' (update-val st 1 100))
     (query st' 0 2)  ; => 140"
  proto/update-val)

(defalias update-fn
  "Update the value at index k by applying f to the current value. O(log n).

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30}))
     (def st' (update-fn st 1 #(* % 2)))
     (query st' 0 2)  ; => 80"
  proto/update-fn)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Split and Range Operations (data.avl compatible)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn split-key
  "Split collection at key k, returning [left entry right].

   - left:  collection of elements less than k
   - entry: the element/entry at k, or nil if not present
            (for sets: the key itself; for maps: [key value])
   - right: collection of elements greater than k

   Complexity: O(log n)

   Compatible with clojure.data.avl/split-key.

   Example:
     (split-key 3 (ordered-set [1 2 3 4 5]))
     ;=> [#{1 2} 3 #{4 5}]

     (split-key 2 (ordered-map [[1 :a] [2 :b] [3 :c]]))
     ;=> [{1 :a} [2 :b] {3 :c}]"
  [k coll]
  (proto/split-key coll k))

(defn split-at
  "Split collection at index i, returning [left right].

   - left:  collection of the first i elements (indices 0 to i-1)
   - right: collection of remaining elements (indices i to n-1)

   Complexity: O(log n)

   Compatible with clojure.core/split-at and clojure.data.avl/split-at.

   Example:
     (split-at 2 (ordered-set [1 2 3 4 5]))
     ;=> [#{1 2} #{3 4 5}]"
  [i coll]
  (proto/split-at coll i))

(defn subrange
  "Return a subcollection comprising elements in the given range.

   Arguments:
     (subrange coll test key)           - elements satisfying test relative to key
     (subrange coll start-test start-key end-test end-key)

   Tests: :< :<= :> :>=

   Complexity: O(log n) to construct the subrange

   Example:
     (subrange (ordered-set (range 10)) :>= 3 :< 7)
     ;=> #{3 4 5 6}

     (subrange (ordered-set (range 10)) :> 5)
     ;=> #{6 7 8 9}"
  ([coll test key]
   (proto/subrange coll test key))
  ([coll start-test start-key end-test end-key]
   (-> coll
       (proto/subrange start-test start-key)
       (proto/subrange end-test end-key))))

(defn nearest
  "Find the nearest element to key k satisfying the given test.

   Tests:
     :<  - greatest element less than k (predecessor)
     :<= - greatest element less than or equal to k (floor)
     :>= - least element greater than or equal to k (ceiling)
     :>  - least element greater than k (successor)

   Returns the element (for sets) or [key value] (for maps), or nil if none.

   Complexity: O(log n)

   Example:
     (nearest (ordered-set [1 3 5 7 9]) :< 6)
     ;=> 5

     (nearest (ordered-set [1 3 5 7 9]) :>= 6)
     ;=> 7

     (nearest (ordered-map [[1 :a] [3 :b] [5 :c]]) :<= 4)
     ;=> [3 :b]"
  [coll test k]
  (proto/nearest coll test k))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defalias rope
  "Create a persistent rope from a collection.

   A rope is a chunked, tree-backed persistent sequence optimized for
   structural editing: O(log n) concat, split, splice, insert, and remove.
   Use a rope when you need to repeatedly edit the middle of a large
   sequence. Use a vector for random-access-heavy workloads.

   Supports nth, get, assoc, conj, peek, pop, seq, rseq, reduce,
   r/fold, compare, java.util.List, and all standard Clojure sequence
   operations.

   Examples:
     (rope [1 2 3 4 5])
     (rope (range 100000))
     (nth (rope (range 1000)) 500)  ;=> 500"
  rope/rope)

(defalias rope-concat
  "Concatenate two ropes (or rope-coercible collections). O(log n).

   Examples:
     (rope-concat (rope [1 2 3]) (rope [4 5 6]))
     ;=> #ordered/rope [1 2 3 4 5 6]"
  proto/rope-concat)

(defalias rope-concat-all
  "Concatenate multiple ropes via bulk chunk collection. O(total chunks).

   Examples:
     (rope-concat-all (rope [1 2]) (rope [3 4]) (rope [5 6]))
     ;=> #ordered/rope [1 2 3 4 5 6]"
  rope/rope-concat-all)

(defalias rope-split
  "Split a rope at element index i, returning [left right]. O(log n).

   Examples:
     (rope-split (rope (range 10)) 4)
     ;=> [#ordered/rope [0 1 2 3] #ordered/rope [4 5 6 7 8 9]]"
  proto/rope-split)

(defalias rope-sub
  "Extract a subrange [start, end) as a Rope. O(log n).
   The result shares structure with the original.

   Examples:
     (rope-sub (rope (range 100)) 20 30)
     ;=> #ordered/rope [20 21 22 23 24 25 26 27 28 29]"
  proto/rope-sub)

(defalias rope-insert
  "Insert elements at index i. O(log n).

   Examples:
     (rope-insert (rope [0 1 2 3]) 2 [:a :b])
     ;=> #ordered/rope [0 1 :a :b 2 3]"
  proto/rope-insert)

(defalias rope-remove
  "Remove elements in range [start, end). O(log n).

   Examples:
     (rope-remove (rope (range 10)) 3 7)
     ;=> #ordered/rope [0 1 2 7 8 9]"
  proto/rope-remove)

(defalias rope-splice
  "Replace elements in range [start, end) with new content. O(log n).

   Examples:
     (rope-splice (rope (range 10)) 2 5 [:x :y])
     ;=> #ordered/rope [0 1 :x :y 5 6 7 8 9]"
  proto/rope-splice)

(defalias rope-chunks
  "Return a seq of the rope's internal chunk vectors."
  proto/rope-chunks)

(defalias rope-chunks-reverse
  "Return a reverse seq of the rope's internal chunk vectors."
  rope/rope-chunks-reverse)

(defalias rope-chunk-count
  "Return the number of chunks in the rope."
  rope/rope-chunk-count)

(defalias rope-str
  "Efficiently convert a rope of characters/strings to a String via
  StringBuilder. Much faster than (apply str r) for large ropes.

   Examples:
     (rope-str (rope (seq \"hello world\")))
     ;=> \"hello world\""
  proto/rope-str)

