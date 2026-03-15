(ns com.dean.ordered-collections.types.interval-set
  (:require [clojure.core.reducers       :as r :refer [coll-fold]]
            [clojure.set]
            [com.dean.ordered-collections.tree.interval :as interval]
            [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.protocol      :as proto]
            [com.dean.ordered-collections.tree.root]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang                RT Murmur3]
            [com.dean.ordered_collections.protocol PExtensibleSet PIntervalCollection PSpan]
            [com.dean.ordered_collections.tree.root     INodeCollection
                                         IBalancedCollection
                                         IOrderedCollection
                                         IIntervalCollection]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-interval-set [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.IOrderedCollection}))
             tree/*t-join*   (.getAllocator ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.INodeCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype IntervalSet [root cmp alloc stitch _meta]

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
    (and (instance? IntervalSet o) (= cmp (.getCmp ^IOrderedCollection o))))
  (isSimilar [_ _]
    false)

  IBalancedCollection
  (getStitch [_]
    stitch)

  IIntervalCollection

  PIntervalCollection
  (overlapping [this interval]
    (with-interval-set this
      (when-let [found (seq (tree/node-find-intervals root interval))]
        (map node/-k found))))

  PSpan
  (span [this]
    (when-not (node/leaf? root)
      [(first (node/-k (tree/node-least root))) (node/-z root)]))

  PExtensibleSet
  (intersection [this that]
    (with-interval-set this
      (cond
        (identical? this that)    this
        (.isCompatible this that)
        (let [that-root (.getRoot ^INodeCollection that)
              use-parallel? (>= (+ (tree/node-size root) (tree/node-size that-root))
                                tree/+parallel-threshold+)]
          (IntervalSet.
           (if use-parallel?
             (tree/node-set-intersection-parallel root that-root)
             (tree/node-set-intersection root that-root))
           cmp alloc stitch {}))
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (union [this that]
    (with-interval-set this
      (cond
        (identical? this that)    this
        (.isCompatible this that)
        (let [that-root (.getRoot ^INodeCollection that)
              use-parallel? (>= (+ (tree/node-size root) (tree/node-size that-root))
                                tree/+parallel-threshold+)]
          (IntervalSet.
           (if use-parallel?
             (tree/node-set-union-parallel root that-root)
             (tree/node-set-union root that-root))
           cmp alloc stitch {}))
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (difference [this that]
    (with-interval-set this
      (cond
        (identical? this that)    (.empty this)
        (.isCompatible this that)
        (let [that-root (.getRoot ^INodeCollection that)
              use-parallel? (>= (+ (tree/node-size root) (tree/node-size that-root))
                                tree/+parallel-threshold+)]
          (IntervalSet.
           (if use-parallel?
             (tree/node-set-difference-parallel root that-root)
             (tree/node-set-difference root that-root))
           cmp alloc stitch {}))
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (subset? [this that]
    (with-interval-set this
      (cond
        (identical? this that)    true
        (.isCompatible this that) (tree/node-subset? (.getRoot ^INodeCollection that) root) ;; Grr. reverse args of tree/subset
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (superset? [this that]
    (with-interval-set this
      (cond
        (identical? this that)    true
        (.isCompatible this that) (tree/node-subset? root (.getRoot ^INodeCollection that))
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (disjoint? [this that]
    (with-interval-set this
      (cond
        (identical? this that)    (zero? (tree/node-size root))
        (.isCompatible this that) (tree/node-disjoint? root (.getRoot ^INodeCollection that))
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))

  clojure.lang.IMeta
  (meta [_]
    _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (IntervalSet. root cmp alloc stitch m))

  clojure.lang.Indexed
  (nth [_ i]
    (node/-k (tree/node-nth root i)))
  (nth [_ i not-found]
    (if (and (>= i 0) (< i (tree/node-size root)))
      (node/-k (tree/node-nth root i))
      not-found))

  clojure.lang.Seqable
  (seq [_]
    (tree/key-seq root (tree/node-size root)))

  clojure.lang.Reversible
  (rseq [_]
    (tree/key-seq-reverse root (tree/node-size root)))

  clojure.lang.ILookup
  (valAt [this k not-found]
    (with-interval-set this
      (if-let [found (seq (tree/node-find-intervals root k))]
        (map node/-k found)
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
    (with-interval-set this
      (cond
        (identical? this o)   0
        (.isCompatible this o) (tree/node-set-compare root (.getRoot ^INodeCollection o))
        true (throw (ex-info "unsupported comparison: " {:this this :o o})))))

  java.util.Collection
  (toArray [this]
    (with-interval-set this
      (object-array (tree/node-vec root :accessor :k)))) ; better constructor not a priority
  (isEmpty [_]
    (node/leaf? root))
  (add [_ _]
    (throw (UnsupportedOperationException.)))
  (addAll [_ _]
    (throw (UnsupportedOperationException.)))
  (removeAll [_ _]
    (throw (UnsupportedOperationException.)))
  (retainAll [_ _]
    (throw (UnsupportedOperationException.)))

  java.util.Set
  (size [_]
    (tree/node-size root))
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))
  (containsAll [this s]
    ;; Checks if all intervals in s exist as exact intervals in this set.
    ;; Does NOT check coverage (use interval queries for that).
    (with-interval-set this
      (cond
        (identical? this s)    true
        (coll? s) (every? #(.contains this %) s)
        true      (throw (ex-info "unsupported comparison: " {:this this :s s})))))

  java.util.SortedSet
  (comparator [_]
    cmp)
  (first [this]
    (with-interval-set this
      (first (tree/node-least-kv root))))
  (last [this]
    (with-interval-set this
      (first (tree/node-greatest-kv root))))
  (headSet [this x]
    (with-interval-set this
      (IntervalSet. (tree/node-split-lesser root (interval/ordered-pair x)) cmp alloc stitch {})))
  (tailSet [this x]
    (with-interval-set this
      (let [k (interval/ordered-pair x)
            [_ present gt] (tree/node-split root k)]
        (if present
          (IntervalSet. (tree/node-add gt (first present) (first present) cmp alloc) cmp alloc stitch {})
          (IntervalSet. gt cmp alloc stitch {})))))
  (subSet [this from to]
    (with-interval-set this
      (let [from-k (interval/ordered-pair from)
            to-k (interval/ordered-pair to)
            [_ from-present from-gt] (tree/node-split root from-k)
            from-tree (if from-present
                        (tree/node-add from-gt (first from-present) (first from-present) cmp alloc)
                        from-gt)
            to-tree (tree/node-split-lesser root to-k)
            result (tree/node-set-intersection from-tree to-tree)]
        (IntervalSet. result cmp alloc stitch {}))))

  clojure.lang.IPersistentSet
  (equiv [this o]
    (with-interval-set this
      (cond
        (identical? this o) true
        (not (instance? clojure.lang.Counted o)) false
        (.isCompatible this o) (and (= (.count this) (.count ^clojure.lang.Counted o))
                                    (zero? (tree/node-set-compare root (.getRoot ^INodeCollection o))))
        :else false)))
  (count [_]
    (tree/node-size root))
  (empty [_]
    (IntervalSet. (node/leaf) cmp alloc stitch {}))
  (contains [this k]
    (with-interval-set this
      (some? (seq (tree/node-find-intervals root (interval/ordered-pair k))))))
  (disjoin [this k]
    (IntervalSet. (tree/node-remove root (interval/ordered-pair k) cmp alloc) cmp alloc stitch _meta))
  (cons [this k]
    (IntervalSet. (tree/node-add root (interval/ordered-pair k) (interval/ordered-pair k) cmp alloc) cmp alloc stitch _meta))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (tree/node-reduce-keys f init root))

  clojure.lang.IReduce
  (reduce [this f]
    (tree/node-reduce-keys f root))

  Object
  (toString [this]
    (pr-str this))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/mixCollHash
      (unchecked-int
        (tree/node-reduce
          (fn [^long acc n]
            (unchecked-add acc (long (clojure.lang.Util/hasheq (node/-k n)))))
          (long 0)
          root))
      (tree/node-size root)))

  clojure.core.reducers.CollFold
  (coll-fold [this n combinef reducef]
    (with-interval-set this
      (tree/node-chunked-fold n root combinef
        (fn [acc node] (reducef acc (node/-k node)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method IntervalSet [^IntervalSet s ^java.io.Writer w]
  (if (order/default-comparator? (.getCmp ^IOrderedCollection s))
    (do (.write w "#ordered/interval-set ")
        (print-method (vec s) w))
    (do (.write w "#<IntervalSet ")
        (print-method (vec s) w)
        (.write w ">"))))
