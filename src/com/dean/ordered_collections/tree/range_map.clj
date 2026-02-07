(ns com.dean.ordered-collections.tree.range-map
  "A map from non-overlapping ranges to values.

   Unlike IntervalMap (which allows overlapping intervals), RangeMap enforces
   that ranges never overlap. When inserting a new range, any overlapping
   portions of existing ranges are removed.

   EXAMPLE:
     (def rm (range-map {[0 10] :a [20 30] :b}))
     (rm 5)               ; => :a
     (rm 15)              ; => nil (gap)
     (rm 25)              ; => :b

     ;; Insert overlapping range - splits existing
     (assoc rm [5 25] :c)
     ; => {[0 5) :a, [5 25) :c, [25 30) :b}

   RANGE SEMANTICS:
   Ranges are half-open intervals [lo, hi) by default:
   - [0 10] contains 0, 1, 2, ..., 9 but NOT 10

   USE CASES:
   - IP address range mappings
   - Time-based scheduling (non-overlapping slots)
   - Memory region allocation
   - Version ranges in dependency resolution"
  (:require [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang ILookup Associative IPersistentCollection Seqable
             Counted IFn IMeta IObj MapEntry]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- range-lo [[lo _]] lo)
(defn- range-hi [[_ hi]] hi)

(defn- ranges-overlap?
  "True if [a-lo, a-hi) and [b-lo, b-hi) overlap."
  [[a-lo a-hi] [b-lo b-hi]]
  (and (< a-lo b-hi) (< b-lo a-hi)))

(defn- range-contains?
  "True if point x is in [lo, hi)."
  [[lo hi] x]
  (and (<= lo x) (< x hi)))

(defn- range-compare
  "Compare ranges by their lower bound."
  [a b]
  (compare (range-lo a) (range-lo b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RangeMap Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ->RangeMap range-map-assoc)

(deftype RangeMap [root cmp _meta]

  IMeta
  (meta [_] _meta)

  IObj
  (withMeta [_ m] (RangeMap. root cmp m))

  Counted
  (count [_] (tree/node-size root))

  Seqable
  (seq [_]
    (when-not (node/leaf? root)
      (binding [order/*compare* cmp]
        (map node/-kv (tree/node-seq root)))))

  ILookup
  (valAt [this x] (.valAt this x nil))
  (valAt [_ x not-found]
    (binding [order/*compare* cmp]
      (loop [n root]
        (if (node/leaf? n)
          not-found
          (let [rng (node/-k n)
                lo  (range-lo rng)
                hi  (range-hi rng)]
            (cond
              (< x lo)  (recur (node/-l n))
              (>= x hi) (recur (node/-r n))
              :else     (node/-v n)))))))

  IFn
  (invoke [this x] (.valAt this x nil))
  (invoke [this x not-found] (.valAt this x not-found))

  Associative
  (containsKey [this x]
    (not= ::not-found (.valAt this x ::not-found)))
  (entryAt [this x]
    (let [v (.valAt this x ::not-found)]
      (when-not (= v ::not-found)
        (MapEntry. x v))))
  (assoc [this rng v]
    (range-map-assoc this rng v))

  IPersistentCollection
  (empty [_]
    (RangeMap. (node/leaf) cmp {}))
  (cons [this x]
    (if (instance? MapEntry x)
      (.assoc this (key x) (val x))
      (.assoc this (first x) (second x))))
  (equiv [this that]
    (and (instance? RangeMap that)
         (= (seq this) (seq that)))))

(defn- collect-overlapping
  "Collect all ranges that overlap [lo, hi)."
  [root lo hi]
  (let [result (volatile! [])]
    (tree/node-iter root
      (fn [n]
        (let [[rl rh] (node/-k n)]
          (when (and (< rl hi) (< lo rh))
            (vswap! result conj [(node/-k n) (node/-v n)])))))
    @result))

(defn- range-map-assoc
  "Insert range [lo hi) -> val, removing any overlapping portions."
  [^RangeMap rm rng v]
  (let [[lo hi] rng
        cmp     (.-cmp rm)]
    (when (>= lo hi)
      (throw (ex-info "Invalid range: lo must be < hi" {:range rng})))
    (binding [order/*compare* cmp]
      (let [overlapping (collect-overlapping (.-root rm) lo hi)
            ;; Remove all overlapping ranges
            root' (reduce (fn [n [r _]] (tree/node-remove n r))
                          (.-root rm) overlapping)
            ;; Add back trimmed portions
            root'' (reduce
                    (fn [n [[rl rh] rv]]
                      (cond-> n
                        (< rl lo) (tree/node-add [rl lo] rv)
                        (> rh hi) (tree/node-add [hi rh] rv)))
                    root' overlapping)
            ;; Add the new range
            root''' (tree/node-add root'' [lo hi] v)]
        (RangeMap. root''' cmp (.-_meta rm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructor & API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn range-map
  "Create a range map from a collection of [range value] pairs.

   Ranges are [lo hi) (half-open, hi exclusive).

   Example:
     (range-map {[0 10] :a [20 30] :b})
     (range-map [[[0 10] :a] [[20 30] :b]])"
  ([]
   (RangeMap. (node/leaf) range-compare {}))
  ([coll]
   (binding [order/*compare* range-compare]
     (reduce
       (fn [rm [rng v]] (assoc rm rng v))
       (RangeMap. (node/leaf) range-compare {})
       coll))))

(defn ranges
  "Return a seq of all [range value] pairs."
  [^RangeMap rm]
  (seq rm))

(defn spanning-range
  "Return [lo hi] spanning all ranges, or nil if empty."
  [^RangeMap rm]
  (when-not (node/leaf? (.-root rm))
    (binding [order/*compare* (.-cmp rm)]
      (let [least    (tree/node-least (.-root rm))
            greatest (tree/node-greatest (.-root rm))]
        [(range-lo (node/-k least))
         (range-hi (node/-k greatest))]))))

(defn gaps
  "Return a seq of [lo hi) ranges that have no mapping."
  [^RangeMap rm]
  (when-let [s (seq rm)]
    (let [pairs (partition 2 1 s)]
      (for [[[_ [_ h1]] [[l2 _] _]] pairs
            :when (< h1 l2)]
        [h1 l2]))))
