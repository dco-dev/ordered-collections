(ns ordered-collections.types.priority-queue
  "Persistent priority queue backed by a weight-balanced tree.

  Internally an ordered map from priorities to vectors of values. Duplicate
  priorities are stable in insertion order. The public API exposes
  [priority value] pairs; the vector grouping is hidden."
  (:require [clojure.core.reducers :as r :refer [coll-fold]]
            [ordered-collections.types.shared :refer [with-compare]]
            [ordered-collections.kernel.node     :as node]
            [ordered-collections.kernel.order    :as order]
            [ordered-collections.protocol      :as proto]
            [ordered-collections.kernel.root]
            [ordered-collections.kernel.tree     :as tree])
  (:import  [clojure.lang RT Murmur3]
            [java.util Comparator]
            [ordered_collections.kernel.root INodeCollection
                                           IBalancedCollection
                                           IOrderedCollection]))


(declare ->PriorityQueue)

(def ^:private not-found-sentinel ::not-found)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Expanding Seq Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The tree stores [priority -> [v1 v2 ...]] entries. These seq types expand
;; each node into [priority v1] [priority v2] ... pairs, walking the tree
;; via enumerator and the value vector via an index.

(defn- seq-equiv
  "Element-wise sequential equivalence (non-recursive through equiv)."
  [s1 o]
  (if-not (or (instance? clojure.lang.Sequential o) (instance? java.util.List o))
    false
    (loop [s1 (seq s1) s2 (seq o)]
      (cond
        (nil? s1) (nil? s2)
        (nil? s2) false
        (not (clojure.lang.Util/equiv (first s1) (first s2))) false
        :else (recur (next s1) (next s2))))))


(deftype PriorityQueueSeq [enum ^long vi cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (let [node (tree/node-enum-first enum)]
      [(node/-k node) (nth (node/-v node) vi)]))
  (next [_]
    (let [node (tree/node-enum-first enum)
          vals (node/-v node)]
      (if (< (inc vi) (count vals))
        (PriorityQueueSeq. enum (inc vi) (when cnt (unchecked-dec-int cnt)) nil)
        (when-let [e (tree/node-enum-rest enum)]
          (PriorityQueueSeq. e 0 (when cnt (unchecked-dec-int cnt)) nil)))))
  (more [this]
    (or (.next this) ()))
  (cons [this o]
    (clojure.lang.Cons. o this))

  clojure.lang.Seqable
  (seq [this] this)

  clojure.lang.Sequential

  java.lang.Iterable
  (iterator [this]
    (clojure.lang.SeqIterator. this))

  clojure.lang.Counted
  (count [_]
    (if cnt cnt
      (loop [e enum, vi vi, n 0]
        (if (nil? e)
          n
          (let [node (tree/node-enum-first e)
                vlen (count (node/-v node))
                remaining (- vlen vi)]
            (recur (tree/node-enum-rest e) 0 (+ n remaining)))))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum, vi (long vi), acc init]
      (if (nil? e)
        acc
        (let [node (tree/node-enum-first e)
              p    (node/-k node)
              vals (node/-v node)
              vlen (count vals)]
          (let [acc (loop [i vi, acc acc]
                      (if (>= i vlen)
                        acc
                        (let [ret (f acc [p (nth vals i)])]
                          (if (reduced? ret)
                            ret
                            (recur (inc i) ret)))))]
            (if (reduced? acc)
              @acc
              (recur (tree/node-enum-rest e) 0 acc)))))))

  clojure.lang.IReduce
  (reduce [this f]
    (if enum
      (let [node (tree/node-enum-first enum)
            p    (node/-k node)
            vals (node/-v node)
            acc  [p (nth vals vi)]]
        (let [remaining-start (inc vi)
              vlen (count vals)]
          ;; First reduce within the current node's remaining values
          (let [[acc e] (loop [i remaining-start, acc acc]
                          (if (>= i vlen)
                            [acc (tree/node-enum-rest enum)]
                            (let [ret (f acc [p (nth vals i)])]
                              (if (reduced? ret)
                                [ret nil]
                                (recur (inc i) ret)))))]
            (if (or (reduced? acc) (nil? e))
              (if (reduced? acc) @acc acc)
              ;; Continue with remaining nodes
              (.reduce ^clojure.lang.IReduceInit
                (PriorityQueueSeq. e 0 nil nil) f acc)))))
      (f)))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  clojure.lang.IPersistentCollection
  (empty [_] ())
  (equiv [this o]
    (seq-equiv this o))

  Object
  (hashCode [this]
    (clojure.lang.Util/hash this))
  (equals [this o]
    (clojure.lang.Util/equals this o))

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (PriorityQueueSeq. enum vi cnt m)))

