(ns ordered-collections.types.ordered-multiset
  "Persistent sorted multiset (bag) backed by a weight-balanced tree.

  Internally an ordered map from elements to counts (longs). The public
  API expands each entry into repeated elements: an element with count 3
  appears three times in seq, reduce, and nth."
  (:require [clojure.core.reducers :as r :refer [coll-fold]]
            [ordered-collections.types.shared :refer [with-compare]]
            [ordered-collections.tree.node     :as node]
            [ordered-collections.tree.order    :as order]
            [ordered-collections.protocol      :as proto]
            [ordered-collections.tree.root]
            [ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang RT Murmur3]
            [java.util Comparator]
            [ordered_collections.tree.root INodeCollection
                                           IBalancedCollection
                                           IOrderedCollection]))


(def ^:private not-found-sentinel ::not-found)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Expanding Seq Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The tree stores [element -> count] entries. These seq types expand each
;; node into `count` repetitions of the element, walking the tree via
;; enumerator and the repetition count via an index.

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


(deftype MultisetSeq [enum ^long ri cnt _meta]
  ;; enum = tree enumerator, ri = repetition index (0-based into current count)

  clojure.lang.ISeq
  (first [_]
    (node/-k (tree/node-enum-first enum)))
  (next [_]
    (let [node (tree/node-enum-first enum)
          c    (long (node/-v node))]
      (if (< (inc ri) c)
        (MultisetSeq. enum (inc ri) (when cnt (unchecked-dec-int cnt)) nil)
        (when-let [e (tree/node-enum-rest enum)]
          (MultisetSeq. e 0 (when cnt (unchecked-dec-int cnt)) nil)))))
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
      (loop [e enum, ri ri, n 0]
        (if (nil? e)
          n
          (let [c (long (node/-v (tree/node-enum-first e)))
                remaining (- c ri)]
            (recur (tree/node-enum-rest e) 0 (+ n remaining)))))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum, ri (long ri), acc init]
      (if (nil? e)
        acc
        (let [node (tree/node-enum-first e)
              elem (node/-k node)
              c    (long (node/-v node))]
          (let [acc (loop [i ri, acc acc]
                      (if (>= i c)
                        acc
                        (let [ret (f acc elem)]
                          (if (reduced? ret)
                            ret
                            (recur (inc i) ret)))))]
            (if (reduced? acc)
              @acc
              (recur (tree/node-enum-rest e) 0 acc)))))))

  clojure.lang.IReduce
  (reduce [this f]
    (if enum
      (let [acc (node/-k (tree/node-enum-first enum))]
        (let [node (tree/node-enum-first enum)
              c    (long (node/-v node))
              remaining-start (inc ri)]
          (let [[acc e] (loop [i remaining-start, acc acc]
                          (if (>= i c)
                            [acc (tree/node-enum-rest enum)]
                            (let [ret (f acc (node/-k node))]
                              (if (reduced? ret)
                                [ret nil]
                                (recur (inc i) ret)))))]
            (if (or (reduced? acc) (nil? e))
              (if (reduced? acc) @acc acc)
              (.reduce ^clojure.lang.IReduceInit
                (MultisetSeq. e 0 nil nil) f acc)))))
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
    (MultisetSeq. enum ri cnt m)))

