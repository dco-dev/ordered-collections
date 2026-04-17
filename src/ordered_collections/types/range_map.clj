(ns ordered-collections.types.range-map
  "A map from non-overlapping ranges to values.

   Unlike IntervalMap (which allows overlapping intervals), RangeMap enforces
   that ranges never overlap. When inserting a new range, any overlapping
   portions of existing ranges are removed.

   SEMANTICS (compatible with Guava's TreeRangeMap):
   - `assoc` (put): inserts range, carving out overlaps. Does NOT coalesce.
   - `assoc-coalescing` (putCoalescing): inserts and coalesces adjacent
     same-value ranges.

   RANGE SEMANTICS:
   Ranges are half-open intervals [lo, hi) by default:
   - [0 10] contains 0, 1, 2, ..., 9 but NOT 10

   PERFORMANCE:
   - Point lookup: O(log n)
   - Insert/assoc: O(k log n) where k = number of overlapping ranges
   - Coalescing insert: O(k log n)
   - Remove: O(k log n)
   For typical use (k=1-3 overlaps), effectively O(log n)."
  (:require [clojure.core.reducers       :as r :refer [coll-fold]]
            [ordered-collections.kernel.node     :as node]
            [ordered-collections.kernel.order    :as order]
            [ordered-collections.protocol      :as proto]
            [ordered-collections.kernel.tree     :as tree])
  (:import  [clojure.lang MapEntry Murmur3]
            [ordered_collections.protocol PRangeMap PSpan]
            [ordered_collections.kernel.tree EnumFrame]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- range-lo [[lo _]] lo)
(defn- range-hi [[_ hi]] hi)

(deftype ^:private RangeComparator []
  java.io.Serializable
  java.util.Comparator
  (compare [_ a b]
    (clojure.core/compare (range-lo a) (range-lo b)))
  Object
  (equals [_ o] (instance? RangeComparator o))
  (hashCode [_] 99))

(def ^:private range-compare (->RangeComparator))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Efficient Overlap Detection - O(log n + k)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; For a query range [lo, hi), a stored range [rl, rh) overlaps iff:
;;   rl < hi AND rh > lo
;;
;; Since ranges are sorted by lower bound (rl), we can efficiently find
;; all overlapping ranges:
;; 1. Find floor(lo) - the range with largest start <= lo
;;    This might overlap if its end > lo
;; 2. Iterate forward from there while start < hi
;;
;; This is O(log n) to find the starting point + O(k) to iterate over
;; k overlapping ranges.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- find-floor-range
  "Find the range with the largest lower bound <= lo.
   Returns [range value] or nil."
  [root lo]
  (loop [n root
         best nil]
    (if (node/leaf? n)
      best
      (let [[rl _] (node/-k n)]
        (cond
          (= rl lo)  [(node/-k n) (node/-v n)]
          (< rl lo)  (recur (node/-r n) [(node/-k n) (node/-v n)])
          :else      (recur (node/-l n) best))))))

(defn- find-ceiling-range
  "Find the range with the smallest lower bound >= lo.
   Returns [range value] or nil."
  [root lo]
  (loop [n root
         best nil]
    (if (node/leaf? n)
      best
      (let [[rl _] (node/-k n)]
        (cond
          (= rl lo)  [(node/-k n) (node/-v n)]
          (> rl lo)  (recur (node/-l n) [(node/-k n) (node/-v n)])
          :else      (recur (node/-r n) best))))))

(defn- collect-overlapping
  "Collect all ranges that overlap [lo, hi). Returns vector of [range value].
   Time: O(log n + k) where k = number of overlapping ranges."
  [root lo hi]
  (if (node/leaf? root)
    []
    (let [floor (find-floor-range root lo)
          start (if (and floor (> (range-hi (first floor)) lo))
                  floor
                  (find-ceiling-range root lo))]
      (if-not start
        []
        (let [start-lo (range-lo (first start))
              ;; Navigate to start position and build enumerator
              enum (loop [n root, frames nil]
                     (if (node/leaf? n)
                       frames
                       (let [rl (range-lo (node/-k n))]
                         (cond
                           (< start-lo rl) (recur (node/-l n) (EnumFrame. n (node/-r n) frames))
                           (> start-lo rl) (recur (node/-r n) frames)
                           :else           (EnumFrame. n (node/-r n) frames)))))
              acc (transient [])]
          ;; Walk forward collecting overlapping ranges
          (loop [e enum]
            (when e
              (let [node (tree/node-enum-first e)
                    [rl rh] (node/-k node)]
                (when (< rl hi)
                  (when (> rh lo)
                    (conj! acc [(node/-k node) (node/-v node)]))
                  (recur (tree/node-enum-rest e))))))
          (persistent! acc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Adjacent Range Detection for Coalescing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- find-adjacent-left
  "Find range that ends exactly at `lo`, if any. O(log n)."
  [root lo]
  (loop [n root
         candidate nil]
    (if (node/leaf? n)
      candidate
      (let [[rl rh] (node/-k n)]
        (cond
          (= rh lo) (recur (node/-r n) [(node/-k n) (node/-v n)])
          (< lo rh) (recur (node/-l n) candidate)
          :else     (recur (node/-r n) candidate))))))

(defn- find-adjacent-right
  "Find range that starts exactly at `hi`, if any. O(log n)."
  [root hi]
  (loop [n root]
    (if (node/leaf? n)
      nil
      (let [[rl _] (node/-k n)]
        (cond
          (= rl hi) [(node/-k n) (node/-v n)]
          (< hi rl) (recur (node/-l n))
          :else     (recur (node/-r n)))))))

(declare range-map-assoc range-map-remove ->RangeMap)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RangeMap Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Fields:
;;   root  — weight-balanced tree keyed by [lo hi] ranges
;;   cmp   — fixed range-compare (by lower bound); not user-configurable
;;   _meta — metadata map
;;
;; No alloc/stitch fields — uses default SimpleNode throughout.
;; Comparator is fixed (ranges always sort by lower bound), so no
;; IOrderedCollection. Manages *compare* binding directly.
;; Assoc implements Guava TreeRangeMap carve-out semantics:
;; collect overlapping ranges, remove them, re-add trimmed portions.

(deftype RangeMap [root cmp _meta]

  java.io.Serializable

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m] (RangeMap. root cmp m))

  clojure.lang.Counted
  (count [_] (tree/node-size root))

  clojure.lang.Seqable
  (seq [_]
    (tree/node-entry-seq root (tree/node-size root)))

  clojure.lang.Reversible
  (rseq [_]
    (tree/node-entry-seq-reverse root (tree/node-size root)))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (tree/node-reduce-entries f init root))

  clojure.lang.IReduce
  (reduce [_ f]
    (tree/node-reduce-entries f root))

  clojure.core.protocols/CollReduce
  (coll-reduce [this f]
    (.reduce ^clojure.lang.IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^clojure.lang.IReduceInit this f init))

  clojure.lang.IKVReduce
  (kvreduce [_ f init]
    (tree/node-reduce-kv f init root))

  clojure.core.reducers.CollFold
  (coll-fold [_ chunk-size combinef reducef]
    (binding [order/*compare* cmp]
      (tree/node-fold chunk-size root combinef
        (fn [acc node] (reducef acc (node/-kv node))))))

  clojure.lang.ILookup
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

  clojure.lang.IFn
  (invoke [this x] (.valAt this x nil))
  (invoke [this x not-found] (.valAt this x not-found))

  clojure.lang.Associative
  (containsKey [this x]
    (not= ::not-found (.valAt this x ::not-found)))
  (entryAt [this x]
    (let [v (.valAt this x ::not-found)]
      (when-not (identical? v ::not-found)
        (MapEntry. x v))))
  (assoc [this rng v]
    (range-map-assoc this rng v false))

  clojure.lang.IPersistentCollection
  (empty [_]
    (RangeMap. (node/leaf) cmp _meta))
  (cons [this x]
    (if (instance? MapEntry x)
      (.assoc this (key x) (val x))
      (.assoc this (first x) (second x))))
  (equiv [this o]
    (cond
      (identical? this o) true
      (not (instance? RangeMap o)) false
      (not= (tree/node-size root) (tree/node-size (.root ^RangeMap o))) false
      :else (= (seq this) (seq o))))

  Object
  (toString [this]
    (pr-str this))
  (hashCode [this]
    (.hasheq this))
  (equals [this o]
    (.equiv this o))

  clojure.lang.IHashEq
  (hasheq [_]
    (Murmur3/mixCollHash
      (unchecked-int
        (tree/node-reduce
          (fn [^long acc n]
            (unchecked-add acc (long (clojure.lang.Util/hasheq
                                       (MapEntry. (node/-k n) (node/-v n))))))
          (long 0)
          root))
      (tree/node-size root)))

  PRangeMap
  (ranges [this]
    (seq this))
  (get-entry [_ point]
    (binding [order/*compare* cmp]
      (loop [n root]
        (if (node/leaf? n)
          nil
          (let [rng (node/-k n)
                lo  (range-lo rng)
                hi  (range-hi rng)]
            (cond
              (< point lo)  (recur (node/-l n))
              (>= point hi) (recur (node/-r n))
              :else         [rng (node/-v n)]))))))
  (assoc-coalescing [this rng val]
    (range-map-assoc this rng val true))
  (range-remove [this rng]
    (range-map-remove this rng))
  (gaps [this]
    (when-let [s (seq this)]
      (let [pairs (partition 2 1 s)]
        (for [[[[_ h1] _] [[l2 _] _]] pairs
              :when (< h1 l2)]
          [h1 l2]))))

  PSpan
  (span [_]
    (when-not (node/leaf? root)
      (binding [order/*compare* cmp]
        [(range-lo (node/-k (tree/node-least root)))
         (range-hi (node/-k (tree/node-greatest root)))]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Carve-Out and Insertion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- carve-out
  "Remove overlapping ranges and re-add trimmed non-overlapping portions."
  [root lo hi overlapping]
  (let [root (reduce (fn [n [r _]] (tree/node-remove n r))
                     root overlapping)]
    (reduce (fn [n [[rl rh] rv]]
              (cond-> n
                (< rl lo) (tree/node-add [rl lo] rv)
                (> rh hi) (tree/node-add [hi rh] rv)))
            root overlapping)))

(defn- coalesce-and-insert
  "Insert [lo hi) -> v, merging with adjacent same-value ranges."
  [root lo hi v]
  (let [left-adj  (find-adjacent-left root lo)
        right-adj (find-adjacent-right root hi)
        [lo root] (if (and left-adj (= (second left-adj) v))
                    [(range-lo (first left-adj))
                     (tree/node-remove root (first left-adj))]
                    [lo root])
        [hi root] (if (and right-adj (= (second right-adj) v))
                    [(range-hi (first right-adj))
                     (tree/node-remove root (first right-adj))]
                    [hi root])]
    (tree/node-add root [lo hi] v)))

(defn- range-map-assoc
  "Insert range [lo hi) -> val, removing any overlapping portions.
   If coalesce? is true, adjacent ranges with the same value are merged."
  [^RangeMap rm rng v coalesce?]
  (let [[lo hi] rng
        cmp (.-cmp rm)]
    (when (>= lo hi)
      (throw (ex-info "Invalid range: lo must be < hi" {:range rng})))
    (binding [order/*compare* cmp]
      (let [overlapping (collect-overlapping (.-root rm) lo hi)
            root (carve-out (.-root rm) lo hi overlapping)
            root (if coalesce?
                   (coalesce-and-insert root lo hi v)
                   (tree/node-add root [lo hi] v))]
        (RangeMap. root cmp (.-_meta rm))))))

(defn- range-map-remove
  "Remove all mappings in [lo hi). Overlapping ranges are trimmed."
  [^RangeMap rm rng]
  (let [[lo hi] rng
        cmp (.-cmp rm)]
    (when (>= lo hi)
      (throw (ex-info "Invalid range: lo must be < hi" {:range rng})))
    (binding [order/*compare* cmp]
      (let [overlapping (collect-overlapping (.-root rm) lo hi)
            root (carve-out (.-root rm) lo hi overlapping)]
        (RangeMap. root cmp (.-_meta rm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sorted-disjoint?
  "True iff every range in the sorted vector satisfies `lo < hi` AND the
   ranges are non-overlapping. Ranges are half-open `[lo, hi)`, so
   disjointness requires `prev-hi <= cur-lo`. Input with invalid or
   overlapping ranges returns false so the caller falls through to the
   general path, which throws on the invalid input (matching the
   single-insert semantics of `assoc`)."
  [sorted-entries]
  (let [n (count sorted-entries)]
    (loop [i (long 0) prev-hi nil]
      (if (>= i n)
        true
        (let [[[lo hi] _] (nth sorted-entries i)]
          (cond
            ;; Invalid range: lo >= hi.
            (not (neg? (clojure.core/compare lo hi)))
            false

            ;; Overlap with predecessor: prev-hi > lo.
            (and prev-hi (pos? (clojure.core/compare prev-hi lo)))
            false

            :else (recur (unchecked-inc i) hi)))))))

(defn range-map
  "Create a range map from a collection of [range value] pairs.

   Ranges are [lo hi) (half-open, hi exclusive).

   When the input is disjoint (no overlapping ranges), the tree is built
   directly in O(n) via a balanced bottom-up construction, which is
   substantially faster than carving per insert. Overlapping input falls
   through to the general carving path, preserving 'later wins' semantics.

   Example:
     (range-map {[0 10] :a [20 30] :b})
     (range-map [[[0 10] :a] [[20 30] :b]])"
  ([]
   (RangeMap. (node/leaf) range-compare {}))
  ([coll]
   (binding [order/*compare* range-compare]
     (let [sorted (vec (sort-by (comp first first) coll))]
       (if (sorted-disjoint? sorted)
         (RangeMap. (tree/node-build-sorted sorted) range-compare {})
         (reduce
           (fn [rm [rng v]] (range-map-assoc rm rng v false))
           (RangeMap. (node/leaf) range-compare {})
           coll))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API (delegates to protocol)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assoc-coalescing
  "Insert range with coalescing. Adjacent ranges with the same value
   are automatically merged. Equivalent to Guava's putCoalescing.

   Example:
     (-> (range-map)
         (assoc-coalescing [0 100] :a)
         (assoc-coalescing [100 200] :a))
     ;; => single range [0 200) :a"
  [rm rng v]
  (proto/assoc-coalescing rm rng v))

(defn ranges
  "Return a seq of all [range value] pairs."
  [rm]
  (proto/ranges rm))

(defn span
  "Return [lo hi] spanning all ranges, or nil if empty."
  [rm]
  (proto/span rm))

(defn gaps
  "Return a seq of [lo hi) ranges that have no mapping."
  [rm]
  (proto/gaps rm))

(defn get-entry
  "Return [range value] for the range containing point x, or nil.
   Equivalent to Guava's getEntry(K).

   Example:
     (get-entry rm 50) ;; => [[0 100] :a]"
  [rm x]
  (proto/get-entry rm x))

(defn range-remove
  "Remove all mappings in the given range [lo hi).
   Any overlapping ranges are trimmed; ranges fully contained are removed.
   Equivalent to Guava's remove(Range).

   Example:
     (range-remove rm [25 75])
     ;; [0 100]:a becomes [0 25):a and [75 100):a"
  [rm rng]
  (proto/range-remove rm rng))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method RangeMap [^RangeMap m ^java.io.Writer w]
  (.write w "#range/map [")
  (let [s (seq m)]
    (when s
      (let [[k v] (first s)]
        (print-method [(vec k) v] w))
      (doseq [[k v] (rest s)]
        (.write w " ")
        (print-method [(vec k) v] w))))
  (.write w "]"))
