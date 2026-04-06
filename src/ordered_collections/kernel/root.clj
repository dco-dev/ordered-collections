(ns ordered-collections.kernel.root)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root Container
;;
;; The Root Container implements the external interface that encapsulates
;; the internal algorithm/structure of a rooted node collection (tree)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(definterface INodeCollection
  (getAllocator     [] "Return a fuction that allocates a new node of this colletion")
  (getRoot          [] "Return a 'root' node as appropriate for the given collection"))

(definterface IBalancedCollection
  (getStitch        [] "Return a 'balanced constructor' that joins an allocated node
                        as appropriate to this collection.  Various balancing algorithms
                        are functions of the implementation of stitch."))

(definterface IOrderedCollection
  (getCmp           []      "Return a three-way comparator that defintes a total order
                             over all items in the collection")
  (isCompatible     [other] "Return `true` if `other` collection is algoritmically compatible")
  (isSimilar        [other] "Return `true` if `other` is algorithmically identical"))

(definterface IIntervalCollection)
