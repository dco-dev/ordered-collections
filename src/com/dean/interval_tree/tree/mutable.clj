(ns com.dean.interval-tree.tree.mutable
  (:require [com.dean.interval-tree.tree.interval :as interval]
            [com.dean.interval-tree.tree.order    :as order]
            [com.dean.interval-tree.tree.node     :as node
             :refer [leaf? leaf -k -v -l -r -x -z
                     -set-k! -set-v! -set-l! -set-r! -set-x! -set-z!]]
            [com.dean.interval-tree.tree.tree     :as tree])
  (:import  [com.dean.interval_tree.tree.node IAugmentedNode]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Node Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-create!
  "Create a new mutable node with the given key, value, and children.
  Dispatches on *t-join* to determine whether to create a simple or interval node."
  [k v l r]
  (if (identical? tree/*t-join* tree/node-create-weight-balanced-interval)
    (node/->MutableIntervalNode k v l r
      (+ 1 (tree/node-size l) (tree/node-size r))
      (order/max (interval/b k) (tree/maybe-z l) (tree/maybe-z r)))
    (node/->MutableSimpleNode k v l r
      (+ 1 (tree/node-size l) (tree/node-size r)))))

(defn node-singleton!
  "Create a new mutable leaf node with the given key and value."
  [k v]
  (node-create! k v (leaf) (leaf)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Node Update
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-update!
  "Mutate node n in-place. Recomputes size and, for interval nodes, the z augmentation."
  [n k v l r]
  (-set-k! n k) (-set-v! n v) (-set-l! n l) (-set-r! n r)
  (-set-x! n (+ 1 (tree/node-size l) (tree/node-size r)))
  (when (instance? IAugmentedNode n)
    (-set-z! n (order/max (interval/b k) (tree/maybe-z l) (tree/maybe-z r))))
  n)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Tree Rotations (zero allocations)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rotate-single-left!
  "In-place single left rotation. Preserves root node identity by
  swapping contents between root and promoted child."
  [a-node]
  (let [b-node (-r a-node)
        bk (-k b-node) bv (-v b-node) y (-l b-node) z (-r b-node)
        ak (-k a-node) av (-v a-node) x (-l a-node)]
    (node-update! b-node ak av x y)
    (node-update! a-node bk bv b-node z)))

(defn rotate-single-right!
  "In-place single right rotation. Preserves root node identity by
  swapping contents between root and promoted child."
  [b-node]
  (let [a-node (-l b-node)
        ak (-k a-node) av (-v a-node) x (-l a-node) y (-r a-node)
        bk (-k b-node) bv (-v b-node) z (-r b-node)]
    (node-update! a-node bk bv y z)
    (node-update! b-node ak av x a-node)))

(defn rotate-double-left!
  "In-place double left rotation. Reuses all 3 existing nodes (a, c, b),
  zero allocations."
  [a-node]
  (let [c-node (-r a-node)
        b-node (-l c-node)
        bk (-k b-node) bv (-v b-node) y1 (-l b-node) y2 (-r b-node)
        ak (-k a-node) av (-v a-node) x  (-l a-node)
        ck (-k c-node) cv (-v c-node) z  (-r c-node)]
    (node-update! b-node ak av x y1)
    (node-update! c-node ck cv y2 z)
    (node-update! a-node bk bv b-node c-node)))

(defn rotate-double-right!
  "In-place double right rotation. Reuses all 3 existing nodes (c, a, b),
  zero allocations."
  [c-node]
  (let [a-node (-l c-node)
        b-node (-r a-node)
        bk (-k b-node) bv (-v b-node) y1 (-l b-node) y2 (-r b-node)
        ck (-k c-node) cv (-v c-node) z  (-r c-node)
        ak (-k a-node) av (-v a-node) x  (-l a-node)]
    (node-update! a-node ak av x y1)
    (node-update! b-node ck cv y2 z)
    (node-update! c-node bk bv a-node b-node)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Stitch (Rebalance)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-stitch!
  "Rebalance the mutable node n in-place using weight-balanced tree
  delta/gamma logic, dispatching to mutable rotations."
  [n]
  (let [lw (tree/node-weight (-l n))
        rw (tree/node-weight (-r n))]
    (cond
      (> rw (* tree/+delta+ lw)) (if (< (tree/node-weight (-l (-r n)))
                                         (* tree/+gamma+ (tree/node-weight (-r (-r n)))))
                                   (rotate-single-left! n)
                                   (rotate-double-left! n))
      (> lw (* tree/+delta+ rw)) (if (< (tree/node-weight (-r (-l n)))
                                         (* tree/+gamma+ (tree/node-weight (-l (-l n)))))
                                   (rotate-single-right! n)
                                   (rotate-double-right! n))
      :else n)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutable Tree Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-add!
  "Insert a new key/value into the mutable tree rooted at n.
  Allocates exactly 1 new leaf node; all parent mutations are in-place."
  ([n k]   (node-add! n k k))
  ([n k v]
   (if (leaf? n)
     (node-singleton! k v)
     (case (order/compare k (-k n))
       -1 (do (-set-l! n (node-add! (-l n) k v))
              (-set-x! n (+ 1 (tree/node-size (-l n)) (tree/node-size (-r n))))
              (when (instance? IAugmentedNode n)
                (-set-z! n (order/max (interval/b (-k n)) (tree/maybe-z (-l n)) (tree/maybe-z (-r n)))))
              (node-stitch! n))
       +1 (do (-set-r! n (node-add! (-r n) k v))
              (-set-x! n (+ 1 (tree/node-size (-l n)) (tree/node-size (-r n))))
              (when (instance? IAugmentedNode n)
                (-set-z! n (order/max (interval/b (-k n)) (tree/maybe-z (-l n)) (tree/maybe-z (-r n)))))
              (node-stitch! n))
        0 (do (-set-v! n v) n)))))

(defn node-remove!
  "Remove the node whose key is equal to k from the mutable tree rooted at n."
  [n k]
  (if (leaf? n)
    (leaf)
    (case (order/compare k (-k n))
      -1 (do (-set-l! n (node-remove! (-l n) k))
             (-set-x! n (+ 1 (tree/node-size (-l n)) (tree/node-size (-r n))))
             (when (instance? IAugmentedNode n)
               (-set-z! n (order/max (interval/b (-k n)) (tree/maybe-z (-l n)) (tree/maybe-z (-r n)))))
             (node-stitch! n))
      +1 (do (-set-r! n (node-remove! (-r n) k))
             (-set-x! n (+ 1 (tree/node-size (-l n)) (tree/node-size (-r n))))
             (when (instance? IAugmentedNode n)
               (-set-z! n (order/max (interval/b (-k n)) (tree/maybe-z (-l n)) (tree/maybe-z (-r n)))))
             (node-stitch! n))
       0 (let [l (-l n) r (-r n)]
           (cond
             (leaf? l) r
             (leaf? r) l
             :else     (let [least (tree/node-least r)]
                         (node-update! n (-k least) (-v least) l (node-remove! r (-k least)))
                         (node-stitch! n)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversion Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node->persistent
  "Deep-convert a mutable tree to persistent nodes. O(n).
  Uses the currently-bound *t-join* for node construction."
  [n]
  (if (leaf? n) (leaf)
    (tree/node-create (-k n) (-v n)
      (node->persistent (-l n))
      (node->persistent (-r n)))))

(defn node->mutable
  "Deep-convert a persistent tree to mutable nodes. O(n)."
  [n]
  (if (leaf? n) (leaf)
    (node-create! (-k n) (-v n)
      (node->mutable (-l n))
      (node->mutable (-r n)))))
