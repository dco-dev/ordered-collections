(ns ordered-collections.types.priority-queue
  "Persistent priority queue implemented using weight-balanced trees.

  A priority queue maps priorities to values. Each element is a [priority value]
  pair. The queue maintains elements ordered by the configured priority
  comparator, with O(log n) push, peek, and pop operations.

  Unlike ordered-map, allows duplicate priorities (elements are distinguished
  by insertion order via an internal sequence counter for stability in forward
  queue order)."
  (:require [clojure.core.reducers :as r :refer [coll-fold]]
            [ordered-collections.tree.node     :as node]
            [ordered-collections.tree.order    :as order]
            [ordered-collections.protocol      :as proto]
            [ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang RT Murmur3]
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
  then by seqnum (for stable ordering of equal priorities in forward queue order)."
  ^Comparator [^Comparator priority-cmp]
  (->PriorityQueueComparator priority-cmp))

(defn- pq-entry
  [[p _ v]]
  [p v])

(defn- seq-equiv
  "Compare this queue's ordered view to another sequential collection."
  [s1 o]
  (if-not (or (instance? clojure.lang.Sequential o)
              (instance? java.util.List o)
              (and (instance? clojure.lang.Seqable o)
                   (not (map? o))
                   (not (set? o))))
    false
    (loop [s1 (seq s1) s2 (seq o)]
      (cond
        (nil? s1) (nil? s2)
        (nil? s2) false
        (not (clojure.lang.Util/equiv (first s1) (first s2))) false
        :else (recur (next s1) (next s2))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Direct Seq Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The queue exposes [priority value] pairs, but stores [priority seqnum value]
;; triples internally. These seq types adapt tree enumerators directly so queue
;; traversal has the same low-overhead shape as the other collection types.

(deftype PriorityQueueSeq [enum cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (pq-entry (node/-k (tree/node-enum-first enum))))
  (next [_]
    (when-let [e (tree/node-enum-rest enum)]
      (PriorityQueueSeq. e (when cnt (unchecked-dec-int cnt)) nil)))
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
      (loop [e enum n 0]
        (if e
          (recur (tree/node-enum-rest e) (unchecked-inc-int n))
          n))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum acc init]
      (if e
        (let [ret (f acc (pq-entry (node/-k (tree/node-enum-first e))))]
          (if (reduced? ret)
            @ret
            (recur (tree/node-enum-rest e) ret)))
        acc)))

  clojure.lang.IReduce
  (reduce [_ f]
    (if enum
      (loop [e   (tree/node-enum-rest enum)
             acc (pq-entry (node/-k (tree/node-enum-first enum)))]
        (if e
          (let [ret (f acc (pq-entry (node/-k (tree/node-enum-first e))))]
            (if (reduced? ret)
              @ret
              (recur (tree/node-enum-rest e) ret)))
          acc))
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
    (PriorityQueueSeq. enum cnt m)))

(deftype PriorityQueueSeqReverse [enum cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (pq-entry (node/-k (tree/node-enum-first enum))))
  (next [_]
    (when-let [e (tree/node-enum-prior enum)]
      (PriorityQueueSeqReverse. e (when cnt (unchecked-dec-int cnt)) nil)))
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
      (loop [e enum n 0]
        (if e
          (recur (tree/node-enum-prior e) (unchecked-inc-int n))
          n))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum acc init]
      (if e
        (let [ret (f acc (pq-entry (node/-k (tree/node-enum-first e))))]
          (if (reduced? ret)
            @ret
            (recur (tree/node-enum-prior e) ret)))
        acc)))

  clojure.lang.IReduce
  (reduce [_ f]
    (if enum
      (loop [e   (tree/node-enum-prior enum)
             acc (pq-entry (node/-k (tree/node-enum-first enum)))]
        (if e
          (let [ret (f acc (pq-entry (node/-k (tree/node-enum-first e))))]
            (if (reduced? ret)
              @ret
              (recur (tree/node-enum-prior e) ret)))
          acc))
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
    (PriorityQueueSeqReverse. enum cnt m)))

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
    (when-let [e (tree/node-enumerator root nil)]
      (PriorityQueueSeq. e (tree/node-size root) nil)))

  clojure.lang.Reversible
  (rseq [_]
    (when-let [e (tree/node-enumerator-reverse root)]
      (PriorityQueueSeqReverse. e (tree/node-size root) nil)))

  clojure.lang.Counted
  (count [_]
    (tree/node-size root))

  clojure.lang.IPersistentCollection
  (empty [_]
    (PriorityQueue. (node/leaf) cmp 0 _meta))
  (equiv [this o]
    (seq-equiv this o))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (tree/node-reduce
      (fn [acc n]
        (let [[p _ v] (node/-k n)]
          (f acc [p v])))
      init root))

  clojure.lang.IReduce
  (reduce [_ f]
    (if (node/leaf? root)
      (f)
      (let [e   (tree/node-enumerator root nil)
            [p _ v] (node/-k (tree/node-enum-first e))
            acc [p v]
            e   (tree/node-enum-rest e)]
        (loop [e e, acc acc]
          (if (nil? e)
            acc
            (let [[p _ v] (node/-k (tree/node-enum-first e))
                  res     (f acc [p v])]
              (if (reduced? res)
                @res
                (recur (tree/node-enum-rest e) res))))))))

  clojure.core.protocols/CollReduce
  (coll-reduce [this f]
    (.reduce ^clojure.lang.IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^clojure.lang.IReduceInit this f init))

  clojure.core.reducers.CollFold
  (coll-fold [_ chunk-size combinef reducef]
    (tree/node-fold chunk-size root combinef
      (fn [acc n]
        (let [[p _ v] (node/-k n)]
          (reducef acc [p v])))))

  clojure.lang.Indexed
  (nth [_ i]
    (let [[p _ v] (node/-k (tree/node-nth root i))]
      [p v]))
  (nth [_ i not-found]
    (if (and (>= i 0) (< i (tree/node-size root)))
      (let [[p _ v] (node/-k (tree/node-nth root i))]
        [p v])
      not-found))

  java.lang.Iterable
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))

  clojure.lang.IHashEq
  (hasheq [this]
    ;; Hash as an ordered collection of [priority value] pairs
    (Murmur3/hashOrdered this))

  Object
  (toString [this]
    (str "#PriorityQueue" (vec (seq this))))
  (hashCode [this]
    (.hasheq this))
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
  (peek-min [_]
    (when-not (node/leaf? root)
      (let [[p _ v] (node/-k (tree/node-least root))]
        [p v])))
  (peek-min-val [_]
    (when-not (node/leaf? root)
      (let [[_ _ v] (node/-k (tree/node-least root))]
        v)))
  (pop-min [this]
    (if (node/leaf? root)
      this
      (let [least (tree/node-least root)
            new-root (tree/node-remove root (node/-k least) cmp tree/node-create-weight-balanced)]
        (PriorityQueue. new-root cmp seqnum _meta))))
  (peek-max [_]
    (when-not (node/leaf? root)
      (let [[p _ v] (node/-k (tree/node-greatest root))]
        [p v])))
  (peek-max-val [_]
    (when-not (node/leaf? root)
      (let [[_ _ v] (node/-k (tree/node-greatest root))]
        v)))
  (pop-max [this]
    (if (node/leaf? root)
      this
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

(defn peek-min
  "Return the first [priority value] in queue order, or nil if empty. O(log n).

  Queue order is determined by the priority comparator. With the default
  comparator this is the lowest priority; with `:comparator >` it is the
  highest priority."
  [pq]
  (proto/peek-min pq))

(defn peek-min-val
  "Return just the value of the first element in queue order, or nil if empty. O(log n)."
  [pq]
  (proto/peek-min-val pq))

(defn pop-min
  "Remove and return a new queue without the first element in queue order. O(log n).
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
  "Remove and return a new queue without the last element in queue order. O(log n).
  Returns the queue unchanged if empty."
  [pq]
  (proto/pop-max pq))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn priority-queue
  "Create a priority queue from [priority value] pairs.

  Options:
    :comparator - priority comparator (default: compare, natural ordering)

  Examples:
    (priority-queue [[1 :a] [3 :c] [2 :b]])           ; min-heap
    (priority-queue [[1 :a] [3 :c]] :comparator >)    ; max-heap"
  ([] (priority-queue []))
  ([pairs & {:keys [comparator]}]
   (let [base-cmp (cond
                    (nil? comparator)                order/normal-compare
                    (instance? Comparator comparator) comparator
                    :else                             (order/compare-by comparator))
         pq-cmp (make-pq-comparator base-cmp)
         empty-pq (PriorityQueue. (node/leaf) pq-cmp 0 {})]
     (push-all empty-pq pairs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method PriorityQueue [^PriorityQueue pq ^java.io.Writer w]
  (let [base-cmp (.-priority-cmp ^PriorityQueueComparator (.cmp pq))]
    (if (order/default-comparator? base-cmp)
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
          (.write w "]>")))))
