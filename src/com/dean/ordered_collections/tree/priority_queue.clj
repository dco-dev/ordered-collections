(ns com.dean.ordered-collections.tree.priority-queue
  "Persistent priority queue implemented using weight-balanced trees.

  A priority queue maps priorities to values. Each element is a [priority value]
  pair. The queue maintains elements ordered by priority, with O(log n) push,
  peek, and pop operations.

  Unlike ordered-map, allows duplicate priorities (elements are distinguished
  by insertion order via an internal sequence counter for stability)."
  (:require [clojure.core.reducers :as r :refer [coll-fold]]
            [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.protocol :as proto]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang RT]
            [java.util Comparator]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue Comparator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Using deftype instead of reify so comparator is serializable.

(deftype PriorityQueueComparator [^Comparator priority-cmp]
  java.io.Serializable
  Comparator
  (compare [_ a b]
    (let [[pa sa _] a
          [pb sb _] b
          c (.compare priority-cmp pa pb)]
      (if (zero? c)
        (Long/compare ^long sa ^long sb)
        c)))
  Object
  (equals [_ o]
    (and (instance? PriorityQueueComparator o)
         (.equals priority-cmp (.-priority-cmp ^PriorityQueueComparator o))))
  (hashCode [_] (hash priority-cmp)))

(defn- make-pq-comparator
  "Create a comparator for priority queue entries.
  Entries are [priority seqnum value] triples.
  Comparison is first by priority (using the user's comparator),
  then by seqnum (for stable ordering of equal priorities)."
  ^Comparator [^Comparator priority-cmp]
  (->PriorityQueueComparator priority-cmp))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype PriorityQueue [root ^Comparator cmp ^long seqnum _meta]

  java.io.Serializable

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (PriorityQueue. root cmp seqnum m))

  clojure.lang.IPersistentStack
  (peek [_]
    ;; Return the minimum element (by priority) as [priority value]
    (when-not (node/leaf? root)
      (let [[p _ v] (node/-k (tree/node-least root))]
        [p v])))
  (pop [this]
    (if (node/leaf? root)
      (throw (IllegalStateException. "Can't pop empty queue"))
      (let [least (tree/node-least root)
            new-root (tree/node-remove root (node/-k least) cmp tree/node-create-weight-balanced)]
        (PriorityQueue. new-root cmp seqnum _meta))))
  (cons [this x]
    ;; x must be [priority value] pair
    (let [[p v] x
          entry [p seqnum v]
          new-root (tree/node-add root entry entry cmp tree/node-create-weight-balanced)]
      (PriorityQueue. new-root cmp (unchecked-inc seqnum) _meta)))

  clojure.lang.Seqable
  (seq [_]
    (when-not (node/leaf? root)
      (map (fn [n] (let [[p _ v] (node/-k n)] [p v]))
           (tree/node-seq root))))

  clojure.lang.Reversible
  (rseq [_]
    (when-not (node/leaf? root)
      (map (fn [n] (let [[p _ v] (node/-k n)] [p v]))
           (tree/node-seq-reverse root))))

  clojure.lang.Counted
  (count [_]
    (tree/node-size root))

  clojure.lang.IPersistentCollection
  (empty [_]
    (PriorityQueue. (node/leaf) cmp 0 {}))
  (equiv [this o]
    (and (instance? PriorityQueue o)
         (= (count this) (count o))
         (= (seq this) (seq o))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (tree/node-reduce
      (fn [acc n]
        (let [[p _ v] (node/-k n)]
          (f acc [p v])))
      init root))

  clojure.lang.IReduce
  (reduce [_ f]
    (let [sentinel (Object.)
          result (tree/node-reduce
                   (fn [acc n]
                     (let [[p _ v] (node/-k n)]
                       (if (identical? acc sentinel)
                         [p v]
                         (f acc [p v]))))
                   sentinel root)]
      (if (identical? result sentinel) (f) result)))

  clojure.core.reducers.CollFold
  (coll-fold [_ chunk-size combinef reducef]
    (tree/node-chunked-fold chunk-size root combinef
      (fn [acc n]
        (let [[p _ v] (node/-k n)]
          (reducef acc [p v])))))

  clojure.lang.Indexed
  (nth [_ i]
    (let [[p _ v] (node/-k (tree/node-nth root i))]
      [p v]))

  java.lang.Iterable
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))

  Object
  (toString [this]
    (str "#PriorityQueue" (vec (seq this))))
  (hashCode [this]
    (.hashCode ^Object (vec (seq this))))
  (equals [this o]
    (and (instance? PriorityQueue o)
         (.equiv this o)))

  proto/PPriorityQueue
  (push [_ priority value]
    (let [entry [priority seqnum value]
          new-root (tree/node-add root entry entry cmp tree/node-create-weight-balanced)]
      (PriorityQueue. new-root cmp (unchecked-inc seqnum) _meta)))
  (push-all [this pairs]
    (reduce (fn [q [p v]] (proto/push q p v)) this pairs))
  (peek-val [_]
    (when-not (node/leaf? root)
      (let [[_ _ v] (node/-k (tree/node-least root))]
        v)))
  (peek-max [_]
    (when-not (node/leaf? root)
      (let [[p _ v] (node/-k (tree/node-greatest root))]
        [p v])))
  (peek-max-val [_]
    (when-not (node/leaf? root)
      (let [[_ _ v] (node/-k (tree/node-greatest root))]
        v)))
  (pop-max [_]
    (if (node/leaf? root)
      (throw (IllegalStateException. "Can't pop-max empty queue"))
      (let [greatest (tree/node-greatest root)
            new-root (tree/node-remove root (node/-k greatest) cmp tree/node-create-weight-balanced)]
        (PriorityQueue. new-root cmp seqnum _meta)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Extended API (delegate to protocol)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn push
  "Add an element to the priority queue with the given priority.
  Returns a new queue. O(log n).

  Example:
    (push pq 1 :urgent)  ; priority 1, value :urgent"
  [pq priority value]
  (proto/push pq priority value))

(defn push-all
  "Add multiple [priority value] pairs to the queue. O(k log n).

  Example:
    (push-all pq [[1 :urgent] [5 :low] [2 :medium]])"
  [pq pairs]
  (proto/push-all pq pairs))

(defn peek-val
  "Return just the value of the minimum element, or nil if empty. O(log n)."
  [pq]
  (proto/peek-val pq))

(defn peek-max
  "Return [priority value] of the maximum element, or nil if empty. O(log n)."
  [pq]
  (proto/peek-max pq))

(defn peek-max-val
  "Return just the value of the maximum element, or nil if empty. O(log n)."
  [pq]
  (proto/peek-max-val pq))

(defn pop-max
  "Remove and return a new queue without the maximum-priority element. O(log n)."
  [pq]
  (proto/pop-max pq))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn priority-queue
  "Create a priority queue from [priority value] pairs.

  Options:
    :comparator - priority comparator (default: < for min-heap)

  Examples:
    (priority-queue [[1 :a] [3 :c] [2 :b]])           ; min-heap
    (priority-queue [[1 :a] [3 :c]] :comparator >)    ; max-heap"
  [pairs & {:keys [comparator] :or {comparator clojure.core/compare}}]
  (let [base-cmp (if (instance? Comparator comparator)
                   comparator
                   (order/compare-by comparator))
        pq-cmp (make-pq-comparator base-cmp)
        empty-pq (PriorityQueue. (node/leaf) pq-cmp 0 {})]
    (push-all empty-pq pairs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Print Method
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method PriorityQueue [^PriorityQueue pq ^java.io.Writer w]
  (.write w "#PriorityQueue[")
  (when-let [s (seq pq)]
    (print-method (first s) w)
    (doseq [x (rest s)]
      (.write w " ")
      (print-method x w)))
  (.write w "]"))