(deftype PriorityQueueSeqReverse [enum ^long vi cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (let [node (tree/node-enum-first enum)]
      [(node/-k node) (nth (node/-v node) vi)]))
  (next [_]
    (if (pos? vi)
      (PriorityQueueSeqReverse. enum (dec vi) (when cnt (unchecked-dec-int cnt)) nil)
      (when-let [e (tree/node-enum-prior enum)]
        (let [vals (node/-v (tree/node-enum-first e))]
          (PriorityQueueSeqReverse. e (dec (count vals))
            (when cnt (unchecked-dec-int cnt)) nil)))))
  (more [this]
    (or (.next this) ()))
  (cons [this o]
    (clojure.lang.Cons. o this))

  clojure.lang.Seqable
  (seq [this] this)

  clojure.lang.Sequential

  java.lang.Iterable
  (iterator [this]
    (clojure.lang.SeqIterator. this))

  clojure.lang.Counted
  (count [_]
    (if cnt cnt
      (loop [e enum, vi vi, n 0]
        (if (nil? e)
          n
          (let [remaining (inc vi)]
            (recur (tree/node-enum-prior e)
                   (if-let [e2 (tree/node-enum-prior e)]
                     (dec (count (node/-v (tree/node-enum-first e2))))
                     0)
                   (+ n remaining)))))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum, vi (long vi), acc init]
      (if (nil? e)
        acc
        (let [node (tree/node-enum-first e)
              p    (node/-k node)
              vals (node/-v node)]
          (let [acc (loop [i vi, acc acc]
                      (if (neg? i)
                        acc
                        (let [ret (f acc [p (nth vals i)])]
                          (if (reduced? ret)
                            ret
                            (recur (dec i) ret)))))]
            (if (reduced? acc)
              @acc
              (if-let [e2 (tree/node-enum-prior e)]
                (recur e2 (dec (count (node/-v (tree/node-enum-first e2)))) acc)
                acc)))))))

  clojure.lang.IReduce
  (reduce [this f]
    (if enum
      (let [node (tree/node-enum-first enum)
            acc  [(node/-k node) (nth (node/-v node) vi)]]
        (if (pos? vi)
          (.reduce ^clojure.lang.IReduceInit
            (PriorityQueueSeqReverse. enum (dec vi) nil nil) f acc)
          (if-let [e2 (tree/node-enum-prior enum)]
            (.reduce ^clojure.lang.IReduceInit
              (PriorityQueueSeqReverse. e2 (dec (count (node/-v (tree/node-enum-first e2)))) nil nil)
              f acc)
            acc)))
      (f)))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  clojure.lang.IPersistentCollection
  (empty [_] ())
  (equiv [this o]
    (seq-equiv this o))

  Object
  (hashCode [this]
    (clojure.lang.Util/hash this))
  (equals [this o]
    (clojure.lang.Util/equals this o))

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (PriorityQueueSeqReverse. enum vi cnt m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Fields:
;;   root — weight-balanced tree mapping priority -> [values...] vector
;;   cmp  — java.util.Comparator for priority ordering (user's comparator directly)
;;   cnt  — total element count (sum of all value vector lengths)
;;   _meta — metadata map
;;
;; Duplicate priorities append to the value vector via conj, preserving
;; insertion order without synthetic seqnum. The public API expands tree
;; entries into [priority value] pairs; the vector grouping is hidden.

(deftype PriorityQueue [root ^Comparator cmp ^long cnt _meta]

  java.io.Serializable

  INodeCollection
  (getAllocator [_] nil)
  (getRoot [_] root)

  IOrderedCollection
  (getCmp [_] cmp)
  (isCompatible [_ o]
    (and (instance? PriorityQueue o)
         (= cmp (.getCmp ^IOrderedCollection o))))
  (isSimilar [_ _] false)

  IBalancedCollection
  (getStitch [_] nil)

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (PriorityQueue. root cmp cnt m))

  clojure.lang.IPersistentStack
  (peek [_]
    (when-not (node/leaf? root)
      (let [n (tree/node-least root)]
        [(node/-k n) (first (node/-v n))])))
  (pop [_]
    (if (node/leaf? root)
      (throw (IllegalStateException. "Can't pop empty queue"))
      (let [n    (tree/node-least root)
            p    (node/-k n)
            vals (node/-v n)
            new-root (if (> (count vals) 1)
                       (tree/node-add root p (subvec vals 1) cmp tree/node-create-weight-balanced)
                       (tree/node-remove root p cmp tree/node-create-weight-balanced))]
        (PriorityQueue. new-root cmp (dec cnt) _meta))))
  (cons [_ x]
    (let [[p v] x
          existing (tree/node-find-val root p not-found-sentinel cmp)
          new-vals (if (identical? existing not-found-sentinel) [v] (conj existing v))
          new-root (tree/node-add root p new-vals cmp tree/node-create-weight-balanced)]
      (PriorityQueue. new-root cmp (inc cnt) _meta)))

  clojure.lang.Seqable
  (seq [_]
    (when-let [e (tree/node-enumerator root nil)]
      (PriorityQueueSeq. e 0 cnt nil)))

  clojure.lang.Reversible
  (rseq [_]
    (when-let [e (tree/node-enumerator-reverse root)]
      (let [vals (node/-v (tree/node-enum-first e))]
        (PriorityQueueSeqReverse. e (dec (count vals)) cnt nil))))

  clojure.lang.Counted
  (count [_] cnt)

  clojure.lang.IPersistentCollection
  (empty [_]
    (PriorityQueue. (node/leaf) cmp 0 _meta))
  (equiv [this o]
    (cond
      (identical? this o) true
      (not (instance? clojure.lang.Counted o)) false
      (not= cnt (.count ^clojure.lang.Counted o)) false
      :else (= (seq this) (seq o))))

  clojure.lang.Indexed
  (nth [this i]
    (loop [s (seq this) j (long i)]
      (cond
        (nil? s) (throw (IndexOutOfBoundsException. (str "Index: " i)))
        (zero? j) (first s)
        :else (recur (next s) (dec j)))))
  (nth [this i not-found]
    (if (and (>= i 0) (< i cnt))
      (.nth this i)
      not-found))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (tree/node-reduce
      (fn [acc n]
        (let [p    (node/-k n)
              vals (node/-v n)]
          (reduce (fn [a v]
                    (let [ret (f a [p v])]
                      (if (reduced? ret) (reduced ret) ret)))
                  acc vals)))
      init root))

  clojure.lang.IReduce
  (reduce [this f]
    (if (node/leaf? root)
      (f)
      (let [s (seq this)]
        (if s
          (reduce f (first s) (rest s))
          (f)))))

  clojure.core.protocols/CollReduce
  (coll-reduce [this f]
    (.reduce ^clojure.lang.IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^clojure.lang.IReduceInit this f init))

  clojure.core.reducers.CollFold
  (coll-fold [this chunk-size combinef reducef]
    (with-compare this
      (tree/node-fold chunk-size root combinef
        (fn [acc node]
          (let [p    (node/-k node)
                vals (node/-v node)]
            (reduce (fn [a v] (reducef a [p v])) acc vals))))))

  java.lang.Iterable
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  Object
  (toString [this]
    (pr-str this))
  (hashCode [this]
    (.hasheq this))
  (equals [this o]
    (and (instance? PriorityQueue o)
         (.equiv this o)))

  proto/PPriorityQueue
  (push [_ priority value]
    (let [existing (tree/node-find-val root priority not-found-sentinel cmp)
          new-vals (if (identical? existing not-found-sentinel) [value] (conj existing value))
          new-root (tree/node-add root priority new-vals cmp tree/node-create-weight-balanced)]
      (PriorityQueue. new-root cmp (inc cnt) _meta)))
  (push-all [this pairs]
    (reduce (fn [q [p v]] (proto/push q p v)) this pairs))
  (peek-min [_]
    (when-not (node/leaf? root)
      (let [n (tree/node-least root)]
        [(node/-k n) (first (node/-v n))])))
  (peek-min-val [_]
    (when-not (node/leaf? root)
      (first (node/-v (tree/node-least root)))))
  (pop-min [this]
    (if (node/leaf? root)
      this
      (let [n    (tree/node-least root)
            p    (node/-k n)
            vals (node/-v n)
            new-root (if (> (count vals) 1)
                       (tree/node-add root p (subvec vals 1) cmp tree/node-create-weight-balanced)
                       (tree/node-remove root p cmp tree/node-create-weight-balanced))]
        (PriorityQueue. new-root cmp (dec cnt) _meta))))
  (peek-max [_]
    (when-not (node/leaf? root)
      (let [n (tree/node-greatest root)]
        [(node/-k n) (peek (node/-v n))])))
  (peek-max-val [_]
    (when-not (node/leaf? root)
      (peek (node/-v (tree/node-greatest root)))))
  (pop-max [this]
    (if (node/leaf? root)
      this
      (let [n    (tree/node-greatest root)
            p    (node/-k n)
            vals (node/-v n)
            new-root (if (> (count vals) 1)
                       (tree/node-add root p (pop vals) cmp tree/node-create-weight-balanced)
                       (tree/node-remove root p cmp tree/node-create-weight-balanced))]
        (PriorityQueue. new-root cmp (dec cnt) _meta)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Extended API (delegate to protocol)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn push
  "Add an element to the priority queue with the given priority.
  Returns a new queue. O(log n).

  Example:
    (push pq 1 :urgent)"
  [pq priority value]
  (proto/push pq priority value))

(defn push-all
  "Add multiple [priority value] pairs to the queue. O(k log n).

  Example:
    (push-all pq [[1 :urgent] [5 :low] [2 :medium]])"
  [pq pairs]
  (proto/push-all pq pairs))

(defn peek-min
  "Return the first [priority value] in queue order, or nil if empty. O(log n)."
  [pq]
  (proto/peek-min pq))

(defn peek-min-val
  "Return just the value of the first element in queue order, or nil if empty. O(log n)."
  [pq]
  (proto/peek-min-val pq))

(defn pop-min
  "Remove the first element in queue order. O(log n).
  Returns the queue unchanged if empty."
  [pq]
  (proto/pop-min pq))

(defn peek-max
  "Return the last [priority value] in queue order, or nil if empty. O(log n)."
  [pq]
  (proto/peek-max pq))

(defn peek-max-val
  "Return just the value of the last element in queue order, or nil if empty. O(log n)."
  [pq]
  (proto/peek-max-val pq))

(defn pop-max
  "Remove the last element in queue order. O(log n).
  Returns the queue unchanged if empty."
  [pq]
  (proto/pop-max pq))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn priority-queue
  "Create a priority queue from [priority value] pairs.
  Priorities are ordered by clojure.core/compare (natural ordering).
  For custom ordering, use priority-queue-by or priority-queue-with.

  Examples:
    (priority-queue)
    (priority-queue [[1 :a] [3 :c] [2 :b]])
    (priority-queue [[\"beta\" :b] [\"alpha\" :a]])"
  ([] (PriorityQueue. (node/leaf) order/normal-compare 0 {}))
  ([pairs]
   (push-all (priority-queue) pairs)))

(defn priority-queue-by
  "Create a priority queue with custom ordering via a predicate.

  Examples:
    (priority-queue-by > [[1 :a] [3 :c] [2 :b]])   ; max-heap
    (priority-queue-by > [])"
  [pred pairs]
  (let [cmp (order/compare-by pred)]
    (push-all (PriorityQueue. (node/leaf) cmp 0 {}) pairs)))

(defn priority-queue-with
  "Create a priority queue with a custom java.util.Comparator for priorities.

  Examples:
    (priority-queue-with long-compare [[1 :a] [2 :b]])
    (priority-queue-with string-compare)"
  ([^Comparator comparator]
   (PriorityQueue. (node/leaf) comparator 0 {}))
  ([^Comparator comparator pairs]
   (push-all (PriorityQueue. (node/leaf) comparator 0 {}) pairs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method PriorityQueue [^PriorityQueue pq ^java.io.Writer w]
  (if (order/default-comparator? (.cmp pq))
    (do (.write w "#ordered/priority-queue [")
        (when-let [s (seq pq)]
          (print-method (first s) w)
          (doseq [x (rest s)]
            (.write w " ")
            (print-method x w)))
        (.write w "]"))
    (do (.write w "#<PriorityQueue [")
        (when-let [s (seq pq)]
          (print-method (first s) w)
          (doseq [x (rest s)]
            (.write w " ")
            (print-method x w)))
        (.write w "]>"))))
