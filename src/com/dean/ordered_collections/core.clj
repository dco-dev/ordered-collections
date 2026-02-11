(ns com.dean.ordered-collections.core
  (:refer-clojure :exclude [split-at])
  (:require [clojure.core.reducers                            :as r]
            [com.dean.ordered-collections.tree.interval             :as interval]
            [com.dean.ordered-collections.tree.interval-map         :refer [->IntervalMap]]
            [com.dean.ordered-collections.tree.interval-set         :refer [->IntervalSet]]
            [com.dean.ordered-collections.tree.fuzzy-map            :as fuzzy-map]
            [com.dean.ordered-collections.tree.fuzzy-set            :as fuzzy-set]
            [com.dean.ordered-collections.tree.node                 :as node]
            [com.dean.ordered-collections.tree.order                :as order]
            [com.dean.ordered-collections.tree.ordered-multiset     :as multiset]
            [com.dean.ordered-collections.tree.priority-queue       :as pq]
            [com.dean.ordered-collections.tree.protocol             :as proto]
            [com.dean.ordered-collections.tree.ordered-map          :refer [->OrderedMap]]
            [com.dean.ordered-collections.tree.ordered-set          :refer [->OrderedSet]]
            [com.dean.ordered-collections.tree.ranked-set           :as ranked]
            [com.dean.ordered-collections.tree.range-map            :as rmap]
            [com.dean.ordered-collections.tree.segment-tree         :as segtree]
            [com.dean.ordered-collections.tree.tree                 :as tree])
  (:import  [com.dean.ordered_collections.tree.ordered_set OrderedSet]
            [com.dean.ordered_collections.tree.ordered_map OrderedMap]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def long-compare
  "Specialized java.util.Comparator for Long keys.
   Uses Long/compare directly for ~15-25% faster comparisons than default."
  order/long-compare)

(def double-compare
  "Specialized java.util.Comparator for Double keys.
   Uses Double/compare directly for faster numeric comparisons."
  order/double-compare)

(def string-compare
  "Specialized java.util.Comparator for String keys.
   Uses String.compareTo directly for faster string comparisons."
  order/string-compare)

(def compare-by
  "Given a predicate that defines a total order (e.g., <), return a java.util.Comparator.
   Example: (compare-by <) returns a comparator for ascending order."
  order/compare-by)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Algebra
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def union
  "Return a set that is the union of the input sets.

   For ordered-sets: Uses Adams' divide-and-conquer algorithm with parallel
   execution for large sets. 7-9x faster than clojure.set/union at scale.

   Complexity: O(m log(n/m + 1)) where m <= n

   Examples:
     (union (ordered-set [1 2]) (ordered-set [2 3]))  ; #{1 2 3}
     (union s1 s2 s3)                                  ; multiple sets"
  proto/union)

(def intersection
  "Return a set that is the intersection of the input sets.

   For ordered-sets: Uses Adams' divide-and-conquer algorithm with parallel
   execution for large sets. 7-9x faster than clojure.set/intersection at scale.

   Complexity: O(m log(n/m + 1)) where m <= n

   Examples:
     (intersection (ordered-set [1 2 3]) (ordered-set [2 3 4]))  ; #{2 3}"
  proto/intersection)

(def difference
  "Return a set that is s1 without elements in s2.

   For ordered-sets: Uses Adams' divide-and-conquer algorithm with parallel
   execution for large sets. 7-9x faster than clojure.set/difference at scale.

   Complexity: O(m log(n/m + 1)) where m <= n

   Examples:
     (difference (ordered-set [1 2 3]) (ordered-set [2]))  ; #{1 3}"
  proto/difference)

(def subset?
  "True if s1 is a subset of s2 (every element of s1 is in s2).

   Examples:
     (subset? (ordered-set [1 2]) (ordered-set [1 2 3]))  ; true
     (subset? (ordered-set [1 4]) (ordered-set [1 2 3]))  ; false"
  proto/subset)

(def superset?
  "True if s1 is a superset of s2 (s1 contains every element of s2).

   Examples:
     (superset? (ordered-set [1 2 3]) (ordered-set [1 2]))  ; true"
  proto/superset)

