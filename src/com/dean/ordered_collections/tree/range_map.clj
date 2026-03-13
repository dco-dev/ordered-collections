(ns com.dean.ordered-collections.tree.range-map
  "A map from non-overlapping ranges to values.

   Unlike IntervalMap (which allows overlapping intervals), RangeMap enforces
   that ranges never overlap. When inserting a new range, any overlapping
   portions of existing ranges are removed.

   SEMANTICS (compatible with Guava's TreeRangeMap):
   - `assoc` (put): inserts range, carving out overlaps. Does NOT coalesce.
   - `assoc-coalescing` (putCoalescing): inserts and coalesces adjacent
     same-value ranges.

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

   PERFORMANCE:
   - Point lookup: O(log n)
   - Insert/assoc: O(k log n) where k = number of overlapping ranges
   - Coalescing insert: O(k log n)
   - Remove: O(k log n)
   For typical use (k=1-3 overlaps), effectively O(log n).

   USE CASES:
   - IP address range mappings
   - Time-based scheduling (non-overlapping slots)
   - Memory region allocation
   - Version ranges in dependency resolution"
  (:require [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.protocol :as proto]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang ILookup Associative IPersistentCollection Seqable
             Counted IFn IMeta IObj MapEntry]
            [com.dean.ordered_collections.tree.protocol PRangeMap]
            [com.dean.ordered_collections.tree.tree EnumFrame]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- range-lo [[lo _]] lo)
(defn- range-hi [[_ hi]] hi)

(defn- range-compare
  "Compare ranges by their lower bound."
  [a b]
  (compare (range-lo a) (range-lo b)))

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
          (= rl lo)  [(node/-k n) (node/-v n)]  ; exact match
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
          (= rl lo)  [(node/-k n) (node/-v n)]  ; exact match
          (> rl lo)  (recur (node/-l n) [(node/-k n) (node/-v n)])
          :else      (recur (node/-r n) best))))))

(defn- collect-overlapping
  "Collect all ranges that overlap [lo, hi). Returns vector of [range value].
   Time: O(log n + k) where k = number of overlapping ranges."
  [root lo hi]
  (if (node/leaf? root)
    []
    (let [result (volatile! (transient []))
          ;; Find floor - might overlap if its end > lo
          floor (find-floor-range root lo)
          ;; Start iteration from floor's position, or ceiling if floor doesn't overlap
          start-range (if (and floor (> (range-hi (first floor)) lo))
                        floor
                        (find-ceiling-range root lo))]
      (when start-range
        ;; Build enumerator starting from start-range's position
        ;; We'll iterate forward while range-lo < hi
        (let [[start-key _] start-range
              start-lo (range-lo start-key)]
          ;; Find the node and build enumerator from there
          (loop [n root
                 enum nil]
            (if (node/leaf? n)
              ;; Process collected frames
              (loop [e enum]
                (when e
                  (let [node (tree/node-enum-first e)
                        [rl rh] (node/-k node)]
                    (when (< rl hi)
                      ;; Check overlap: rl < hi AND rh > lo
                      (when (> rh lo)
                        (vswap! result conj! [(node/-k node) (node/-v node)]))
                      (recur (tree/node-enum-rest e))))))
              ;; Navigate to start position
              (let [[rl _] (node/-k n)]
                (cond
                  (< start-lo rl) (recur (node/-l n) (EnumFrame. n (node/-r n) enum))
                  (> start-lo rl) (recur (node/-r n) enum)
                  :else
                  ;; Found start - build enum and process
                  (let [e (EnumFrame. n (node/-r n) enum)]
                    (loop [e e]
                      (when e
                        (let [node (tree/node-enum-first e)
                              [rl rh] (node/-k node)]
                          (when (< rl hi)
                            (when (> rh lo)
                              (vswap! result conj! [(node/-k node) (node/-v node)]))
                            (recur (tree/node-enum-rest e)))))))))))))
      (persistent! @result))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RangeMap Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ->RangeMap range-map-assoc rm-ranges rm-get-entry rm-spanning-range rm-gaps rm-range-remove)

(deftype RangeMap [root cmp _meta]

  IMeta
  (meta [_] _meta)

  IObj
  (withMeta [_ m] (RangeMap. root cmp m))

  Counted
  (count [_] (tree/node-size root))

  Seqable
  (seq [_]
    (tree/entry-seq root (tree/node-size root)))

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
    (range-map-assoc this rng v false))

  IPersistentCollection
  (empty [_]
    (RangeMap. (node/leaf) cmp {}))
  (cons [this x]
    (if (instance? MapEntry x)
      (.assoc this (key x) (val x))
      (.assoc this (first x) (second x))))
  (equiv [this that]
    (and (instance? RangeMap that)
         (= (seq this) (seq that))))

  PRangeMap
  (ranges [this]
    (rm-ranges this))
  (get-entry [this point]
    (rm-get-entry this point))
  (assoc-coalescing [this rng val]
    (range-map-assoc this rng val true))
  (range-remove [this rng]
    (rm-range-remove this rng))
  (spanning-range [this]
    (rm-spanning-range this))
  (gaps [this]
    (rm-gaps this)))

