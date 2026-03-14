(ns com.dean.ordered-collections.tree.segment-tree
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
            [com.dean.ordered-collections.tree.protocol :as proto]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang ILookup Associative IPersistentCollection Seqable
             Counted IFn IMeta IObj MapEntry Murmur3]
            [com.dean.ordered_collections.tree.protocol PRangeAggregate]))


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

(defn- node-agg [n]
  (if (node/leaf? n) nil (.-agg ^AggregateNode n)))

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
;; To compute op over [lo, hi]:
;;   1. If node's key range is entirely within [lo, hi], use its agg
;;   2. If node's key range is entirely outside [lo, hi], return identity
;;   3. Otherwise, recurse on children and include this node's value if in range
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- query-range
  "Compute op over all values with keys in [lo, hi] inclusive."
  [n lo hi op identity]
  (if (node/leaf? n)
    identity
    (let [lo (long lo)
          hi (long hi)
          k  (long (node/-k n))]
      (cond
        ;; Node entirely right of query range
        (< hi k)
        (query-range (node/-l n) lo hi op identity)

        ;; Node entirely left of query range
        (> lo k)
        (query-range (node/-r n) lo hi op identity)

        ;; Node's key is within range - need to check subtrees carefully
        :else
        (let [;; Left subtree: query for [lo, k-1]
              l-result (if (< lo k)
                         (query-range (node/-l n) lo (dec k) op identity)
                         identity)
              ;; This node's contribution
              v-result (node/-v n)
              ;; Right subtree: query for [k+1, hi]
              r-result (if (> hi k)
                         (query-range (node/-r n) (inc k) hi op identity)
                         identity)]
          (op l-result (op v-result r-result)))))))

(defn- query-range-fast
  "Optimized range query that uses subtree aggregates when possible.

   Key insight: if we know the entire subtree is within [lo, hi], we can
   use the pre-computed aggregate instead of recursing."
  [n lo hi op identity cmp]
  (if (node/leaf? n)
    identity
    (let [lo   (long lo)
          hi   (long hi)
          k    (long (node/-k n))
          l    (node/-l n)
          r    (node/-r n)
          l-lo (if (node/leaf? l) k (long (node/-k (tree/node-least l))))
          r-hi (if (node/leaf? r) k (long (node/-k (tree/node-greatest r))))]
      (cond
        ;; Entire subtree outside range
        (or (< r-hi lo) (> l-lo hi))
        identity

        ;; Entire subtree inside range - use aggregate!
        (and (<= lo l-lo) (>= hi r-hi))
        (.-agg ^AggregateNode n)

        ;; Partial overlap - recurse
        :else
        (let [l-result (query-range-fast l lo hi op identity cmp)
              v-result (if (and (<= lo k) (<= k hi))
                         (node/-v n)
                         identity)
              r-result (query-range-fast r lo hi op identity cmp)]
          (op l-result (op v-result r-result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SegmentTree Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare seg-assoc)

(deftype SegmentTree [root op identity creator cmp _meta]

  IMeta
  (meta [_] _meta)

  IObj
  (withMeta [_ m] (SegmentTree. root op identity creator cmp m))

  Counted
  (count [_] (tree/node-size root))

  Seqable
  (seq [_]
    (tree/entry-seq root (tree/node-size root)))

  ILookup
  (valAt [_ k] (.valAt _ k nil))
  (valAt [_ k not-found]
    (binding [order/*compare* cmp]
      (if-let [n (tree/node-find root k)]
        (node/-v n)
        not-found)))

  IFn
  (invoke [this k] (.valAt this k nil))
  (invoke [this k not-found] (.valAt this k not-found))

  Associative
  (containsKey [_ k]
    (binding [order/*compare* cmp]
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
    (binding [order/*compare* cmp]
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

(defn- seg-assoc [^SegmentTree st k v]
  (binding [order/*compare* (.-cmp st)
            tree/*t-join*   (.-creator st)]
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

(defn segment-tree
  "Create a segment tree with the given associative operation and identity.

   Arguments:
     op       - associative binary operation (e.g., +, min, max)
     identity - identity element for op (e.g., 0 for +, Long/MAX_VALUE for min)
     coll     - map or seq of [index value] pairs

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
   (let [cmp     order/normal-compare
         creator (make-agg-creator op identity)]
     (binding [order/*compare* cmp
               tree/*t-join*   creator]
       (SegmentTree.
         (reduce (fn [n [k v]] (tree/node-add n k v)) (node/leaf) coll)
         op identity creator cmp {})))))

(def query
  "Query the aggregate over index range [lo, hi] inclusive.
   O(log n) time.

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30, 3 40}))
     (query st 0 3)  ; => 100
     (query st 1 2)  ; => 50"
  proto/aggregate-range)

(def update-val
  "Update the value at index k. O(log n) time.

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30}))
     (def st' (update-val st 1 100))
     (query st' 0 2)  ; => 140"
  proto/update-val)

(def update-fn
  "Update the value at index k by applying f to the current value.
   O(log n) time.

   Example:
     (def st (segment-tree + 0 {0 10, 1 20, 2 30}))
     (def st' (update-fn st 1 #(* % 2)))  ; double index 1
     (query st' 0 2)  ; => 80"
  proto/update-fn)

(def aggregate
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
