(ns com.dean.ordered-collections.types.segment-tree
  "A segment tree for efficient range aggregate queries.

   Supports O(log n) point updates and O(log n) range queries for any
   associative operation (sum, min, max, gcd, etc.).

   CONCEPT:
   Each node stores an aggregate of its entire subtree. For sum:

                    ┌─────────────┐
                    │ key: 3      │
                    │ val: 40     │
                    │ agg: 150 ◄──────── sum of entire tree
                    └──────┬──────┘
               ┌───────────┴───────────┐
        ┌──────┴──────┐         ┌──────┴──────┐
        │ key: 1      │         │ key: 4      │
        │ val: 20     │         │ val: 50     │
        │ agg: 30 ◄───────      │ agg: 80 ◄───────
        └──────┬──────┘   │     └──────┬──────┘   │
               │          │            │          │
        ┌──────┴──────┐   │     ┌──────┴──────┐   │
        │ key: 0      │   │     │ key: 5      │   │
        │ val: 10     │   │     │ val: 30     │   │
        │ agg: 10     │   │     │ agg: 30     │   │
        └─────────────┘   │     └─────────────┘   │
                          │                       │
               10 + 20 = 30              50 + 30 = 80

   RANGE QUERY: query(1, 4) = sum of indices 1,2,3,4
   Uses aggregates to avoid visiting every node - O(log n).

   EXAMPLE:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30, 3 40, 4 50}))
     (query st 0 4)     ; => 150 (sum of all)
     (query st 1 3)     ; => 90 (20 + 30 + 40)
     (update st 2 100)  ; => new tree with index 2 = 100
     (query st 1 3)     ; => 160 (20 + 100 + 40)"
  (:require [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.root]
            [com.dean.ordered-collections.tree.tree     :as tree]
            [com.dean.ordered-collections.protocol      :as proto]
            [com.dean.ordered-collections.util          :refer [defalias]])
  (:import  [clojure.lang ILookup Associative IPersistentCollection Seqable
             Counted IFn IMeta IObj MapEntry Murmur3]
            [java.util Comparator]
            [com.dean.ordered_collections.protocol PRangeAggregate]
            [com.dean.ordered_collections.tree.root INodeCollection
                                         IBalancedCollection
                                         IOrderedCollection]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aggregate Node
;;
;; Extends SimpleNode with an aggregate field that stores op applied to the
;; entire subtree: agg = op(left.agg, val, right.agg)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype AggregateNode [k v l r ^long x agg]
  com.dean.ordered_collections.tree.node.IBalancedNode
  (x [_] x)
  com.dean.ordered_collections.tree.node.INode
  (k  [_] k)
  (v  [_] v)
  (l  [_] l)
  (r  [_] r)
  (kv [_] (MapEntry. k v)))

(defn- node-agg
  "Return the cached aggregate for subtree n, or identity for an empty subtree."
  [n identity]
  (if (node/leaf? n) identity (.-agg ^AggregateNode n)))

