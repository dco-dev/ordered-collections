(ns com.dean.interval-tree.tree.mutable-interval-set
  (:require [com.dean.interval-tree.tree.interval     :as interval]
            [com.dean.interval-tree.tree.node         :as node]
            [com.dean.interval-tree.tree.order        :as order]
            [com.dean.interval-tree.tree.tree         :as tree]
            [com.dean.interval-tree.tree.mutable      :as mut]
            [com.dean.interval-tree.tree.interval-set :as interval-set])
  (:import  [clojure.lang RT]
            [com.dean.interval_tree.tree.root INodeCollection
                                              IBalancedCollection
                                              IOrderedCollection
                                              IIntervalCollection]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-mutable-interval-set [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.interval_tree.tree.root.IOrderedCollection}))
             tree/*t-join*   (.getAllocator ~(with-meta x {:tag 'com.dean.interval_tree.tree.root.INodeCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Interval Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype MutableIntervalSet [^:unsynchronized-mutable root cmp alloc stitch]

  INodeCollection
  (getAllocator [_] alloc)
  (getRoot [_] root)

  IOrderedCollection
  (getCmp [_] cmp)
  (isCompatible [_ o]
    (and (instance? MutableIntervalSet o) (= cmp (.getCmp ^MutableIntervalSet o))))
  (isSimilar [_ _]
    false)

  IBalancedCollection
  (getStitch [_] stitch)

  IIntervalCollection

  clojure.lang.ITransientCollection
  (conj [this k]
    (with-mutable-interval-set this
      (set! root (mut/node-add! root (interval/ordered-pair k)))
      this))
  (persistent [this]
    (with-mutable-interval-set this
      (interval-set/->IntervalSet (mut/node->persistent root) cmp alloc stitch {})))

  clojure.lang.ITransientSet
  (disjoin [this k]
    (with-mutable-interval-set this
      (set! root (mut/node-remove! root (interval/ordered-pair k)))
      this))
  (contains [this k]
    (with-mutable-interval-set this
      (some? (seq (tree/node-find-intervals root (interval/ordered-pair k))))))
  (get [this k]
    (with-mutable-interval-set this
      (when-let [found (seq (tree/node-find-intervals root k))]
        (map node/-k found))))

  clojure.lang.ILookup
  (valAt [this k not-found]
    (with-mutable-interval-set this
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

  clojure.lang.Counted
  (count [_]
    (tree/node-size root))

  clojure.lang.Indexed
  (nth [this i]
    (with-mutable-interval-set this
      (node/-k (tree/node-nth root i))))

  clojure.lang.Seqable
  (seq [this]
    (with-mutable-interval-set this
      (map node/-k (tree/node-seq root))))

  clojure.lang.Reversible
  (rseq [this]
    (with-mutable-interval-set this
      (map node/-k (tree/node-seq-reverse root)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method MutableIntervalSet [s ^java.io.Writer w]
  (.write w "#MutableIntervalSet")
  ((get (methods print-method) clojure.lang.IPersistentSet)
    (persistent! s) w))
