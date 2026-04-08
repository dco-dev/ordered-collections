(ns ordered-collections.types.shared
  (:require [ordered-collections.kernel.order :as order]
            [ordered-collections.kernel.tree :as tree]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared Boilerplate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-compare
  [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'ordered_collections.kernel.root.IOrderedCollection}))]
     ~@body))

(defmacro with-tree-env
  [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'ordered_collections.kernel.root.IOrderedCollection}))
             tree/*t-join*   (.getAllocator ~(with-meta x {:tag 'ordered_collections.kernel.root.INodeCollection}))]
     ~@body))
