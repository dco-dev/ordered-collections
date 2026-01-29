(ns com.dean.interval-tree.tree.mutable-ordered-set
  (:require [com.dean.interval-tree.tree.node        :as node]
            [com.dean.interval-tree.tree.order       :as order]
            [com.dean.interval-tree.tree.tree        :as tree]
            [com.dean.interval-tree.tree.mutable     :as mut]
            [com.dean.interval-tree.tree.ordered-set :as ordered-set])
  (:import  [clojure.lang RT]
            [com.dean.interval_tree.tree.root INodeCollection
                                              IBalancedCollection
                                              IOrderedCollection]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-mutable-ordered-set [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.interval_tree.tree.root.IOrderedCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Ordered Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype MutableOrderedSet [^:unsynchronized-mutable root cmp alloc stitch]

  INodeCollection
  (getAllocator [_] alloc)
  (getRoot [_] root)

  IOrderedCollection
  (getCmp [_] cmp)
  (isCompatible [_ o]
    (and (instance? MutableOrderedSet o) (= cmp (.getCmp ^MutableOrderedSet o))
         (= stitch (.getStitch ^MutableOrderedSet o))))
  (isSimilar [_ o]
    (set? o))

  IBalancedCollection
  (getStitch [_] stitch)

  clojure.lang.ITransientCollection
  (conj [this k]
    (with-mutable-ordered-set this
      (set! root (mut/node-add! root k))
      this))
  (persistent [this]
    (with-mutable-ordered-set this
      (ordered-set/->OrderedSet (mut/node->persistent root) cmp alloc stitch {})))

  clojure.lang.ITransientSet
  (disjoin [this k]
    (with-mutable-ordered-set this
      (set! root (mut/node-remove! root k))
      this))
  (contains [this k]
    (with-mutable-ordered-set this
      (some? (tree/node-find root k))))
  (get [this k]
    (with-mutable-ordered-set this
      (when-let [found (tree/node-find root k)]
        (node/-k found))))

  clojure.lang.ILookup
  (valAt [this k not-found]
    (with-mutable-ordered-set this
      (if-let [found (tree/node-find root k)]
        (node/-k found)
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
    (with-mutable-ordered-set this
      (node/-k (tree/node-nth root i))))

  clojure.lang.Seqable
  (seq [this]
    (with-mutable-ordered-set this
      (map node/-k (tree/node-seq root))))

  clojure.lang.Reversible
  (rseq [this]
    (with-mutable-ordered-set this
      (map node/-k (tree/node-seq-reverse root)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method MutableOrderedSet [s ^java.io.Writer w]
  (.write w "#MutableOrderedSet")
  ((get (methods print-method) clojure.lang.IPersistentSet)
    (persistent! s) w))