(defn- make-agg-creator
  "Create a node constructor that computes aggregates using op and identity."
  [op identity]
  (fn [k v l r]
    (let [l-agg (if (node/leaf? l) identity (.-agg ^AggregateNode l))
          r-agg (if (node/leaf? r) identity (.-agg ^AggregateNode r))
          agg   (op l-agg (op v r-agg))]
      (AggregateNode. k v l r (+ 1 (tree/node-size l) (tree/node-size r)) agg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Query Algorithm
;;
;; Query uses two splits:
;;   1. split at lo  =>  (< lo)  [= lo]  (> lo)
;;   2. split >lo at hi => (lo,hi) [= hi] (> hi)
;;
;; The middle tree already stores the aggregate for all keys strictly between
;; lo and hi. We then combine that cached aggregate with exact lo/hi matches.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- query-range-fast
  "Compute op over all values with keys in [lo, hi] inclusive."
  [root lo hi op identity cmp]
  (if (node/leaf? root)
    identity
    (binding [order/*compare* cmp]
      (if (pos? (order/compare lo hi))
        identity
        (let [[_ lo-present gt] (tree/node-split root lo)
              [mid hi-present _] (tree/node-split gt hi)
              acc0 (if lo-present
                     (second lo-present)
                     identity)
              acc1 (op acc0 (node-agg mid identity))]
          (if hi-present
            (op acc1 (second hi-present))
            acc1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SegmentTree Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare seg-assoc)

(defmacro with-segment-tree [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.IOrderedCollection}))
             tree/*t-join*   (.getAllocator ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.INodeCollection}))]
     ~@body))

(deftype SegmentTree [root op identity creator cmp _meta]

  java.io.Serializable

  INodeCollection
  (getAllocator [_]
    creator)
  (getRoot [_]
    root)

  IOrderedCollection
  (getCmp [_]
    cmp)
  (isCompatible [_ o]
    (and (instance? SegmentTree o)
         (= cmp (.getCmp ^IOrderedCollection o))))
  (isSimilar [_ o]
    (map? o))

  IBalancedCollection
  (getStitch [_]
    creator)

  IMeta
  (meta [_] _meta)

  IObj
  (withMeta [_ m] (SegmentTree. root op identity creator cmp m))

  Counted
  (count [_] (tree/node-size root))

  Seqable
  (seq [_]
    (tree/node-entry-seq root (tree/node-size root)))

  clojure.lang.Reversible
  (rseq [_]
    (tree/node-entry-seq-reverse root (tree/node-size root)))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (tree/node-reduce-entries f init root))

  clojure.lang.IReduce
  (reduce [this f]
    (tree/node-reduce-entries f root))

  clojure.lang.IKVReduce
  (kvreduce [this f init]
    (tree/node-reduce-kvs f init root))

  clojure.lang.MapEquivalence

  ILookup
  (valAt [_ k] (.valAt _ k nil))
  (valAt [this k not-found]
    (with-segment-tree this
      (if-let [n (tree/node-find root k)]
        (node/-v n)
        not-found)))

  IFn
  (invoke [this k] (.valAt this k nil))
  (invoke [this k not-found] (.valAt this k not-found))

  Associative
  (containsKey [this k]
    (with-segment-tree this
      (some? (tree/node-find root k))))
  (entryAt [this k]
    (let [v (.valAt this k ::not-found)]
      (when-not (= v ::not-found)
        (MapEntry. k v))))
  (assoc [this k v]
    (seg-assoc this k v))

  IPersistentCollection
  (empty [_]
    (SegmentTree. (node/leaf) op identity creator cmp {}))
  (cons [this x]
    (if (instance? MapEntry x)
      (.assoc this (key x) (val x))
      (.assoc this (first x) (second x))))
  (equiv [this that]
    (and (instance? SegmentTree that)
         (= (seq this) (seq that))))

  Object
  (toString [this]
    (pr-str this))

  java.lang.Comparable
  (compareTo [this o]
    (with-segment-tree this
      (cond
        (identical? this o) 0
        (.isCompatible this o) (tree/node-map-compare root (.getRoot ^INodeCollection o))
        (.isSimilar this o) (.compareTo ^Comparable (into (empty o) this) o)
        true (throw (ex-info "unsupported comparison: " {:this this :o o})))))

  java.util.Map
  (get [this k]
    (.valAt this k))
  (isEmpty [_]
    (node/leaf? root))
  (size [_]
    (tree/node-size root))
  (keySet [this]
    (with-segment-tree this
      (set (tree/node-vec root :accessor :k))))
  (put [_ _ _]
    (throw (UnsupportedOperationException.)))
  (putAll [_ _]
    (throw (UnsupportedOperationException.)))
  (clear [_]
    (throw (UnsupportedOperationException.)))
  (values [this]
    (with-segment-tree this
      (tree/node-vec root :accessor :v)))
  (entrySet [this]
    (with-segment-tree this
      (set (tree/node-vec root :accessor :kv))))
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))

  clojure.lang.IPersistentMap
  (assocEx [this k v]
    (with-segment-tree this
      (if-let [new-root (tree/node-add-if-absent root k v)]
        (SegmentTree. new-root op identity creator cmp _meta)
        (throw (Exception. "Key already present")))))
  (without [this k]
    (with-segment-tree this
      (SegmentTree. (tree/node-remove root k) op identity creator cmp _meta)))

  java.util.SortedMap
  (comparator [_]
    cmp)
  (firstKey [this]
    (with-segment-tree this
      (first (tree/node-least-kv root))))
  (lastKey [this]
    (with-segment-tree this
      (first (tree/node-greatest-kv root))))
  (headMap [this k]
    (with-segment-tree this
      (SegmentTree. (tree/node-split-lesser root k) op identity creator cmp {})))
  (tailMap [this k]
    (with-segment-tree this
      (let [[_ present gt] (tree/node-split root k)]
        (if present
          (SegmentTree. (tree/node-add gt (first present) (second present))
                        op identity creator cmp {})
          (SegmentTree. gt op identity creator cmp {})))))
  (subMap [this from to]
    (with-segment-tree this
      (let [[_ from-present from-gt] (tree/node-split root from)
            from-tree (if from-present
                        (tree/node-add from-gt (first from-present) (second from-present))
                        from-gt)
            to-tree   (tree/node-split-lesser root to)
            result    (tree/node-set-intersection from-tree to-tree)]
        (SegmentTree. result op identity creator cmp {}))))

  clojure.lang.Sorted
  (entryKey [_ entry]
    (key entry))
  (seq [_ ascending]
    (if ascending
      (tree/node-entry-seq root)
      (tree/node-entry-seq-reverse root)))
  (seqFrom [this k ascending]
    (with-segment-tree this
      (let [[lt present gt] (tree/node-split root k)]
        (if ascending
          (if present
            (cons (MapEntry. (first present) (second present))
                  (tree/node-entry-seq gt))
            (tree/node-entry-seq gt))
          (if present
            (cons (MapEntry. (first present) (second present))
                  (tree/node-entry-seq-reverse lt))
            (tree/node-entry-seq-reverse lt))))))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/mixCollHash
      (unchecked-int
        (tree/node-reduce
          (fn [^long acc n]
            (unchecked-add acc (long (clojure.lang.Util/hasheq
                                       (MapEntry. (node/-k n) (node/-v n))))))
          (long 0)
          root))
      (tree/node-size root)))

  PRangeAggregate
  (aggregate-range [this lo hi]
    (with-segment-tree this
      (query-range-fast root lo hi op identity cmp)))
  (aggregate [this]
    (if (node/leaf? root)
      identity
      (.-agg ^AggregateNode root)))
  (update-val [this k v]
    (seg-assoc this k v))
  (update-fn [this k f]
    (let [old-val (get this k identity)]
      (seg-assoc this k (f old-val)))))

(extend-type SegmentTree
  proto/PNearest
  (nearest [this test k]
    (with-segment-tree this
      (case test
        :< (when-let [n (tree/node-predecessor (.getRoot ^INodeCollection this) k)]
             [(node/-k n) (node/-v n)])
        :<= (when-let [n (tree/node-find-nearest (.getRoot ^INodeCollection this) k :<)]
              [(node/-k n) (node/-v n)])
        :> (when-let [n (tree/node-successor (.getRoot ^INodeCollection this) k)]
             [(node/-k n) (node/-v n)])
        :>= (when-let [n (tree/node-find-nearest (.getRoot ^INodeCollection this) k :>)]
              [(node/-k n) (node/-v n)])
        (throw (ex-info "nearest test must be :<, :<=, :>, or :>=" {:test test})))))
  (subrange [this test k]
    (with-segment-tree this
      (let [root        (.getRoot ^INodeCollection this)
            result-root (case test
                          (:< :<=) (tree/node-split-lesser root k)
                          (:> :>=) (tree/node-split-greater root k)
                          (throw (ex-info "subrange test must be :<, :<=, :>, or :>=" {:test test})))
            result-root (case test
                          (:<= :>=) (if-let [n (tree/node-find root k)]
                                      (tree/node-add result-root (node/-k n) (node/-v n))
                                      result-root)
                          result-root)]
        (SegmentTree. result-root (.-op this) (.-identity this) (.-creator this) (.-cmp this) {}))))

  proto/PRanked
  (rank-of [this k]
    (or (tree/node-rank (.getRoot ^INodeCollection this) k (.getCmp ^IOrderedCollection this)) -1))
  (slice [this start end]
    (let [root  (.getRoot ^INodeCollection this)
          n     (tree/node-size root)
          start (max 0 (long start))
          end   (min n (long end))]
      (when (< start end)
        (with-segment-tree this
          (map (fn [n] (MapEntry. (node/-k n) (node/-v n)))
               (tree/node-subseq root start (dec end)))))))
  (median [this]
    (let [root (.getRoot ^INodeCollection this)
          n    (tree/node-size root)]
      (when (pos? n)
        (with-segment-tree this
          (let [n (tree/node-nth root (quot (dec n) 2))]
            (MapEntry. (node/-k n) (node/-v n)))))))
  (percentile [this pct]
    (let [root (.getRoot ^INodeCollection this)
          n    (tree/node-size root)]
      (when (pos? n)
        (let [idx (min (dec n) (long (* (/ (double pct) 100.0) n)))]
          (with-segment-tree this
            (let [n (tree/node-nth root idx)]
              (MapEntry. (node/-k n) (node/-v n))))))))

  proto/PSplittable
  (split-key [this k]
    (with-segment-tree this
      (let [root         (.getRoot ^INodeCollection this)
            [l present r] (tree/node-split root k)
            entry         (when present [(first present) (second present)])]
        [(SegmentTree. l (.-op this) (.-identity this) (.-creator this) (.-cmp this) {})
         entry
         (SegmentTree. r (.-op this) (.-identity this) (.-creator this) (.-cmp this) {})])))
  (split-at [this i]
    (with-segment-tree this
      (let [root (.getRoot ^INodeCollection this)
            n    (tree/node-size root)]
        (cond
          (<= i 0) [(.empty this) this]
          (>= i n) [this (.empty this)]
          :else
          (let [left-root  (tree/node-split-lesser root (node/-k (tree/node-nth root i)))
                right-root (tree/node-split-nth root i)]
            [(SegmentTree. left-root (.-op this) (.-identity this) (.-creator this) (.-cmp this) {})
             (SegmentTree. right-root (.-op this) (.-identity this) (.-creator this) (.-cmp this) {})]))))))

(defn- seg-assoc [^SegmentTree st k v]
  (with-segment-tree st
    (SegmentTree.
      (tree/node-add (.-root st) k v)
      (.-op st)
      (.-identity st)
      (.-creator st)
      (.-cmp st)
      (.-_meta st))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn segment-tree-with
  "Create a segment tree with a custom comparator.

   The comparator controls key ordering for point lookups, updates,
   and range queries."
  ([^Comparator comparator op identity]
   (segment-tree-with comparator op identity nil))
  ([^Comparator comparator op identity coll]
   (let [creator (make-agg-creator op identity)]
     (binding [order/*compare* comparator
               tree/*t-join*   creator]
       (SegmentTree.
         (reduce (fn [n [k v]]
                   (tree/node-add n k v))
                 (node/leaf)
                 coll)
         op identity creator comparator {})))))

(defn segment-tree-by
  "Create a segment tree with a custom ordering predicate.

   The predicate should define a total order like < or >."
  [pred op identity coll]
  (segment-tree-with (order/compare-by pred) op identity coll))

(defn segment-tree
  "Create a segment tree with the given associative operation and identity.

   Arguments:
     op       - associative binary operation (e.g., +, min, max)
     identity - identity element for op (e.g., 0 for +, Long/MAX_VALUE for min)
     coll     - map or seq of [key value] pairs

   Example:
     ;; Sum segment tree
     (segment-tree + 0 {0 10, 1 20, 2 30})

     ;; Min segment tree
     (segment-tree min Long/MAX_VALUE {0 5, 1 3, 2 8})

     ;; Max segment tree
     (segment-tree max Long/MIN_VALUE [[0 5] [1 3] [2 8]])"
  ([op identity]
   (segment-tree op identity nil))
  ([op identity coll]
   (segment-tree-with order/normal-compare op identity coll)))

(defalias query
  "Query the aggregate over key range [lo, hi] inclusive.
   O(log n) time.

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30, 3 40}))
     (query st 0 3)  ; => 100
     (query st 1 2)  ; => 50"
  proto/aggregate-range)

(defalias update-val
  "Update the value at index k. O(log n) time.

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30}))
     (def st' (update-val st 1 100))
     (query st' 0 2)  ; => 140"
  proto/update-val)

(defalias update-fn
  "Update the value at index k by applying f to the current value.
   O(log n) time.

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30}))
     (def st' (update-fn st 1 #(* % 2)))  ; double index 1
     (query st' 0 2)  ; => 80"
  proto/update-fn)

(defalias aggregate
  "Return the aggregate over the entire tree. O(1) time."
  proto/aggregate)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method SegmentTree [^SegmentTree st ^java.io.Writer w]
  (.write w "#<SegmentTree ")
  (print-method (into {} (seq st)) w)
  (.write w ">"))

;; Convenience constructors for common operations

(defn sum-tree
  "Create a segment tree for range sums.
   (query st lo hi) returns sum of values in [lo, hi]."
  [coll]
  (segment-tree + 0 coll))

(defn min-tree
  "Create a segment tree for range minimum queries.
   (query st lo hi) returns minimum value in [lo, hi]."
  [coll]
  (segment-tree min Long/MAX_VALUE coll))

(defn max-tree
  "Create a segment tree for range maximum queries.
   (query st lo hi) returns maximum value in [lo, hi]."
  [coll]
  (segment-tree max Long/MIN_VALUE coll))
