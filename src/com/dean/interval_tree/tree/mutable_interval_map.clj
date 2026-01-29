(ns com.dean.interval-tree.tree.mutable-interval-map
  (:require [com.dean.interval-tree.tree.interval     :as interval]
            [com.dean.interval-tree.tree.node         :as node]
            [com.dean.interval-tree.tree.order        :as order]
            [com.dean.interval-tree.tree.tree         :as tree]
            [com.dean.interval-tree.tree.mutable      :as mut]
            [com.dean.interval-tree.tree.interval-map :as interval-map])
  (:import  [clojure.lang RT]
            [com.dean.interval_tree.tree.root INodeCollection
                                              IBalancedCollection
                                              IOrderedCollection
                                              IIntervalCollection]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-mutable-interval-map [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.interval_tree.tree.root.IOrderedCollection}))
             tree/*t-join*   (.getAllocator ~(with-meta x {:tag 'com.dean.interval_tree.tree.root.INodeCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Interval Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype MutableIntervalMap [^:unsynchronized-mutable root cmp alloc stitch]

  INodeCollection
  (getAllocator [_] alloc)
  (getRoot [_] root)

  IOrderedCollection
  (getCmp [_] cmp)
  (isCompatible [_ o]
    (and (instance? MutableIntervalMap o) (= cmp (.getCmp ^MutableIntervalMap o))
         (= stitch (.getStitch ^MutableIntervalMap o))))
  (isSimilar [_ o]
    (map? o))

  IBalancedCollection
  (getStitch [_] stitch)

  IIntervalCollection

  clojure.lang.ITransientCollection
  (conj [this o]
    (.assoc this (nth o 0) (nth o 1)))
  (persistent [this]
    (with-mutable-interval-map this
      (interval-map/->IntervalMap (mut/node->persistent root) cmp alloc stitch {})))

  clojure.lang.ITransientAssociative
  (assoc [this k v]
    (with-mutable-interval-map this
      (set! root (mut/node-add! root (interval/ordered-pair k) v))
      this))

  clojure.lang.ITransientMap
  (without [this k]
    (with-mutable-interval-map this
      (set! root (mut/node-remove! root k))
      this))
  (valAt [this k]
    (.valAt this k nil))
  (valAt [this k not-found]
    (with-mutable-interval-map this
      (if-let [found (tree/node-find-intervals root k)]
        (map node/-v found)
        not-found)))

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
    (with-mutable-interval-map this
      (node/-kv (tree/node-nth root i))))

  clojure.lang.Seqable
  (seq [this]
    (with-mutable-interval-map this
      (map node/-kv (tree/node-seq root))))

  clojure.lang.Reversible
  (rseq [this]
    (with-mutable-interval-map this
      (map node/-kv (tree/node-seq-reverse root)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method MutableIntervalMap [m ^java.io.Writer w]
  (.write w "#MutableIntervalMap")
  ((get (methods print-method) clojure.lang.IPersistentMap)
    (persistent! m) w))
