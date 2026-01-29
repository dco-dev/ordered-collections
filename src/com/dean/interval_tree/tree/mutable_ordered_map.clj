(ns com.dean.interval-tree.tree.mutable-ordered-map
  (:require [com.dean.interval-tree.tree.node        :as node]
            [com.dean.interval-tree.tree.order       :as order]
            [com.dean.interval-tree.tree.tree        :as tree]
            [com.dean.interval-tree.tree.mutable     :as mut]
            [com.dean.interval-tree.tree.ordered-map :as ordered-map])
  (:import  [clojure.lang RT]
            [com.dean.interval_tree.tree.root INodeCollection
                                              IBalancedCollection
                                              IOrderedCollection]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-mutable-ordered-map [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.interval_tree.tree.root.IOrderedCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Ordered Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype MutableOrderedMap [^:unsynchronized-mutable root cmp alloc stitch]

  INodeCollection
  (getAllocator [_] alloc)
  (getRoot [_] root)

  IOrderedCollection
  (getCmp [_] cmp)
  (isCompatible [_ o]
    (and (instance? MutableOrderedMap o) (= cmp (.getCmp ^MutableOrderedMap o))
         (= stitch (.getStitch ^MutableOrderedMap o))))
  (isSimilar [_ o]
    (map? o))

  IBalancedCollection
  (getStitch [_] stitch)

  clojure.lang.ITransientCollection
  (conj [this o]
    (.assoc this (nth o 0) (nth o 1)))
  (persistent [this]
    (with-mutable-ordered-map this
      (ordered-map/->OrderedMap (mut/node->persistent root) cmp alloc stitch {})))

  clojure.lang.ITransientAssociative
  (assoc [this k v]
    (with-mutable-ordered-map this
      (set! root (mut/node-add! root k v))
      this))

  clojure.lang.ITransientMap
  (without [this k]
    (with-mutable-ordered-map this
      (set! root (mut/node-remove! root k))
      this))
  (valAt [this k]
    (.valAt this k nil))
  (valAt [this k not-found]
    (with-mutable-ordered-map this
      (if-let [found (tree/node-find root k)]
        (node/-v found)
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
    (with-mutable-ordered-map this
      (node/-kv (tree/node-nth root i))))

  clojure.lang.Seqable
  (seq [this]
    (with-mutable-ordered-map this
      (map node/-kv (tree/node-seq root))))

  clojure.lang.Reversible
  (rseq [this]
    (with-mutable-ordered-map this
      (map node/-kv (tree/node-seq-reverse root)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method MutableOrderedMap [m ^java.io.Writer w]
  (.write w "#MutableOrderedMap")
  ((get (methods print-method) clojure.lang.IPersistentMap)
    (persistent! m) w))
