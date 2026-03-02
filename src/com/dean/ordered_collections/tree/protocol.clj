(ns com.dean.ordered-collections.tree.protocol
  (:refer-clojure :exclude [split-at subrange]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PExtensibleSet
  "Protocol for set algebra operations.

   Provides the standard set-theoretic operations: union, intersection,
   difference, and subset/superset predicates. Operations return sets
   preserving the type of the receiver."
  (intersection [this that]
    "Return the set of elements present in both this and that.")
  (union [this that]
    "Return the set of elements present in either this or that.")
  (difference [this that]
    "Return the set of elements in this but not in that.")
  (subset? [this that]
    "Return true if every element of this is also in that.")
  (superset? [this that]
    "Return true if every element of that is also in this.")
  (disjoint? [this that]
    "Return true if this and that share no elements."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exclusive Association Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PExclusiveAssoc
  "Protocol for exclusive association (insert only, no update)."
  (assoc-new [m k v]
    "Associate k with v only if k is not already present.
    Returns the new collection with the key added, or the original
    collection unchanged if the key already exists."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ranked Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PRanked
  "Protocol for collections supporting rank-based operations."
  (rank-of [coll x]
    "Return the 0-based index of element x in sorted order, or -1 if not present.")
  (slice [coll start end]
    "Return a seq of elements from index start (inclusive) to end (exclusive).")
  (median [coll]
    "Return the median element. For even-sized collections, returns the lower median.")
  (percentile [coll pct]
    "Return the element at the given percentile (0-100)."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Nearest Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PNearest
  "Protocol for finding nearest elements and ranges relative to a key."
  (nearest [coll test k]
    "Find the nearest element satisfying test relative to k.
    Tests: :< (predecessor), :<= (floor), :>= (ceiling), :> (successor).
    Returns element (for sets) or [key value] (for maps), or nil if none.")
  (subrange [coll test k]
    "Return subcollection of elements satisfying test relative to k.
    Tests: :< :<= :>= :>"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Splittable Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PSplittable
  "Protocol for collections supporting efficient split operations.
  Compatible with clojure.data.avl split operations."
  (split-key [coll k]
    "Split collection at key k, returning [left entry right].
    - left: collection of elements less than k
    - entry: the element/entry at k, or nil if not present
    - right: collection of elements greater than k")
  (split-at [coll i]
    "Split collection at index i, returning [left right].
    - left: collection of the first i elements (indices 0 to i-1)
    - right: collection of remaining elements (indices i to n-1)"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Aggregate Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PRangeAggregate
  "Protocol for range aggregate queries over associative operations."
  (aggregate-range [coll lo hi]
    "Return aggregate over index range [lo, hi] inclusive.")
  (aggregate [coll]
    "Return aggregate over entire collection.")
  (update-val [coll k v]
    "Update value at index k, returning new collection.")
  (update-fn [coll k f]
    "Update value at index k by applying f, returning new collection."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Collection Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PIntervalCollection
  "Protocol for interval-based collections supporting overlap queries."
  (overlapping [coll interval] "Return all intervals overlapping the given point or interval.")
  (span [coll] "Return [min-start max-end] covering all intervals, or nil if empty."))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multiset Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PMultiset
  "Protocol for multiset (bag) operations."
  (multiplicity       [ms k]   "Return count of element k.")
  (disj-one           [ms k]   "Remove one occurrence of k.")
  (disj-all           [ms k]   "Remove all occurrences of k.")
  (distinct-elements  [ms]     "Return set of distinct elements.")
  (element-frequencies [ms]    "Return map of element -> count."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PPriorityQueue
  "Protocol for priority queue operations.
  Elements are [priority value] pairs."
  (push     [pq priority value] "Add element with given priority.")
  (push-all [pq pairs]          "Add multiple [priority value] pairs.")
  (peek-min [pq]                "Return [priority value] of min element, or nil.")
  (peek-val [pq]                "Return just the value of min element, or nil.")
  (pop-min  [pq]                "Remove min element. Returns queue unchanged if empty.")
  (peek-max [pq]                "Return [priority value] of max element, or nil.")
  (peek-max-val [pq]            "Return just the value of max element, or nil.")
  (pop-max  [pq]                "Remove max element. Returns queue unchanged if empty."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Collection Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PFuzzy
  "Protocol for fuzzy/nearest-neighbor collections."
  (nearest-with-distance [coll query]
    "Find nearest element/entry with distance.
    Returns [element distance] for sets, [key value distance] for maps.")
  (exact-contains? [coll k]
    "Check if collection contains exactly k (no fuzzy matching).")
  (exact-get [coll k] [coll k not-found]
    "Get value for exactly k (maps only, no fuzzy matching)."))
