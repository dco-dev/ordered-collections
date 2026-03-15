(ns com.dean.ordered-collections.types.interop
  "Protocol extensions for interoperability with standard Clojure collections.

   Extends ordered-collections protocols to:
   - PersistentHashSet
   - PersistentTreeSet
   - PersistentTreeMap

   This allows protocol functions like union, intersection, nearest, etc.
   to work with standard Clojure sorted collections."
  (:require [clojure.set :as set]
            [com.dean.ordered-collections.protocol :as proto]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PExtensibleSet - Set algebra operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-type clojure.lang.PersistentHashSet
  proto/PExtensibleSet
  (intersection [this that]
    (set/intersection this that))
  (union [this that]
    (set/union this that))
  (difference [this that]
    (set/difference this that))
  (subset? [this that]
    (set/subset? this that))
  (superset? [this that]
    (set/subset? that this))
  (disjoint? [this that]
    (empty? (set/intersection this that))))

(extend-type clojure.lang.PersistentTreeSet
  proto/PExtensibleSet
  (intersection [this that]
    (set/intersection this that))
  (union [this that]
    (set/union this that))
  (difference [this that]
    (set/difference this that))
  (subset? [this that]
    (set/subset? this that))
  (superset? [this that]
    (set/subset? that this))
  (disjoint? [this that]
    (empty? (set/intersection this that))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PNearest - Floor/ceiling/predecessor/successor operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-type clojure.lang.PersistentTreeSet
  proto/PNearest
  (nearest [this test k]
    (case test
      :<  (first (rsubseq this < k))
      :<= (first (rsubseq this <= k))
      :>  (first (subseq this > k))
      :>= (first (subseq this >= k))
      (throw (ex-info "nearest test must be :<, :<=, :>, or :>=" {:test test}))))
  (subrange [this test k]
    (into (empty this)
          (case test
            :<  (subseq this < k)
            :<= (subseq this <= k)
            :>  (subseq this > k)
            :>= (subseq this >= k)
            (throw (ex-info "subrange test must be :<, :<=, :>, or :>=" {:test test}))))))

(extend-type clojure.lang.PersistentTreeMap
  proto/PNearest
  (nearest [this test k]
    (when-let [entry (case test
                       :<  (first (rsubseq this < k))
                       :<= (first (rsubseq this <= k))
                       :>  (first (subseq this > k))
                       :>= (first (subseq this >= k))
                       (throw (ex-info "nearest test must be :<, :<=, :>, or :>=" {:test test})))]
      [(key entry) (val entry)]))
  (subrange [this test k]
    (into (empty this)
          (case test
            :<  (subseq this < k)
            :<= (subseq this <= k)
            :>  (subseq this > k)
            :>= (subseq this >= k)
            (throw (ex-info "subrange test must be :<, :<=, :>, or :>=" {:test test}))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRanked - Index-based operations (O(n) for standard sorted collections)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Note: These are O(n) implementations since PersistentTreeSet/Map don't
;; maintain subtree sizes. For performance-critical code, use ordered-set/map.

(extend-type clojure.lang.PersistentTreeSet
  proto/PRanked
  (rank-of [this x]
    (if (contains? this x)
      (count (subseq this < x))
      -1))
  (slice [this start end]
    (let [s (seq this)]
      (when s
        (->> s (drop start) (take (- end start))))))
  (median [this]
    (let [n (count this)]
      (when (pos? n)
        (nth (seq this) (quot (dec n) 2)))))
  (percentile [this pct]
    (let [n (count this)]
      (when (pos? n)
        (let [idx (min (dec n) (long (* (/ (double pct) 100.0) n)))]
          (nth (seq this) idx))))))

(extend-type clojure.lang.PersistentTreeMap
  proto/PRanked
  (rank-of [this k]
    (if (contains? this k)
      (count (subseq this < k))
      -1))
  (slice [this start end]
    (let [s (seq this)]
      (when s
        (->> s (drop start) (take (- end start))))))
  (median [this]
    (let [n (count this)]
      (when (pos? n)
        (nth (seq this) (quot (dec n) 2)))))
  (percentile [this pct]
    (let [n (count this)]
      (when (pos? n)
        (let [idx (min (dec n) (long (* (/ (double pct) 100.0) n)))]
          (nth (seq this) idx))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PSplittable - Split operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-type clojure.lang.PersistentTreeSet
  proto/PSplittable
  (split-key [this k]
    (let [left  (into (empty this) (subseq this < k))
          entry (when (contains? this k) k)
          right (into (empty this) (subseq this > k))]
      [left entry right]))
  (split-at [this i]
    (let [s (seq this)
          left  (into (empty this) (take i s))
          right (into (empty this) (drop i s))]
      [left right])))

(extend-type clojure.lang.PersistentTreeMap
  proto/PSplittable
  (split-key [this k]
    (let [left  (into (empty this) (subseq this < k))
          entry (when (contains? this k) [(find this k)])
          right (into (empty this) (subseq this > k))]
      [left (when entry [(key (first entry)) (val (first entry))]) right]))
  (split-at [this i]
    (let [s (seq this)
          left  (into (empty this) (take i s))
          right (into (empty this) (drop i s))]
      [left right])))
