(ns ordered-collections.types.shared
  (:require [ordered-collections.tree.order :as order]
            [ordered-collections.tree.tree :as tree]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared Boilerplate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-compare
  [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'ordered_collections.tree.root.IOrderedCollection}))]
     ~@body))

(defmacro with-tree-env
  [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'ordered_collections.tree.root.IOrderedCollection}))
             tree/*t-join*   (.getAllocator ~(with-meta x {:tag 'ordered_collections.tree.root.INodeCollection}))]
     ~@body))