(defn- range-map-assoc
  "Insert range [lo hi) -> val, removing any overlapping portions.
   If coalesce? is true, adjacent ranges with the same value are merged."
  [^RangeMap rm rng v coalesce?]
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
                    root' overlapping)]
        (if coalesce?
          ;; Coalescing mode: check for adjacent same-value ranges
          (let [left-adj (find-adjacent-left root'' lo)
                right-adj (find-adjacent-right root'' hi)
                [final-lo root'''] (if (and left-adj (= (second left-adj) v))
                                     [(range-lo (first left-adj))
                                      (tree/node-remove root'' (first left-adj))]
                                     [lo root''])
                [final-hi root''''] (if (and right-adj (= (second right-adj) v))
                                      [(range-hi (first right-adj))
                                       (tree/node-remove root''' (first right-adj))]
                                      [hi root'''])
                root''''' (tree/node-add root'''' [final-lo final-hi] v)]
            (RangeMap. root''''' cmp (.-_meta rm)))
          ;; Non-coalescing mode: just add the range
          (let [root''' (tree/node-add root'' [lo hi] v)]
            (RangeMap. root''' cmp (.-_meta rm))))))))

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
       (fn [rm [rng v]] (range-map-assoc rm rng v false))
       (RangeMap. (node/leaf) range-compare {})
       coll))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol Implementation Helpers (called from deftype)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rm-ranges
  [^RangeMap rm]
  (seq rm))

(defn- rm-spanning-range
  [^RangeMap rm]
  (when-not (node/leaf? (.-root rm))
    (binding [order/*compare* (.-cmp rm)]
      (let [least    (tree/node-least (.-root rm))
            greatest (tree/node-greatest (.-root rm))]
        [(range-lo (node/-k least))
         (range-hi (node/-k greatest))]))))

(defn- rm-gaps
  [^RangeMap rm]
  (when-let [s (seq rm)]
    (let [pairs (partition 2 1 s)]
      (for [[[[_ h1] _] [[l2 _] _]] pairs
            :when (< h1 l2)]
        [h1 l2]))))

(defn- rm-get-entry
  [^RangeMap rm x]
  (binding [order/*compare* (.-cmp rm)]
    (loop [n (.-root rm)]
      (if (node/leaf? n)
        nil
        (let [rng (node/-k n)
              lo  (range-lo rng)
              hi  (range-hi rng)]
          (cond
            (< x lo)  (recur (node/-l n))
            (>= x hi) (recur (node/-r n))
            :else     [rng (node/-v n)]))))))

(defn- rm-range-remove
  [^RangeMap rm rng]
  (let [[lo hi] rng
        cmp (.-cmp rm)]
    (when (>= lo hi)
      (throw (ex-info "Invalid range: lo must be < hi" {:range rng})))
    (binding [order/*compare* cmp]
      (let [overlapping (collect-overlapping (.-root rm) lo hi)
            ;; Remove all overlapping ranges
            root' (reduce (fn [n [r _]] (tree/node-remove n r))
                          (.-root rm) overlapping)
            ;; Add back trimmed portions (outside the removal range)
            root'' (reduce
                     (fn [n [[rl rh] rv]]
                       (cond-> n
                         (< rl lo) (tree/node-add [rl lo] rv)
                         (> rh hi) (tree/node-add [hi rh] rv)))
                     root' overlapping)]
        (RangeMap. root'' cmp (.-_meta rm))))))

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

(defn spanning-range
  "Return [lo hi] spanning all ranges, or nil if empty."
  [rm]
  (proto/spanning-range rm))

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
  (.write w "#ordered/range-map [")
  (let [s (seq m)]
    (when s
      (let [[k v] (first s)]
        (print-method [(vec k) v] w))
      (doseq [[k v] (rest s)]
        (.write w " ")
        (print-method [(vec k) v] w))))
  (.write w "]"))