(deftype MultisetSeqReverse [enum ^long ri cnt _meta]
  ;; enum = reverse tree enumerator, ri = repetition index (counting down from count-1)

  clojure.lang.ISeq
  (first [_]
    (node/-k (tree/node-enum-first enum)))
  (next [_]
    (if (pos? ri)
      (MultisetSeqReverse. enum (dec ri) (when cnt (unchecked-dec-int cnt)) nil)
      (when-let [e (tree/node-enum-prior enum)]
        (let [c (long (node/-v (tree/node-enum-first e)))]
          (MultisetSeqReverse. e (dec c) (when cnt (unchecked-dec-int cnt)) nil)))))
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
      (loop [e enum, ri ri, n 0]
        (if (nil? e)
          n
          (let [remaining (inc ri)]
            (recur (tree/node-enum-prior e)
                   (if-let [e2 (tree/node-enum-prior e)]
                     (dec (long (node/-v (tree/node-enum-first e2))))
                     0)
                   (+ n remaining)))))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum, ri (long ri), acc init]
      (if (nil? e)
        acc
        (let [elem (node/-k (tree/node-enum-first e))]
          (let [acc (loop [i ri, acc acc]
                      (if (neg? i)
                        acc
                        (let [ret (f acc elem)]
                          (if (reduced? ret)
                            ret
                            (recur (dec i) ret)))))]
            (if (reduced? acc)
              @acc
              (if-let [e2 (tree/node-enum-prior e)]
                (recur e2 (dec (long (node/-v (tree/node-enum-first e2)))) acc)
                acc)))))))

  clojure.lang.IReduce
  (reduce [this f]
    (if enum
      (let [acc (node/-k (tree/node-enum-first enum))]
        (if (pos? ri)
          (.reduce ^clojure.lang.IReduceInit
            (MultisetSeqReverse. enum (dec ri) nil nil) f acc)
          (if-let [e2 (tree/node-enum-prior enum)]
            (.reduce ^clojure.lang.IReduceInit
              (MultisetSeqReverse. e2 (dec (long (node/-v (tree/node-enum-first e2)))) nil nil)
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
    (MultisetSeqReverse. enum ri cnt m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Multiset
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Fields:
;;   root — weight-balanced tree mapping element -> count (long)
;;   cmp  — java.util.Comparator for element ordering (user's comparator directly)
;;   cnt  — total element count (sum of all counts)
;;   _meta — metadata map
;;
;; No seqnum, no synthetic comparator. Duplicate elements increment the
;; count stored at that key. The public API expands counts into repeated
;; elements in seq/reduce/nth.

(deftype OrderedMultiset [root ^Comparator cmp ^long cnt _meta]

  java.io.Serializable

  INodeCollection
  (getAllocator [_] nil)
  (getRoot [_] root)

  IOrderedCollection
  (getCmp [_] cmp)
  (isCompatible [_ o]
    (and (instance? OrderedMultiset o)
         (= cmp (.getCmp ^IOrderedCollection o))))
  (isSimilar [_ _] false)

  IBalancedCollection
  (getStitch [_] nil)

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (OrderedMultiset. root cmp cnt m))

  clojure.lang.Seqable
  (seq [_]
    (when-let [e (tree/node-enumerator root nil)]
      (MultisetSeq. e 0 cnt nil)))

  clojure.lang.Reversible
  (rseq [_]
    (when-let [e (tree/node-enumerator-reverse root)]
      (let [c (long (node/-v (tree/node-enum-first e)))]
        (MultisetSeqReverse. e (dec c) cnt nil))))

  clojure.lang.Counted
  (count [_] cnt)

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

  clojure.lang.ILookup
  (valAt [_ k not-found]
    (let [result (tree/node-find-val root k not-found-sentinel cmp)]
      (if (identical? result not-found-sentinel)
        not-found
        k)))
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
  (cons [_ x]
    (let [existing (tree/node-find-val root x not-found-sentinel cmp)
          new-count (if (identical? existing not-found-sentinel) 1 (inc (long existing)))
          new-root  (tree/node-add root x new-count cmp tree/node-create-weight-balanced)]
      (OrderedMultiset. new-root cmp (inc cnt) _meta)))
  (empty [_]
    (OrderedMultiset. (node/leaf) cmp 0 _meta))
  (equiv [this o]
    (cond
      (identical? this o) true
      (not (instance? clojure.lang.Counted o)) false
      (not= cnt (.count ^clojure.lang.Counted o)) false
      :else (= (seq this) (seq o))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (tree/node-reduce
      (fn [acc n]
        (let [elem (node/-k n)
              c    (long (node/-v n))]
          (loop [i (long 0), acc acc]
            (if (>= i c)
              acc
              (let [ret (f acc elem)]
                (if (reduced? ret)
                  (reduced ret)
                  (recur (inc i) ret)))))))
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
          (let [elem (node/-k node)
                c    (long (node/-v node))]
            (loop [i (long 0), acc acc]
              (if (>= i c)
                acc
                (recur (inc i) (reducef acc elem)))))))))

  java.lang.Comparable
  (compareTo [this o]
    (if (instance? OrderedMultiset o)
      (compare (vec (seq this)) (vec (seq o)))
      (throw (ex-info "Cannot compare OrderedMultiset to non-multiset" {:other o}))))

  java.util.Collection
  (toArray [this]
    (object-array (seq this)))
  (isEmpty [_]
    (node/leaf? root))
  (size [_] cnt)
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
    (not (identical? not-found-sentinel
                     (tree/node-find-val root x not-found-sentinel cmp))))
  (containsAll [this coll]
    (every? #(.contains this %) coll))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  Object
  (toString [this]
    (pr-str this))
  (hashCode [this]
    (.hasheq this))
  (equals [this o]
    (.equiv this o))

  proto/PMultiset
  (multiplicity [_ x]
    (let [result (tree/node-find-val root x not-found-sentinel cmp)]
      (if (identical? result not-found-sentinel) 0 (long result))))
  (disj-one [this x]
    (let [existing (tree/node-find-val root x not-found-sentinel cmp)]
      (if (identical? existing not-found-sentinel)
        this
        (let [c (long existing)
              new-root (if (> c 1)
                         (tree/node-add root x (dec c) cmp tree/node-create-weight-balanced)
                         (tree/node-remove root x cmp tree/node-create-weight-balanced))]
          (OrderedMultiset. new-root cmp (dec cnt) _meta)))))
  (disj-all [this x]
    (let [existing (tree/node-find-val root x not-found-sentinel cmp)]
      (if (identical? existing not-found-sentinel)
        this
        (let [c (long existing)
              new-root (tree/node-remove root x cmp tree/node-create-weight-balanced)]
          (OrderedMultiset. new-root cmp (- cnt c) _meta)))))
  (distinct-elements [_]
    (tree/node-key-seq root))
  (element-frequencies [_]
    (persistent!
      (tree/node-reduce
        (fn [acc n] (assoc! acc (node/-k n) (node/-v n)))
        (transient {})
        root))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Extended API (delegate to protocol)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn multiplicity
  "Return the number of occurrences of x in the multiset. O(log n)."
  [ms x]
  (proto/multiplicity ms x))

(defn disj-one
  "Remove one occurrence of x from the multiset. O(log n).
  Returns the same multiset if x is not present."
  [ms x]
  (proto/disj-one ms x))

(defn disj-all
  "Remove all occurrences of x from the multiset. O(log n).
  Returns the same multiset if x is not present."
  [ms x]
  (proto/disj-all ms x))

(defn distinct-elements
  "Return a seq of distinct elements in the multiset, in sorted order. O(n)."
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

  Examples:
    (ordered-multiset)
    (ordered-multiset [3 1 4 1 5 9 2 6 5 3 5])"
  ([] (OrderedMultiset. (node/leaf) order/normal-compare 0 {}))
  ([coll]
   (into (ordered-multiset) coll)))

(defn ordered-multiset-by
  "Create an ordered multiset with custom ordering via a predicate.

  Example:
    (ordered-multiset-by > [3 1 4 1 5])"
  [pred coll]
  (let [cmp (order/compare-by pred)]
    (into (OrderedMultiset. (node/leaf) cmp 0 {}) coll)))

(defn ordered-multiset-with
  "Create an ordered multiset with a custom java.util.Comparator.

  Example:
    (ordered-multiset-with long-compare [3 1 4 1 5])"
  ([^Comparator comparator]
   (OrderedMultiset. (node/leaf) comparator 0 {}))
  ([^Comparator comparator coll]
   (into (OrderedMultiset. (node/leaf) comparator 0 {}) coll)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method OrderedMultiset [^OrderedMultiset ms ^java.io.Writer w]
  (if (order/default-comparator? (.cmp ms))
    (do (.write w "#ordered/multiset [")
        (when-let [s (seq ms)]
          (print-method (first s) w)
          (doseq [x (rest s)]
            (.write w " ")
            (print-method x w)))
        (.write w "]"))
    (do (.write w "#<OrderedMultiset [")
        (when-let [s (seq ms)]
          (print-method (first s) w)
          (doseq [x (rest s)]
            (.write w " ")
            (print-method x w)))
        (.write w "]>"))))
