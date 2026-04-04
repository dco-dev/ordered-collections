(ns com.dean.ordered-collections.types.interval-map
  (:require [clojure.core.reducers       :as r :refer [coll-fold]]
            [com.dean.ordered-collections.tree.interval :as interval]
            [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.protocol      :as proto]
            [com.dean.ordered-collections.tree.root]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang                RT MapEntry Murmur3]
            [com.dean.ordered_collections.protocol PIntervalCollection PSpan]
            [com.dean.ordered_collections.tree.root     INodeCollection
                                         IBalancedCollection
                                         IOrderedCollection
                                         IIntervalCollection]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-interval-map [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.IOrderedCollection}))
             tree/*t-join*   (.getAllocator ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.INodeCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype IntervalMap [root cmp alloc stitch _meta]

  java.io.Serializable

  INodeCollection
  (getAllocator [_]
    alloc)
  (getRoot [_]
    root)

  IOrderedCollection
  (getCmp [_]
    cmp)
  (isCompatible [_ o]
    (and (instance? IntervalMap o) (= cmp (.getCmp ^IOrderedCollection o)) (= stitch (.getStitch ^IBalancedCollection o))))
  (isSimilar [_ o]
    (map? o))

  IBalancedCollection
  (getStitch [_]
    stitch)

  IIntervalCollection

  PIntervalCollection
  (overlapping [this interval]
    (with-interval-map this
      (when-let [found (seq (tree/node-find-intervals root interval))]
        (map node/-kv found))))

  PSpan
  (span [this]
    (when-not (node/leaf? root)
      [(first (node/-k (tree/node-least root))) (node/-z root)]))

  clojure.lang.IMeta
  (meta [_]
    _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (IntervalMap. root cmp alloc stitch m))

  clojure.lang.Indexed
  (nth [_ i]
    (node/-kv (tree/node-nth root i)))
  (nth [_ i not-found]
    (if (and (>= i 0) (< i (tree/node-size root)))
      (node/-kv (tree/node-nth root i))
      not-found))

  clojure.lang.MapEquivalence

  clojure.lang.Seqable
  (seq [_]
    (tree/node-entry-seq root (tree/node-size root)))

  clojure.lang.Reversible
  (rseq [_]
    (tree/node-entry-seq-reverse root (tree/node-size root)))

  clojure.lang.ILookup
  (valAt [this k not-found]
    (with-interval-map this
      (if-let [found (seq (tree/node-find-intervals root k))]
        (map node/-v found)
        not-found)))
  (valAt [this k]
    (.valAt this k nil))

  clojure.lang.IFn
  (invoke [this k not-found]
    (.valAt this k not-found))
  (invoke [this k]
    (.valAt this k))
  (applyTo [this args]
    (let [n (RT/boundedLength args 2)]
      (case n
        0 (throw (clojure.lang.ArityException. n (.. this (getClass) (getSimpleName))))
        1 (.invoke this (first args))
        2 (.invoke this (first args) (second args))
        3 (throw (clojure.lang.ArityException. n (.. this (getClass) (getSimpleName)))))))

  java.lang.Comparable
  (compareTo [this o]
    (with-interval-map this
      (cond
        (identical? this o) 0
        (.isCompatible this o) (tree/node-map-compare root (.getRoot ^INodeCollection o))
        true (throw (ex-info "unsupported comparison: " {:this this :o o})))))

  clojure.lang.Counted
  (count [this]
    (tree/node-size root))

  clojure.lang.Associative
  (containsKey [this k]
    (with-interval-map this
      (not (empty? (tree/node-find-intervals root k)))))
  (entryAt [this k]
    (with-interval-map this
      (when-let [found (seq (tree/node-find-intervals root k))]
        (node/-kv (first found)))))
  (assoc [this k v]
    (IntervalMap. (tree/node-add root (interval/ordered-pair k) v cmp alloc) cmp alloc stitch _meta))
  (empty [this]
    (IntervalMap. (node/leaf) cmp alloc stitch {}))

  java.util.Map
  (get [this k]
    (.valAt this k))
  (isEmpty [_]
    (node/leaf? root))
  (size [_]
    (tree/node-size root))
  (keySet [this]
    (with-interval-map this
      (set (tree/node-vec root :accessor :k))))
  (put [_ _ _]
    (throw (UnsupportedOperationException.)))
  (putAll [_ _]
    (throw (UnsupportedOperationException.)))
  (clear [_]
    (throw (UnsupportedOperationException.)))
  (values [this]
    (with-interval-map this
      (tree/node-vec root :accessor :v)))
  (entrySet [this]
    (with-interval-map this
      (set (tree/node-vec root :accessor :kv))))
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))

  clojure.lang.IPersistentCollection
  (equiv [this o]
    (with-interval-map this
      (cond
        (identical? this o) true
        (not (instance? clojure.lang.Counted o)) false
        (.isCompatible this o) (and (= (.count this) (.count ^clojure.lang.Counted o))
                                    (zero? (tree/node-map-compare root (.getRoot ^INodeCollection o))))
        :else false)))

  (cons [this o]
    (.assoc this (nth o 0) (nth o 1)))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (tree/node-reduce-entries f init root))

  clojure.lang.IReduce
  (reduce [this f]
    (tree/node-reduce-entries f root))

  clojure.core.protocols/CollReduce
  (coll-reduce [this f]
    (.reduce ^clojure.lang.IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^clojure.lang.IReduceInit this f init))

  clojure.lang.IKVReduce
  (kvreduce [this f init]
    (tree/node-reduce-kv f init root))

  Object
  (toString [this]
    (pr-str this))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/mixCollHash
      (unchecked-int
        (tree/node-reduce
          (fn [^long acc n]
            (unchecked-add acc (long (clojure.lang.Util/hasheq
                                       (clojure.lang.MapEntry. (node/-k n) (node/-v n))))))
          (long 0)
          root))
      (tree/node-size root)))

  clojure.core.reducers.CollFold
  (coll-fold [this n combinef reducef]
    (with-interval-map this
      (tree/node-chunked-fold n root combinef
        (fn [acc node] (reducef acc (node/-kv node))))))

  clojure.lang.IPersistentMap
  (assocEx [this k v]
    (if (contains? this k)
      (throw (RuntimeException. "Key or value already present"))
      (assoc this k v)))
  (without [this k]
    (IntervalMap. (tree/node-remove root (interval/ordered-pair k) cmp alloc) cmp alloc stitch _meta)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method IntervalMap [^IntervalMap m ^java.io.Writer w]
  (if (order/default-comparator? (.getCmp ^IOrderedCollection m))
    (do (.write w "#ordered/interval-map [")
        (let [s (seq m)]
          (when s
            (let [[k v] (first s)]
              (print-method [(vec k) v] w))
            (doseq [[k v] (rest s)]
              (.write w " ")
              (print-method [(vec k) v] w))))
        (.write w "]"))
    (do (.write w "#<IntervalMap {")
        (let [s (seq m)]
          (when s
            (let [[k v] (first s)]
              (print-method k w) (.write w " ") (print-method v w))
            (doseq [[k v] (rest s)]
              (.write w ", ")
              (print-method k w) (.write w " ") (print-method v w))))
        (.write w "}>"))))
