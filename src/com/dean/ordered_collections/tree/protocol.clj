(ns com.dean.ordered-collections.tree.protocol
  (:require [clojure.set :as set]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PExtensibleSet
  (intersection [this that])
  (union        [this that])
  (difference   [this that])
  (subset       [this that])
  (superset     [this that]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PPriorityQueue
  "Protocol for priority queue operations.
  Elements are [priority value] pairs."
  (push     [pq priority value] "Add element with given priority. O(log n).")
  (push-all [pq pairs]          "Add multiple [priority value] pairs. O(k log n).")
  (peek-val [pq]                "Return just the value of min element, or nil.")
  (peek-max [pq]                "Return [priority value] of max element, or nil.")
  (peek-max-val [pq]            "Return just the value of max element, or nil.")
  (pop-max  [pq]                "Remove max element. O(log n)."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multiset Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PMultiset
  "Protocol for multiset (bag) operations."
  (multiplicity       [ms k]   "Return count of element k. O(log n).")
  (disj-one           [ms k]   "Remove one occurrence of k. O(log n).")
  (disj-all           [ms k]   "Remove all occurrences of k. O(log n).")
  (distinct-elements  [ms]     "Return set of distinct elements.")
  (element-frequencies [ms]    "Return map of element -> count."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Collection Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PIntervalCollection
  "Protocol for interval-based collections supporting overlap queries."
  (overlapping [coll interval] "Return all intervals overlapping the given point or interval. O(log n + k)."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Map Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PRangeMap
  "Protocol for range map operations (non-overlapping ranges to values)."
  (ranges          [rm]           "Return seq of [[lo hi] value] entries.")
  (get-entry       [rm point]     "Return [[lo hi] value] containing point, or nil.")
  (assoc-coalescing [rm rng val]  "Insert range [lo hi), merging adjacent same-value ranges.")
  (range-remove    [rm rng]       "Remove all mappings in [lo, hi) range.")
  (spanning-range  [rm]           "Return [lo hi] spanning all ranges, or nil if empty.")
  (gaps            [rm]           "Return seq of [lo hi] gaps between ranges."))

(extend-type clojure.lang.PersistentHashSet
  PExtensibleSet
  (intersection [this that]
    (set/intersection this that))
  (union [this that]
    (set/union this that))
  (difference [this that]
    (set/difference this that))
  (subset [this that]
    (set/subset? this that))
  (superset [this that]
    (set/subset? that this)))

(extend-type clojure.lang.PersistentTreeSet
  PExtensibleSet
  (intersection [this that]
    (set/intersection this that))
  (union [this that]
    (set/union this that))
  (difference [this that]
    (set/difference this that))
  (subset [this that]
    (set/subset? this that))
  (superset [this that]
    (set/subset? that this)))
