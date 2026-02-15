(ns com.dean.ordered-collections.tree.ordered-multiset
  "Persistent sorted multiset (bag) implemented using weight-balanced trees.

  Unlike ordered-set, allows duplicate elements. Elements with the same
  value are distinguished by insertion order. Supports efficient:
  - O(log n) add/remove
  - O(log n) count of specific element
  - O(log n) nth access
  - O(log n + k) range queries
  - Parallel fold"
  (:require [clojure.core.reducers :as r :refer [coll-fold]]
            [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.protocol :as proto]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang RT Murmur3]
            [java.util Comparator]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multiset Comparator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Using deftype instead of reify so comparator is serializable.

(deftype MultisetComparator [^Comparator value-cmp]
  java.io.Serializable
  Comparator
  (compare [_ a b]
    (let [[va sa] a
          [vb sb] b
          c (.compare value-cmp va vb)]
      (if (zero? c)
        (Long/compare ^long sa ^long sb)
        c)))
  Object
  (equals [_ o]
    (and (instance? MultisetComparator o)
         (.equals value-cmp (.-value-cmp ^MultisetComparator o))))
  (hashCode [_] (hash value-cmp)))

(defn- make-multiset-comparator
  "Create a comparator for multiset entries.
  Entries are [value seqnum] pairs.
  Comparison is first by value (using the user's comparator),
  then by seqnum (for distinguishing duplicates)."
  ^Comparator [^Comparator value-cmp]
  (->MultisetComparator value-cmp))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper Functions (needed by protocol impl)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- count-matching
  "Count all occurrences of x in subtree n using base comparator bc."
  [^Comparator bc n x]
  (if (node/leaf? n)
    0
    (let [[v _] (node/-k n)
          c (.compare bc x v)]
      (cond
        (neg? c) (count-matching bc (node/-l n) x)
        (pos? c) (count-matching bc (node/-r n) x)
        :else (+ 1
                (count-matching bc (node/-l n) x)
                (count-matching bc (node/-r n) x))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Multiset
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ->OrderedMultiset)

(deftype OrderedMultiset [root ^Comparator cmp ^Comparator base-cmp ^long seqnum _meta]

  java.io.Serializable

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (OrderedMultiset. root cmp base-cmp seqnum m))

  clojure.lang.Seqable
  (seq [_]
    (when-not (node/leaf? root)
      (map (fn [n] (first (node/-k n)))
           (tree/node-seq root))))

  clojure.lang.Reversible
  (rseq [_]
    (when-not (node/leaf? root)
      (map (fn [n] (first (node/-k n)))
           (tree/node-seq-reverse root))))

  clojure.lang.Counted
  (count [_]
    (tree/node-size root))

  clojure.lang.Indexed
  (nth [_ i]
    (first (node/-k (tree/node-nth root i))))

  clojure.lang.ILookup
  (valAt [this k not-found]
    ;; Return first occurrence of k, or not-found
    (let [^Comparator bc base-cmp]
      (loop [n root]
        (if (node/leaf? n)
          not-found
          (let [[v _] (node/-k n)
                c (.compare bc k v)]
            (cond
              (neg? c) (recur (node/-l n))
              (pos? c) (recur (node/-r n))
              :else    v))))))
  (valAt [this k]
    (.valAt this k nil))

  clojure.lang.IFn
  (invoke [this k]
    (.valAt this k))
  (invoke [this k not-found]
    (.valAt this k not-found))
  (applyTo [this args]
    (let [n (RT/boundedLength args 2)]
      (case n
        0 (throw (clojure.lang.ArityException. n (.. this (getClass) (getSimpleName))))
        1 (.invoke this (first args))
        2 (.invoke this (first args) (second args))
        3 (throw (clojure.lang.ArityException. n (.. this (getClass) (getSimpleName)))))))

  clojure.lang.IPersistentCollection
  (cons [this k]
    (let [entry [k seqnum]
          new-root (tree/node-add root entry entry cmp tree/node-create-weight-balanced)]
      (OrderedMultiset. new-root cmp base-cmp (unchecked-inc seqnum) _meta)))
  (empty [_]
    (OrderedMultiset. (node/leaf) cmp base-cmp 0 {}))
  (equiv [this o]
    (cond
      (identical? this o) true
      (instance? OrderedMultiset o)
      (and (= (count this) (count o))
           (= (seq this) (seq o)))
      (coll? o)
      (and (= (count this) (count o))
           (= (seq this) (seq o)))
      :else false))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (tree/node-reduce
      (fn [acc n] (f acc (first (node/-k n))))
      init root))

  clojure.lang.IReduce
  (reduce [_ f]
    (let [sentinel (Object.)
          result (tree/node-reduce
                   (fn [acc n]
                     (let [v (first (node/-k n))]
                       (if (identical? acc sentinel)
                         v
                         (f acc v))))
                   sentinel root)]
      (if (identical? result sentinel) (f) result)))

  clojure.core.reducers.CollFold
  (coll-fold [_ chunk-size combinef reducef]
    (tree/node-chunked-fold chunk-size root combinef
      (fn [acc n] (reducef acc (first (node/-k n))))))

  clojure.lang.IHashEq
  (hasheq [_]
    ;; Multiset hash: sum of hasheq of all elements (order-independent)
    (tree/node-reduce
      (fn [^long acc n]
        (unchecked-add acc (Murmur3/hashInt (clojure.lang.Util/hasheq (first (node/-k n))))))
      (long 0)
      root))

  java.lang.Comparable
  (compareTo [this o]
    (if (instance? OrderedMultiset o)
      (compare (vec (seq this)) (vec (seq o)))
      (throw (ex-info "Cannot compare OrderedMultiset to non-multiset" {:other o}))))

  java.util.Collection
  (toArray [_]
    (object-array (map (fn [n] (first (node/-k n))) (tree/node-seq root))))
  (isEmpty [_]
    (node/leaf? root))
  (size [_]
    (tree/node-size root))
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))
  (add [_ _]
    (throw (UnsupportedOperationException.)))
  (addAll [_ _]
    (throw (UnsupportedOperationException.)))
  (remove [_ _]
    (throw (UnsupportedOperationException.)))
  (removeAll [_ _]
    (throw (UnsupportedOperationException.)))
  (retainAll [_ _]
    (throw (UnsupportedOperationException.)))
  (clear [_]
    (throw (UnsupportedOperationException.)))
  (contains [this x]
    (not= ::not-found (.valAt this x ::not-found)))
  (containsAll [this coll]
    (every? #(.contains this %) coll))

  Object
  (toString [this]
    (str "#OrderedMultiset" (vec (seq this))))
  (hashCode [this]
    (.hasheq this))
  (equals [this o]
    (.equiv this o))

  proto/PMultiset
  (multiplicity [_ x]
    (count-matching base-cmp root x))
  (disj-one [this x]
    ;; Find first occurrence and remove it
    (loop [n root]
      (if (node/leaf? n)
        this  ; not found
        (let [[v _ :as entry] (node/-k n)
              c (.compare base-cmp x v)]
          (cond
            (neg? c) (recur (node/-l n))
            (pos? c) (recur (node/-r n))
            :else    ;; Found, remove this entry
            (let [new-root (tree/node-remove root entry cmp tree/node-create-weight-balanced)]
              (OrderedMultiset. new-root cmp base-cmp seqnum _meta)))))))
  (disj-all [this x]
    (loop [m this]
      (if (.contains ^java.util.Collection m x)
        (recur (proto/disj-one m x))
        m)))
  (distinct-elements [_]
    (when-not (node/leaf? root)
      (distinct (map (fn [n] (first (node/-k n))) (tree/node-seq root)))))
  (element-frequencies [this]
    (frequencies (seq this))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Extended API (delegate to protocol)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn multiplicity
  "Return the number of occurrences of x in the multiset. O(log n + k)."
  [ms x]
  (proto/multiplicity ms x))

(defn disj-one
  "Remove one occurrence of x from the multiset. O(log n).
  Returns the same multiset if x is not present."
  [ms x]
  (proto/disj-one ms x))

(defn disj-all
  "Remove all occurrences of x from the multiset. O(k log n) where k is multiplicity."
  [ms x]
  (proto/disj-all ms x))

(defn distinct-elements
  "Return a lazy seq of distinct elements in the multiset, in sorted order."
  [ms]
  (proto/distinct-elements ms))

(defn element-frequencies
  "Return a map of {element -> count} for all elements. O(n)."
  [ms]
  (proto/element-frequencies ms))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ordered-multiset
  "Create an ordered multiset from a collection.
  Elements are sorted by natural order (clojure.core/compare).
  Duplicates are allowed.

  Example:
    (ordered-multiset [3 1 4 1 5 9 2 6 5 3 5])
    ;; => #OrderedMultiset[1 1 2 3 3 4 5 5 5 6 9]"
  [coll]
  (let [base-cmp order/normal-compare
        ms-cmp (make-multiset-comparator base-cmp)
        empty-ms (OrderedMultiset. (node/leaf) ms-cmp base-cmp 0 {})]
    (into empty-ms coll)))

(defn ordered-multiset-by
  "Create an ordered multiset with a custom comparator.

  Example:
    (ordered-multiset-by > [3 1 4 1 5])
    ;; => #OrderedMultiset[5 4 3 1 1]"
  [comparator coll]
  (let [base-cmp (if (instance? Comparator comparator)
                   comparator
                   (order/compare-by comparator))
        ms-cmp (make-multiset-comparator base-cmp)
        empty-ms (OrderedMultiset. (node/leaf) ms-cmp base-cmp 0 {})]
    (into empty-ms coll)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Print Method
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method OrderedMultiset [^OrderedMultiset ms ^java.io.Writer w]
  (.write w "#OrderedMultiset[")
  (when-let [s (seq ms)]
    (print-method (first s) w)
    (doseq [x (rest s)]
      (.write w " ")
      (print-method x w)))
  (.write w "]"))