;; Keep old names for backwards compatibility
(def subset subset?)
(def superset superset?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: allow high speed construction AND custom compare-fn
;; TODO: refactor

;; NOTE: subject to change!
;; experimentally determined to be in the ballpark, given the current
;; performance characteristics upstream

(def ^:private +chunk-size+ 2048)

(defn- ordered-set* [compare-fn coll]
  (binding [order/*compare* compare-fn]
    (->OrderedSet
      (r/fold +chunk-size+
              (fn
                ([]      (node/leaf))
                ([n0 n1] (tree/node-set-union n0 n1))) tree/node-add coll)
      compare-fn nil nil {})))

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
      compare-fn nil node-create {})))

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
      compare-fn nil nil {})))

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
      compare-fn nil node-create {})))

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
   (ordered-map* order/normal-compare coll))
  ([compare-fn coll]
   (ordered-map* compare-fn coll)))

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

(defn ordered-merge-with
  "Merge ordered maps with a function to resolve conflicts.
   When the same key appears in multiple maps, (f key val-in-result val-in-latter) is called.
   Uses parallel divide-and-conquer for large maps (threshold: 10000 elements).

   Examples:
     (ordered-merge-with (fn [k a b] (+ a b)) m1 m2)
     (ordered-merge-with (fn [k a b] b) m1 m2 m3)  ; last-wins"
  [f & maps]
  (when (some identity maps)
    (let [merge-fn (fn [k v1 v2] (f k v2 v1))  ;; swap order to match clojure.core/merge-with semantics
          maps (filter identity maps)]
      (if (empty? maps)
        nil
        (reduce
          (fn [m1 m2]
            (if (and (instance? com.dean.ordered_collections.tree.ordered_map.OrderedMap m1)
                     (instance? com.dean.ordered_collections.tree.ordered_map.OrderedMap m2)
                     (.isCompatible ^com.dean.ordered_collections.tree.root.IOrderedCollection m1 m2))
              ;; Both are compatible ordered-maps: use fast tree merge
              (let [^com.dean.ordered_collections.tree.root.INodeCollection m1c m1
                    ^com.dean.ordered_collections.tree.root.INodeCollection m2c m2
                    root1 (.getRoot m1c)
                    root2 (.getRoot m2c)
                    cmp (.getCmp ^com.dean.ordered_collections.tree.root.IOrderedCollection m1)
                    use-parallel? (>= (+ (tree/node-size root1) (tree/node-size root2))
                                      tree/+parallel-threshold+)]
                (binding [order/*compare* cmp]
                  (->OrderedMap
                    (if use-parallel?
                      (tree/node-map-merge-parallel root1 root2 merge-fn)
                      (tree/node-map-merge root1 root2 merge-fn))
                    cmp nil nil {})))
              ;; Fallback: use sequential assoc
              (reduce-kv (fn [m k v]
                           (if-let [existing (get m k)]
                             (assoc m k (f k existing v))
                             (assoc m k v)))
                         m1 m2)))
          maps)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn interval-map
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn priority-queue
  "Create a persistent priority queue from a collection.
  Elements are used as their own priority.

  Supports O(log n) push/peek/pop operations, plus parallel fold.

  Options:
    :comparator - priority comparator (default: < for min-heap)

  Examples:
    (priority-queue [3 1 4 1 5])           ; min-heap
    (priority-queue [3 1 4] :comparator >) ; max-heap

  Use (peek pq) for min element, (pop pq) to remove it."
  [coll & opts]
  (apply pq/priority-queue coll opts))

(defn priority-queue-by
  "Create a priority queue with [priority value] pairs.

  Example:
    (priority-queue-by < [[3 :c] [1 :a] [2 :b]])
    (peek pq) ; => :a"
  [comparator pairs]
  (pq/priority-queue-by comparator pairs))

(def push
  "Add an element to a priority queue with given priority.
  (push pq priority value) => new-pq"
  pq/push)

(def push-all
  "Add multiple [priority value] pairs to a priority queue.
  (push-all pq [[p1 v1] [p2 v2]]) => new-pq"
  pq/push-all)

(def peek-with-priority
  "Return [priority value] of the minimum element.
  (peek-with-priority pq) => [priority value] or nil"
  pq/peek-with-priority)

(def peek-max
  "Return the maximum-priority element (value only).
  (peek-max pq) => value or nil"
  pq/peek-max)

(def pop-max
  "Remove the maximum-priority element.
  (pop-max pq) => new-pq"
  pq/pop-max)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Multiset
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ordered-multiset
  "Create an ordered multiset (sorted bag) from a collection.
  Unlike ordered-set, allows duplicate elements.

  Supports O(log n) add/remove, nth access, and parallel fold.

  Example:
    (ordered-multiset [3 1 4 1 5 9 2 6 5 3 5])
    ;; => #OrderedMultiset[1 1 2 3 3 4 5 5 5 6 9]"
  [coll]
  (multiset/ordered-multiset coll))

(defn ordered-multiset-by
  "Create an ordered multiset with a custom comparator.

  Example:
    (ordered-multiset-by > [3 1 4 1 5])
    ;; => #OrderedMultiset[5 4 3 1 1]"
  [comparator coll]
  (multiset/ordered-multiset-by comparator coll))

(def multiplicity
  "Return the number of occurrences of x in a multiset.
  (multiplicity ms x) => count"
  multiset/multiplicity)

(def disj-one
  "Remove one occurrence of x from a multiset.
  (disj-one ms x) => new-ms"
  multiset/disj-one)

(def disj-all
  "Remove all occurrences of x from a multiset.
  (disj-all ms x) => new-ms"
  multiset/disj-all)

(def distinct-elements
  "Return a lazy seq of distinct elements in sorted order.
  (distinct-elements ms) => seq"
  multiset/distinct-elements)

(def element-frequencies
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

;; Re-export fuzzy-specific functions
(def fuzzy-nearest
  "Find the nearest element/entry and its distance.
   For fuzzy-set: (fuzzy-nearest fs query) => [element distance]
   For fuzzy-map: (fuzzy-nearest fm query) => [key value distance]"
  (fn [coll query]
    (cond
      (instance? com.dean.ordered_collections.tree.fuzzy_set.FuzzySet coll)
      (fuzzy-set/nearest coll query)
      (instance? com.dean.ordered_collections.tree.fuzzy_map.FuzzyMap coll)
      (fuzzy-map/nearest coll query)
      :else (throw (ex-info "fuzzy-nearest requires a FuzzySet or FuzzyMap" {:coll coll})))))

(def fuzzy-exact-contains?
  "Check if the fuzzy collection contains exactly the given element/key.
   Unlike regular lookup, this does not do fuzzy matching."
  (fn [coll k]
    (cond
      (instance? com.dean.ordered_collections.tree.fuzzy_set.FuzzySet coll)
      (fuzzy-set/exact-contains? coll k)
      (instance? com.dean.ordered_collections.tree.fuzzy_map.FuzzyMap coll)
      (fuzzy-map/exact-contains? coll k)
      :else (throw (ex-info "fuzzy-exact-contains? requires a FuzzySet or FuzzyMap" {:coll coll})))))

(def fuzzy-exact-get
  "Get the value for exactly the given key (no fuzzy matching).
   Only for fuzzy-map."
  fuzzy-map/exact-get)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ranked Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ranked-set
  "Create a sorted set with O(log n) positional access.

   In addition to normal set operations:
   - (nth-element rs i)  -> element at index i, O(log n)
   - (rank rs x)         -> index of element x, O(log n)
   - (slice rs i j)      -> elements from i to j-1
   - (median rs)         -> median element
   - (percentile rs pct) -> element at percentile

   Example:
     (def rs (ranked-set [3 1 4 1 5 9 2 6]))
     (nth-element rs 0)  ; => 1 (smallest)
     (rank rs 5)         ; => 4"
  ranked/ranked-set)

(def ranked-set-by
  "Create a ranked set with a custom comparator."
  ranked/ranked-set-by)

(def nth-element
  "Return element at index i in a ranked set. O(log n)."
  ranked/nth-element)

(def rank
  "Return the 0-based index of element x in a ranked set. O(log n)."
  ranked/rank)

(def slice
  "Return elements from index start to end-1. O(log n + k)."
  ranked/slice)

(def median
  "Return the median element of a ranked set. O(log n)."
  ranked/median)

(def percentile
  "Return element at given percentile (0-100). O(log n)."
  ranked/percentile)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def range-map
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

(def ranges
  "Return seq of [range value] pairs from a range-map."
  rmap/ranges)

(def spanning-range
  "Return [lo hi] spanning all ranges in a range-map, or nil if empty."
  rmap/spanning-range)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Segment Tree
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def segment-tree
  "Create a segment tree for O(log n) range aggregate queries.

   Arguments:
     op       - associative operation (+, min, max, etc.)
     identity - identity element (0 for +, Long/MAX_VALUE for min)
     coll     - map or seq of [index value] pairs

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30, 3 40}))
     (query st 1 3)  ; => 90 (sum of indices 1,2,3)"
  segtree/segment-tree)

(def sum-tree
  "Create a segment tree for range sums."
  segtree/sum-tree)

(def min-tree
  "Create a segment tree for range minimum queries."
  segtree/min-tree)

(def max-tree
  "Create a segment tree for range maximum queries."
  segtree/max-tree)

(def query
  "Query aggregate over [lo, hi] inclusive. O(log n)."
  segtree/query)

(def aggregate
  "Return aggregate over entire segment tree. O(1)."
  segtree/aggregate)

(def update-val
  "Update value at index k. O(log n)."
  segtree/update-val)

(def update-fn
  "Update value at index k by applying f. O(log n)."
  segtree/update-fn)

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
     (split-key (ordered-set [1 2 3 4 5]) 3)
     ;=> [#{1 2} 3 #{4 5}]

     (split-key (ordered-map [[1 :a] [2 :b] [3 :c]]) 2)
     ;=> [{1 :a} [2 :b] {3 :c}]"
  [coll k]
  (let [root   (.getRoot ^com.dean.ordered_collections.tree.root.INodeCollection coll)
        cmp    (.getCmp ^com.dean.ordered_collections.tree.root.IOrderedCollection coll)
        stitch (.getStitch ^com.dean.ordered_collections.tree.root.IBalancedCollection coll)
        alloc  (.getAllocator ^com.dean.ordered_collections.tree.root.INodeCollection coll)]
    (binding [order/*compare* cmp]
      (let [[l present r] (tree/node-split root k)
            ;; Reconstruct collections of the same type
            make-coll (fn [node]
                        (cond
                          (instance? OrderedSet coll)
                          (->OrderedSet (or node (node/leaf)) cmp alloc stitch {})

                          (instance? OrderedMap coll)
                          (->OrderedMap (or node (node/leaf)) cmp alloc stitch {})

                          :else (throw (ex-info "split-key not supported for this collection type" {:coll coll}))))
            ;; Format entry based on collection type
            entry (when present
                    (let [[k v] present]
                      (if (instance? OrderedSet coll)
                        k
                        [k v])))]
        [(make-coll l) entry (make-coll r)]))))

(defn split-at
  "Split collection at index i, returning [left right].

   - left:  collection of the first i elements (indices 0 to i-1)
   - right: collection of remaining elements (indices i to n-1)

   Complexity: O(log n)

   Compatible with clojure.data.avl/split-at.

   Example:
     (split-at (ordered-set [1 2 3 4 5]) 2)
     ;=> [#{1 2} #{3 4 5}]"
  [coll ^long i]
  (let [root   (.getRoot ^com.dean.ordered_collections.tree.root.INodeCollection coll)
        cmp    (.getCmp ^com.dean.ordered_collections.tree.root.IOrderedCollection coll)
        stitch (.getStitch ^com.dean.ordered_collections.tree.root.IBalancedCollection coll)
        alloc  (.getAllocator ^com.dean.ordered_collections.tree.root.INodeCollection coll)
        n      (tree/node-size root)]
    (cond
      (<= i 0) [(empty coll) coll]
      (>= i n) [coll (empty coll)]
      :else
      (binding [order/*compare* cmp]
        (let [pivot-node (tree/node-nth root i)
              pivot-k    (node/-k pivot-node)
              left-root  (tree/node-split-lesser root pivot-k)
              ;; Reconstruct collections of the same type
              make-coll  (fn [node]
                           (cond
                             (instance? OrderedSet coll)
                             (->OrderedSet (or node (node/leaf)) cmp alloc stitch {})

                             (instance? OrderedMap coll)
                             (->OrderedMap (or node (node/leaf)) cmp alloc stitch {})

                             :else (throw (ex-info "split-at not supported for this collection type" {:coll coll}))))
              right-root (tree/node-split-nth root i)]
          [(make-coll left-root) (make-coll right-root)])))))

(defn subrange
  "Return a subcollection comprising elements in the given range.

   Arguments mirror clojure.core/subseq and rsubseq:
     (subrange coll test key)           - elements where (test elem key) is true
     (subrange coll start-test start-key end-test end-key)

   Tests can be: < <= >= >

   Complexity: O(log n) to construct the subrange

   Compatible with clojure.data.avl/subrange.

   Example:
     (subrange (ordered-set (range 10)) >= 3 < 7)
     ;=> #{3 4 5 6}

     (subrange (ordered-set (range 10)) > 5)
     ;=> #{6 7 8 9}"
  ([coll test key]
   (let [root   (.getRoot ^com.dean.ordered_collections.tree.root.INodeCollection coll)
         cmp    (.getCmp ^com.dean.ordered_collections.tree.root.IOrderedCollection coll)
         stitch (.getStitch ^com.dean.ordered_collections.tree.root.IBalancedCollection coll)
         alloc  (.getAllocator ^com.dean.ordered_collections.tree.root.INodeCollection coll)]
     (binding [order/*compare* cmp]
       (let [result-root (cond
                           (or (identical? test <) (identical? test <=))
                           (tree/node-split-lesser root key)
                           (or (identical? test >) (identical? test >=))
                           (tree/node-split-greater root key)
                           :else (throw (ex-info "subrange test must be <, <=, >, or >=" {:test test})))
             ;; For <= and >=, we might need to include the key itself
             result-root (cond
                           (identical? test <=)
                           (if-let [n (tree/node-find root key)]
                             (tree/node-add result-root (node/-k n) (node/-v n))
                             result-root)
                           (identical? test >=)
                           (if-let [n (tree/node-find root key)]
                             (tree/node-add result-root (node/-k n) (node/-v n))
                             result-root)
                           :else result-root)]
         (cond
           (instance? OrderedSet coll)
           (->OrderedSet result-root cmp alloc stitch {})

           (instance? OrderedMap coll)
           (->OrderedMap result-root cmp alloc stitch {})

           :else (throw (ex-info "subrange not supported for this collection type" {:coll coll})))))))
  ([coll start-test start-key end-test end-key]
   (-> coll
       (subrange start-test start-key)
       (subrange end-test end-key))))

(defn nearest
  "Find the nearest element to key k satisfying the given test.

   Tests:
     <  - greatest element less than k
     <= - greatest element less than or equal to k
     >= - least element greater than or equal to k
     >  - least element greater than k

   Returns the element (for sets) or [key value] (for maps), or nil if none.

   Complexity: O(log n)

   Compatible with clojure.data.avl/nearest.

   Example:
     (nearest (ordered-set [1 3 5 7 9]) < 6)
     ;=> 5

     (nearest (ordered-set [1 3 5 7 9]) >= 6)
     ;=> 7

     (nearest (ordered-map [[1 :a] [3 :b] [5 :c]]) <= 4)
     ;=> [3 :b]"
  [coll test k]
  (let [root (.getRoot ^com.dean.ordered_collections.tree.root.INodeCollection coll)
        ^java.util.Comparator cmp (.getCmp ^com.dean.ordered_collections.tree.root.IOrderedCollection coll)
        format-result (fn [n]
                        (if (instance? OrderedSet coll)
                          (node/-k n)
                          [(node/-k n) (node/-v n)]))]
    (binding [order/*compare* cmp]
      (cond
        ;; < : greatest less than k
        (identical? test <)
        (when-let [n (tree/node-find-nearest root k :<)]
          ;; node-find-nearest returns <= so filter exact matches
          (when (neg? (.compare cmp (node/-k n) k))
            (format-result n)))

        ;; <= : greatest less than or equal to k
        (identical? test <=)
        (if-let [exact (tree/node-find root k)]
          (format-result exact)
          (when-let [n (tree/node-find-nearest root k :<)]
            (format-result n)))

        ;; > : least greater than k
        (identical? test >)
        (when-let [n (tree/node-find-nearest root k :>)]
          ;; node-find-nearest returns >= so filter exact matches
          (when (pos? (.compare cmp (node/-k n) k))
            (format-result n)))

        ;; >= : least greater than or equal to k
        (identical? test >=)
        (if-let [exact (tree/node-find root k)]
          (format-result exact)
          (when-let [n (tree/node-find-nearest root k :>)]
            (format-result n)))

        :else (throw (ex-info "nearest test must be <, <=, >, or >=" {:test test}))))))
