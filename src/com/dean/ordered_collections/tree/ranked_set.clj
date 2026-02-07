(ns com.dean.ordered-collections.tree.ranked-set
  "A sorted set with O(log n) positional access.

   RankedSet extends OrderedSet with efficient index-based operations:
   - (nth-element rs i) -> element at index i, O(log n)
   - (rank rs x)        -> index of element x, O(log n)
   - (slice rs i j)     -> elements from index i to j-1

   EXAMPLE:
     (def rs (ranked-set [50 10 30 20 40]))
     (seq rs)             ; => (10 20 30 40 50)
     (nth-element rs 0)   ; => 10 (smallest)
     (nth-element rs 2)   ; => 30
     (rank rs 30)         ; => 2
     (slice rs 1 4)       ; => (20 30 40)

   All standard set operations (conj, disj, contains?) remain O(log n)."
  (:require [clojure.core.reducers :as r]
            [com.dean.ordered-collections.tree.node       :as node]
            [com.dean.ordered-collections.tree.order      :as order]
            [com.dean.ordered-collections.tree.tree       :as tree]
            [com.dean.ordered-collections.tree.ordered-set :refer [->OrderedSet]])
  (:import  [com.dean.ordered_collections.tree.ordered_set OrderedSet]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +chunk-size+ 2048)

(defn- build-set [compare-fn coll]
  (binding [order/*compare* compare-fn]
    (->OrderedSet
      (r/fold +chunk-size+
              (fn
                ([]      (node/leaf))
                ([n0 n1] (tree/node-set-union n0 n1))) tree/node-add coll)
      compare-fn nil nil {})))

(defn ranked-set
  "Create a ranked set from a collection.

   All OrderedSet operations plus:
   - (nth-element rs i)  -> element at index i
   - (rank rs x)         -> index of element x
   - (slice rs i j)      -> elements from i to j-1
   - (median rs)         -> median element
   - (percentile rs pct) -> element at percentile

   Example:
     (def rs (ranked-set [3 1 4 1 5 9 2 6]))
     (nth-element rs 0) ; => 1
     (rank rs 5)        ; => 4
     (slice rs 2 5)     ; => (3 4 5)"
  ([]
   (build-set order/normal-compare nil))
  ([coll]
   (build-set order/normal-compare coll)))

(defn ranked-set-by
  "Create a ranked set with a custom comparator."
  [comparator coll]
  (build-set (order/compare-by comparator) (seq coll)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ranked Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nth-element
  "Return the element at index i in the sorted set. O(log n) time.
   Throws if index is out of bounds."
  ([^OrderedSet rs ^long i]
   (binding [order/*compare* (.getCmp rs)]
     (node/-k (tree/node-nth (.getRoot rs) i))))
  ([^OrderedSet rs ^long i not-found]
   (try
     (binding [order/*compare* (.getCmp rs)]
       (node/-k (tree/node-nth (.getRoot rs) i)))
     (catch Exception _ not-found))))

(defn rank
  "Return the 0-based index of element x in the sorted set, or nil if not present.
   O(log n) time."
  [^OrderedSet rs x]
  (binding [order/*compare* (.getCmp rs)]
    (tree/node-rank (.getRoot rs) x)))

(defn slice
  "Return a lazy seq of elements from index start (inclusive) to end (exclusive).
   O(log n + k) where k is the number of elements returned."
  [^OrderedSet rs ^long start ^long end]
  (binding [order/*compare* (.getCmp rs)]
    (->> (tree/node-subseq (.getRoot rs) start (dec end))
         (map node/-k))))

(defn median
  "Return the median element. For even-sized sets, returns the lower median.
   O(log n) time."
  [^OrderedSet rs]
  (let [n (count rs)]
    (when (pos? n)
      (nth-element rs (quot (dec n) 2)))))

(defn percentile
  "Return the element at the given percentile (0-100).
   O(log n) time."
  [^OrderedSet rs ^double pct]
  (let [n (count rs)]
    (when (pos? n)
      (let [idx (min (dec n) (long (* (/ pct 100.0) n)))]
        (nth-element rs idx)))))

(defn select
  "Return the k-th smallest element (0-indexed). Alias for nth-element.
   O(log n) time."
  [^OrderedSet rs ^long k]
  (nth-element rs k))
