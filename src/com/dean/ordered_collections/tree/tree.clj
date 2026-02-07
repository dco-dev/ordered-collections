(ns com.dean.ordered-collections.tree.tree
  (:require [clojure.core.reducers       :as r]
            [com.dean.ordered-collections.tree.interval :as interval]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.node     :as node  :refer [leaf? leaf -k -v -l -r -x -z -kv]])
  (:import  [clojure.lang MapEntry]
            [java.util Comparator]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Weight Balanced Functional Binary Interval Tree (Hirai-Yamamoto Tree)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; This is an implementation of a weight-balanced binary interval-tree data
;; structure based on the following references:
;;
;; --  Adams (1992)
;;     'Implementing Sets Efficiently in a Functional Language'
;;     Technical Report CSTR 92-10, University of Southampton.
;;     <http://groups.csail.mit.edu/mac/users/adams/BB/92-10.ps>
;;
;; --  Hirai and Yamamoto (2011)
;;     'Balancing Weight-Balanced Trees'
;;     Journal of Functional Programming / 21 (3):
;;     Pages 287-307
;;     <https://yoichihirai.com/bst.pdf>
;;
;; --  Oleg Kiselyov
;;     'Towards the best collection API, A design of the overall optimal
;;     collection traversal interface'
;;     <http://pobox.com/~oleg/ftp/papers/LL3-collections-enumerators.txt>
;;
;; --  Nievergelt and Reingold (1972)
;;     'Binary Search Trees of Bounded Balance'
;;     STOC '72 Proceedings
;;     4th Annual ACM symposium on Theory of Computing
;;     Pages 137-142
;;
;; --  Driscoll, Sarnak, Sleator, and Tarjan (1989)
;;     'Making Data Structures Persistent'
;;     Journal of Computer and System Sciences Volume 38 Issue 1, February 1989
;;     18th Annual ACM Symposium on Theory of Computing
;;     Pages 86-124
;;
;; --  MIT Scheme weight balanced tree as reimplemented by Yoichi Hirai
;;     and Kazuhiko Yamamoto using the revised non-variant algorithm recommended
;;     integer balance parameters from (Hirai/Yamomoto 2011).
;;
;; --  Wikipedia
;;     'Interval Tree'
;;     <https://en.wikipedia.org/wiki/Interval_tree>
;;
;; --  Wikipedia
;;     'Weight Balanced Tree'
;;     <https://en.wikipedia.org/wiki/Weight-balanced_tree>
;;
;; --  Andrew Baine, Rahul Jaine (2007)
;;     'Purely Functional Data Structures in Common Lisp'
;;     Google Summer of Code 2007
;;     <https://common-lisp.net/project/funds/funds.pdf>
;;     <https://developers.google.com/open-source/gsoc/2007/>
;;
;; -- Scott L. Burson
;;     'Functional Set-Theoretic Collections for Common Lisp'
;;     <https://common-lisp.net/project/fset/Site/index.html>
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: potential improvements
;;
;; - transient/editable collections (would very significantly improve creation)
;; - hash/hashEq

;; TODO: additional operations
;;
;; - node-traverse (maybe?)
;; - reducer

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Weight Balancing Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:const true
       :doc   "The primary balancing rotation coefficient that is used for the
              determination whether two subtrees of a node are in balance or
              require adjustment by means of a rotation operation.  The specific
              rotation to be performed is determined by `+gamma+`."}
  +delta+ 3)

(def ^{:const true
       :doc   "The secondary balancing rotation coefficient that is used for the
              determination of whether a single or double rotation operation should
              occur, once it has been decided based on `+delta+` that a rotation is
              indeed required."}
  +gamma+ 2)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node Destructuring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro kvlr
  "destructure node n: key value left right. This is the principal destructuring macro
  for operating on regions of trees"
  [[ksym vsym lsym rsym] n & body]
  `(let [n# ~n
         ~ksym (-k n#) ~vsym (-v n#)
         ~lsym (-l n#) ~rsym (-r n#)]
     ~@body))

(defmacro lr [[lsym rsym] n & body]
  `(let [n# ~n ~lsym (-l n#) ~rsym (-r n#)]
     ~@body))

(defn maybe-z [n]
  (when-not (leaf? n) (-z n)))

(def ^:private node-accessor {:k -k :v -v :kv -kv nil identity})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Balance Metrics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-size
  "returns the balance metric of the tree rooted at n."
  ^long [n]
  (if (leaf? n) 0 (-x n)))

(defn node-weight
  "returns node weight as appropriate for rotation calculations using
   the 'revised non-variant algorithm' for weight balanced binary tree."
  ^long [n]
  (unchecked-inc (node-size n)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node Builders (t-join)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-create-weight-balanced
  "Join left and right weight-balanced subtrees at root k/v.
  Assumes all keys in l < k < all keys in r."
  [k v l r]
  (node/->SimpleNode k v l r (+ 1 (node-size l) (node-size r))))

(defn node-create-weight-balanced-interval
  "Join left and right weight-balanced interval subtrees at root k/v.
  Assumes all keys in l < k < all keys in r."
  [i v l r]
  (node/->IntervalNode i v l r (+ 1 (node-size l) (node-size r))
     (order/max (interval/b i) (maybe-z l) (maybe-z r))))

(def ^:dynamic *t-join* node-create-weight-balanced)

(defn node-create
  "Join left and right subtrees at root k/v.
  Assumes all keys in l < k < all keys in r."
  [k v l r]
  (*t-join* k v l r))

(defn node-singleton
  "Create and return a newly allocated, balanced tree
  containing a single association, that of key K with value V."
  [k v]
  (node-create k v (leaf) (leaf)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node Enumerators: the fundamental traversal algorithm
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: describe in more detail "enumerator" concept
;; TODO: diagram of left partial tree decomposition
;; TODO: use a simple triple type rather than persistentlist

(defn node-enumerator
  "Efficient mechanism to accomplish partial enumeration of
   tree-structure into a seq representation without incurring the
   overhead of operating over the entire tree.  Used internally for
   implementation of higher-level collection api routines"
  ([n] (node-enumerator n nil))
  ([n enum]
     (if (leaf? n)
       enum
       (kvlr [k v l r] n
         (recur l (list n r enum))))))

;; TODO: diagram of right partial tree decomposition

(defn node-enumerator-reverse
  ([n] (node-enumerator-reverse n nil))
  ([n enum]
     (if (leaf? n)
       enum
       (kvlr [k v l r] n
         (recur r (list n l enum))))))

(def node-enum-first first)

(defn node-enum-rest  [enum]
  (when (some? enum)
    (let [[x1 x2 x3] enum]
      (when-not (and (nil? x2) (nil? x3))
        (node-enumerator x2 x3)))))

(defn node-enum-prior [enum]
  (when (some? enum)
    (let [[x1 x2 x3] enum]
      (when-not (and (nil? x2) (nil? x3))
        (node-enumerator-reverse x2 x3)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree Rotations (Weight Balanced)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rotate-sl
  "Parameterized single left rotation (private, takes explicit create fn)."
  [create ak av x b]
  (kvlr [bk bv y z] b
    (create bk bv (create ak av x y) z)))

(defn- rotate-dl
  "Parameterized double left rotation (private, takes explicit create fn)."
  [create ak av x c]
  (kvlr [ck cv b z] c
    (kvlr [bk bv y1 y2] b
      (create bk bv (create ak av x y1) (create ck cv y2 z)))))

(defn- rotate-sr
  "Parameterized single right rotation (private, takes explicit create fn)."
  [create bk bv a z]
  (kvlr [ak av x y] a
    (create ak av x (create bk bv y z))))

(defn- rotate-dr
  "Parameterized double right rotation (private, takes explicit create fn)."
  [create ck cv a z]
  (kvlr [ak av x b] a
    (kvlr [bk bv y1 y2] b
      (create bk bv (create ak av x y1) (create ck cv y2 z)))))

(defn- stitch-wb
  "Parameterized weight-balanced stitch (private, takes explicit create fn).
  Same algorithm as node-stitch-weight-balanced but avoids dynamic var deref."
  [create k v l r]
  (let [lw (node-weight l)
        rw (node-weight r)]
    (cond
      (> rw (* +delta+ lw)) (let [rlw (node-weight (-l r))
                                  rrw (node-weight (-r r))]
                              (if (< rlw (* +gamma+ rrw))
                                (rotate-sl create k v l r)
                                (rotate-dl create k v l r)))
      (> lw (* +delta+ rw)) (let [llw (node-weight (-l l))
                                  lrw (node-weight (-r l))]
                              (if (< lrw (* +gamma+ llw))
                                (rotate-sr create k v l r)
                                (rotate-dr create k v l r)))
      :else                 (create k v l r))))

(defn rotate-single-left
  "Perform a single left rotation, moving Y, the left subtree of the
  right subtree of A, into the left subtree (shown below).  This must
  occur in order to restore proper balance when the weight of the left
  subtree of node A is less then the weight of the right subtree of
  node A multiplied by rotation coefficient +delta+ and the weight of
  the left subtree of node B is less than the weight of the right subtree
  of node B multiplied by rotation coefficient +gamma+

                ,---,                                  ,---,
                | A |                                  | B |
                :---:                                  :---:
               :     :                                :     :
          ,---:       :---,                      ,---:       :---,
          | X |       | B |           =>         | A |       | Z |
          '---'       :---:                      :---:       '---'
                 ,---:     :---,            ,---:     :---,
                 | Y |     | Z |            | X |     | Y |
                 '---'     '---'            '---'     '---'"
  [ak av x b]
  (kvlr [bk bv y z] b
    (node-create bk bv
      (node-create ak av x y) z)))

(defn rotate-double-left
  "Perform a double left rotation, moving Y1, the left subtree of the
  left subtree of the right subtree of A, into the left subtree (shown
  below).  This must occur in order to restore proper balance when the
  weight of the left subtree of node A is less then the weight of the
  right subtree of node A multiplied by rotation coefficient +delta+
  and the weight of the left subtree of node B is greater than or equal
  to the weight of the right subtree of node B multiplied by rotation
  coefficient +gamma+.

                ,---,                                    ,---,
                | A |                                    | B |
             ___:---:___                             ____:---:____
        ,---:           :---,                   ,---:             :---,
        | X |           | C |                   | A |             | C |
        '---'           :---:         =>        :---:             :---:
                   ,---:     :---,         ,---:     :---,   ,---:     :---,
                   | B |     | Z |         | X |     | y1|   | y2|     | Z |
                   :---:     '---'         '---'     '---'   '---'     '---'
              ,---:     :---,
              | y1|     | y2|
              '---'     '---'"
  [ak av x c]
  (kvlr [ck cv b z] c
    (kvlr [bk bv y1 y2] b
      (node-create bk bv
        (node-create ak av x y1)
        (node-create ck cv y2 z)))))

(defn rotate-single-right
  "Perform a single right rotation, moving Y, the right subtree of the
  left subtree of B, into the right subtree (shown below).  This must
  occur in order to restore proper balance when the weight of the right
  subtree of node B is less then the weight of the left subtree of
  node B multiplied by rotation coefficient +delta+ and the weight of the
  right subtree of node A is less than the weight of the left subtree
  of node A multiplied by rotation coefficient +gamma+.

                ,---,                                  ,---,
                | B |                                  | A |
                :---:                                  :---:
               :     :                                :     :
          ,---:       :---,                      ,---:       :---,
          | A |       | Z |          =>          | X |       | B |
          :---:       '---'                      '---'       :---:
     ,---:     :---,                                    ,---:     :---,
     | X |     | Y |                                    | Y |     | Z |
     '---'     '---'                                    '---'     '---'"
  [bk bv a z]
  (kvlr [ak av x y] a
    (node-create ak av x (node-create bk bv y z))))

(defn rotate-double-right
  "Perform a double right rotation, moving Y2, the right subtree of
  the right subtree of the left subtree of C, into the right
  subtree (shown below).  This must occur in order to restore proper
  balance when the weight of the right subtree of node C is less then
  the weight of the left subtree of node C multiplied by rotation
  coefficient +delta+ and the weight of the right subtree of node B
  is greater than or equal to the weight of the left subtree of node B
  multiplied by rotation coefficient +gamma+.

                ,---,                                    ,---,
                | C |                                    | B |
             ___:---:___                             ____:---:____
        ,---:           :---,                   ,---:             :---,
        | A |           | Z |                   | A |             | C |
        :---:           '---'        =>         :---:             :---:
   ,---:     :---,                         ,---:     :---,   ,---:     :---,
   | X |     | B |                         | X |     | y1|   | y2|     | Z |
   '---'     :---:                         '---'     '---'   '---'     '---'
        ,---:     :---,
        | y1|     | y2|
        '---'     '---'"
  [ck cv a z]
  (kvlr [ak av x b] a
    (kvlr [bk bv y1 y2] b
      (node-create bk bv
        (node-create ak av x y1)
        (node-create ck cv y2 z)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Balanced Tree Constructors (n-Join]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-stitch-weight-balanced
  "Weight-Balancing Algorithm:

  Join left and right subtrees at root k/v, performing a single or
  double rotation to balance the resulting tree, if needed.  Assumes
  all keys in l < k < all keys in r, and the relative weight balance
  of the left and right subtrees is such that no more than one
  single/double rotation will result in each subtree being less than
  +delta+ times the weight of the other.  This is the heart of tree
  construction."
  [k v l r]
  (let [lw (node-weight l)
        rw (node-weight r)]
    (cond
      (> rw (* +delta+ lw)) (let [rlw (node-weight (-l r))
                                  rrw (node-weight (-r r))]
                              (if (< rlw (* +gamma+ rrw))
                                (rotate-single-left k v l r)
                                (rotate-double-left k v l r)))
      (> lw (* +delta+ rw)) (let [llw (node-weight (-l l))
                                  lrw (node-weight (-r l))]
                              (if (< lrw (* +gamma+ llw))
                                (rotate-single-right k v l r)
                                (rotate-double-right k v l r)))
      true                  (node-create k v l r))))

(def ^:dynamic *n-join* node-stitch-weight-balanced)

(defn node-stitch
  "The `stitch` operation is the sole balancing constructor and
  interface to the specific balancing rotation algorithm of the tree.
  other balancing algorithms (AVL Tree, Red-Black Tree) can be
  implemented here without effect to other aspects of the tree.
  Sometimes referred to as `n-join` operation"
  [k v l r]
  (*n-join* k v l r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Tree Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-add
  "Insert a new key/value into the tree rooted at n."
  ([n k]
   (node-add n k k))
  ([n k v]
   (node-add n k v order/*compare* *t-join*))
  ([n k v ^Comparator cmp create]
   (letfn [(add [n]
             (if (leaf? n)
               (create k v (leaf) (leaf))
               (kvlr [key val l r] n
                 (let [c (.compare cmp k key)]
                   (if (zero? c)
                     (create key v l r)
                     (if (neg? c)
                       (stitch-wb create key val (add l) r)
                       (stitch-wb create key val l (add r))))))))]
     (add n))))

(defn node-concat3
  "Join two trees, the left rooted at l, and the right at r,
  with a new key/value, performing rotation operations on the resulting
  trees and subtrees. Assumes all keys in l are smaller than all keys in
  r, and the relative balance of l and r is such that no more than one
  rotation operation will be required to balance the resulting tree."
  [k v l r]
  (let [^Comparator cmp order/*compare*
        create *t-join*]
    (letfn [(cat3 [k v l r]
              (cond
                (leaf? l) (let [add (fn add [n]
                                     (if (leaf? n)
                                       (create k v (leaf) (leaf))
                                       (kvlr [key val l r] n
                                         (let [c (.compare cmp k key)]
                                           (if (zero? c)
                                             (create key v l r)
                                             (if (neg? c)
                                               (stitch-wb create key val (add l) r)
                                               (stitch-wb create key val l (add r))))))))]
                            (add r))
                (leaf? r) (let [add (fn add [n]
                                     (if (leaf? n)
                                       (create k v (leaf) (leaf))
                                       (kvlr [key val l r] n
                                         (let [c (.compare cmp k key)]
                                           (if (zero? c)
                                             (create key v l r)
                                             (if (neg? c)
                                               (stitch-wb create key val (add l) r)
                                               (stitch-wb create key val l (add r))))))))]
                            (add l))
                true      (let [lw (node-weight l)
                                rw (node-weight r)]
                            (cond
                              (< (* +delta+ lw) rw) (kvlr [k2 v2 l2 r2] r
                                                      (stitch-wb create k2 v2
                                                        (cat3 k v l l2) r2))
                              (< (* +delta+ rw) lw) (kvlr [k1 v1 l1 r1] l
                                                      (stitch-wb create k1 v1 l1
                                                        (cat3 k v r1 r)))
                              true                  (create k v l r)))))]
      (cat3 k v l r))))

(defn node-least
  "Return the node containing the minimum key of the tree rooted at n"
  [n]
  (cond
    (leaf? n)      (throw (ex-info "least: empty tree" {:node n}))
    (leaf? (-l n)) n
    true           (recur (-l n))))

(defn node-greatest
  "Return the node containing the minimum key of the tree rooted at n"
  [n]
  (cond
    (leaf? n)      (throw (ex-info "greatest: empty tree" {:node n}))
    (leaf? (-r n)) n
    true           (recur (-r n))))

(defn node-remove-least
  "Return a tree the same as the one rooted at n, with the node
  containing the minimum key removed. See node-least."
  [n]
  (let [create *t-join*]
    (letfn [(rm-least [n]
              (cond
                (leaf? n)       (throw (ex-info "remove-least: empty tree" {:node n}))
                (leaf? (-l n))  (-r n)
                true            (stitch-wb create (-k n) (-v n)
                                  (rm-least (-l n)) (-r n))))]
      (rm-least n))))

(defn node-remove-greatest
  "Return a tree the same as the one rooted at n, with the node
  containing the maximum key removed. See node-greatest."
  [n]
  (let [create *t-join*]
    (letfn [(rm-greatest [n]
              (cond
                (leaf? n)       (throw (ex-info "remove-greatest: empty tree" {:node n}))
                (leaf? (-r n))  (-l n)
                true            (stitch-wb create (-k n) (-v n) (-l n)
                                  (rm-greatest (-r n)))))]
      (rm-greatest n))))

(defn node-concat2
  "Join two trees, the left rooted at l, and the right at r,
  performing a single balancing operation on the resulting tree, if
  needed. Assumes all keys in l are smaller than all keys in r, and
  the relative balance of l and r is such that no more than one rotation
  operation will be required to balance the resulting tree."
  [l r]
  (let [create *t-join*]
    (cond
      (leaf? l) r
      (leaf? r) l
      true      (kvlr [k v _ _] (node-least r)
                  (stitch-wb create k v l (node-remove-least r))))))

(defn node-remove
  "remove the node whose key is equal to k, if present."
  ([n k]
   (node-remove n k order/*compare* *t-join*))
  ([n k ^Comparator cmp create]
   (letfn [(concat2 [l r]
             (cond
               (leaf? l) r
               (leaf? r) l
               :else (kvlr [k v _ _] (node-least r)
                       (stitch-wb create k v l (rm-least r)))))
           (rm-least [n]
             (cond
               (leaf? n)      (throw (ex-info "rm-least: empty" {}))
               (leaf? (-l n)) (-r n)
               :else          (stitch-wb create (-k n) (-v n)
                                (rm-least (-l n)) (-r n))))
           (rm [n]
             (if (leaf? n)
               (leaf)
               (kvlr [key val l r] n
                 (let [c (.compare cmp k key)]
                   (if (zero? c)
                     (concat2 l r)
                     (if (neg? c)
                       (stitch-wb create key val (rm l) r)
                       (stitch-wb create key val l (rm r))))))))]
     (rm n))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree Search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-find
  "find a node in n whose key = k"
  ([n k]
   (node-find n k order/*compare*))
  ([n k ^Comparator cmp]
   (loop [n n]
     (when-not (leaf? n)
       (let [c (.compare cmp k (-k n))]
         (if (zero? c) n (recur (if (neg? c) (-l n) (-r n)))))))))

(defn node-find-nearest
  "Find the nearest k according to relation expressed by :< or :>"
  [n k & [gt-or-lt]]
  (let [gt-or-lt (or gt-or-lt :<)
        ^Comparator cmp-fn order/*compare*
        [cmp fwd rev] (case gt-or-lt
                        :< [(fn [x y] (neg? (.compare cmp-fn x y))) -l -r]
                        :> [(fn [x y] (pos? (.compare cmp-fn x y))) -r -l])]
    (loop [this n best nil]
      (cond
        (leaf? this)      best
        (cmp k (-k this)) (recur (fwd this) best)
        true              (recur (rev this) this)))))

(defn- node-find-interval-fn [i pred]
  (let [i      (interval/ordered-pair i)
        result (volatile! nil)
        accum  (if pred
                 (fn [n] (vswap! result #(if (or (nil? %) (pred n %)) n %)))
                 (fn [n] (vswap! result conj n)))]
    (fn [n]
      (letfn [(srch [this]
                (when-not (leaf? this)
                  (when (order/compare>= (interval/b i) (-> this -k interval/a))
                    (-> this -r srch))
                  (when (interval/intersects? i (-k this))
                    (accum this))
                  (when (and (not (leaf? (-l this)))
                             (order/compare<= (interval/a i) (-> this -l -z)))
                    (-> this -l srch))))]
        (srch n)
        @result))))

(defn node-find-intervals [n i]
  ((node-find-interval-fn i nil) n))

(defn node-find-best-interval [n i pred]
  ((node-find-interval-fn i pred) n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Iteration and Accumulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; MAYBE: replace/refactor as `node-traverse`
;;       options: forward/reverse, in-order/post-order/pre-order

(defn node-iter
  "For the side-effect, apply f to each node of the tree rooted at n."
  [n f]
  (when-not (leaf? n)
    (lr [l r] n
      (node-iter l f)
      (f n)
      (node-iter r f))))

(defn node-iter-reverse
  "For the side-effect, apply f to each node of the tree rooted at n."
  [n f]
  (when-not (leaf? n)
    (lr [l r] n
      (node-iter-reverse r f)
      (f n)
      (node-iter-reverse l f))))

(defn- node-fold-fn [dir]
  (let [[enum-fn next-fn] (case dir
                            :< [node-enumerator node-enum-rest]
                            :> [node-enumerator-reverse node-enum-prior])]
    (fn [f base n]
      (loop [e (enum-fn n) acc base]
        (if (nil? e)
          acc
          (let [res (f acc (node-enum-first e))]
            (if (reduced? res) @res
                (recur (next-fn e) res))))))))

(defn node-fold-left
  "Fold-left (reduce) the collection from least to greatest."
  ([f n]      (node-fold-left f nil n))
  ([f base n] ((node-fold-fn :<) f base n)))

(defn node-fold-right
  "Fold-right (reduce) the collection from greatest to least."
  ([f n] (node-fold-right f nil n))
  ([f base n] ((node-fold-fn :>) f base n)))

(defn node-reduce
  "Stack-based in-order reduction. Faster than enumerator-based node-fold-left
   because it uses a mutable ArrayDeque instead of allocating lists.
   Supports early termination via clojure.core/reduced."
  ([f init root]
   (if (leaf? root)
     init
     (let [stack (java.util.ArrayDeque.)]
       ;; Push leftmost spine
       (loop [n root]
         (when-not (leaf? n)
           (.push stack n)
           (recur (-l n))))
       ;; Process nodes
       (loop [acc init]
         (if (.isEmpty stack)
           acc
           (let [node (.pop stack)
                 res  (f acc node)]
             (if (reduced? res)
               @res
               (do
                 ;; Push left spine of right subtree
                 (loop [n (-r node)]
                   (when-not (leaf? n)
                     (.push stack n)
                     (recur (-l n))))
                 (recur res)))))))))
  ([f root]
   (if (leaf? root)
     (f)
     (let [stack (java.util.ArrayDeque.)]
       ;; Push leftmost spine
       (loop [n root]
         (when-not (leaf? n)
           (.push stack n)
           (recur (-l n))))
       ;; First element as initial accumulator
       (let [first-node (.pop stack)]
         ;; Push left spine of right subtree of first node
         (loop [n (-r first-node)]
           (when-not (leaf? n)
             (.push stack n)
             (recur (-l n))))
         ;; Process remaining nodes
         (loop [acc first-node]
           (if (.isEmpty stack)
             acc
             (let [node (.pop stack)
                   res  (f acc node)]
               (if (reduced? res)
                 @res
                 (do
                   (loop [n (-r node)]
                     (when-not (leaf? n)
                       (.push stack n)
                       (recur (-l n))))
                   (recur res)))))))))))

;; MAYBE: i'm not convinced these are necessary

(defn- node-fold*-fn [dir]
  (let [iter-fn (case dir
                  :< node-iter
                  :> node-iter-reverse)]
    (fn [f base n]
      (let [acc (volatile! base)
            fun #(vswap! acc f %)]
     (iter-fn n fun)
     @acc))))

(defn- node-fold-left*
  "eager left reduction of the tree rooted at n. does not support clojure.core/reduced."
  ([f n] (node-fold-left* f nil n))
  ([f base n] ((node-fold*-fn :<) f base n)))

(defn- node-fold-right*
  "eager right reduction of the tree rooted at n. does not support clojure.core/reduced."
  ([f n] (node-fold-right* f nil n))
  ([f base n] ((node-fold*-fn :>) f base n)))

(defn node-filter
  "return a tree with all nodes of n satisfying predicate p."
  [p n]
  (node-fold-left* (fn [x y]
                      (if (p y)
                        x
                        (node-remove x (-k y))))
                    n n))

(defn node-invert
  "return a tree in which the keys and values of n are reversed."
  [n]
  (node-fold-left* (fn [acc x]
                      (node-add acc (-v x) (-k x)))
                   (leaf) n))

(defn node-healthy?
  "verify node `n` and all descendants satisfy the node-invariants
  of a weight-balanced binary tree."
  [n]
  (or (leaf? n)
      (lr [l r] n
        (let [lw (node-weight l)
              rw (node-weight r)]
          (and
            (<= (max lw rw) (* +delta+ (min lw rw)))
            (node-healthy? l)
            (node-healthy? r))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree Splitting (Logarithmic Time)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-split-lesser
  "return a tree of all nodes whose key is less than k (Logarithmic time)."
  [n k]
  (let [^Comparator cmp order/*compare*]
    (loop [n n]
      (if (leaf? n)
        n
        (kvlr [kn vn ln rn] n
          (let [c (.compare cmp k kn)]
            (if (zero? c) ln
              (if (neg? c)
                (recur ln)
                (node-concat3 kn vn ln
                  (node-split-lesser rn k))))))))))

(defn node-split-greater
  "return a tree of all nodes whose key is greater than k (Logarithmic time)."
  [n k]
  (let [^Comparator cmp order/*compare*]
    (loop [n n]
      (if (leaf? n)
        n
        (kvlr [kn vn ln rn] n
          (let [c (.compare cmp k kn)]
            (if (zero? c) rn
              (if (neg? c)
                (node-concat3 kn vn
                  (node-split-greater ln k) rn)
                (recur rn)))))))))

(defn node-split
  "returns a triple (l present r) where: l is the set of elements of
  n that are < k, r is the set of elements of n that are > k, present
  is false if n contains no element equal to k, or (k v) if n contains
  an element with key equal to k."
  [n k]
  (let [^Comparator cmp order/*compare*]
    (letfn [(split [n]
              (if (leaf? n)
                [nil nil nil]
                (kvlr [ak v l r] n
                  (let [c (.compare cmp k ak)]
                    (if (zero? c)
                      [l (list k v) r]
                      (if (neg? c)
                        (let [[ll pres rl] (split l)]
                          [ll pres (node-concat3 ak v rl r)])
                        (let [[lr pres rr] (split r)]
                          [(node-concat3 ak v l lr) pres rr])))))))]
      (split n))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree Comparator (Worst-Case Linear Time)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-compare
  "return 3-way comparison of the trees n1 and n2 using an accessor
  to compare specific node consitituent values: :k, :v, :kv, or any
  user-specifed function.  Default, when not specified, to the
  entire node structure. return-value semantics:
   -1  -> n1 is LESS-THAN    n2
    0  -> n1 is EQUAL-TO     n2
   +1  -> n1 is GREATER-THAN n2"
  [accessor n1 n2]
  (let [acc-fn (cond-> accessor
                 (not (fn? accessor)) node-accessor)
        ^Comparator cmp order/*compare*]
    (loop [e1 (node-enumerator n1 nil)
           e2 (node-enumerator n2 nil)]
      (cond
        (and (nil? e1) (nil? e2))  0
        (nil? e1)                 -1
        (nil? e2)                  1
        true                       (let [[x1 r1 ee1] e1
                                         [x2 r2 ee2] e2
                                         c (.compare cmp (acc-fn x1) (acc-fn x2))]
                                     (if-not (zero? c)
                                       c
                                       (recur
                                         (node-enumerator r1 ee1)
                                         (node-enumerator r2 ee2))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Set Operations (Worst-Case Linear Time)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-set-union
  "set union"
  [n1 n2]
  (cond
    (leaf? n1) n2
    (leaf? n2) n1
    true       (kvlr [ak av l r] n2
                 (let [[l1 _ r1] (node-split n1 ak)]
                   (node-concat3 ak av
                     (node-set-union l1 l)
                     (node-set-union r1 r))))))

(defn node-set-intersection
  "set intersection"
  [n1 n2]
  (cond
    (leaf? n1) (leaf)
    (leaf? n2) (leaf)
    true       (kvlr [ak av l r] n2
                 (let [[l1 x r1] (node-split n1 ak)]
                   (if x
                     (node-concat3 ak av
                       (node-set-intersection l1 l)
                       (node-set-intersection r1 r))
                     (node-concat2
                       (node-set-intersection l1 l)
                       (node-set-intersection r1 r)))))))

(defn node-set-difference [n1 n2]
  "set difference"
  (cond
    (leaf? n1) (leaf)
    (leaf? n2) n1
    true       (kvlr [ak _ l r] n2
                 (let  [[l1 _ r1] (node-split n1 ak)]
                   (node-concat2
                     (node-set-difference l1 l)
                     (node-set-difference r1 r))))))

(defn node-subset?
  "return true if `sub` is a subset of `super`"
  [super sub]
  (let [^Comparator cmp order/*compare*]
    (letfn [(subset? [n1 n2]
              (or (leaf? n1)
                (and
                  (<= (node-size n1) (node-size n2))
                  (kvlr [k1 _ l1 r1] n1
                    (kvlr [k2 _ l2 r2] n2
                      (let [c (.compare cmp k1 k2)]
                        (if (zero? c)
                          (and (subset? l1 l2) (subset? r1 r2))
                          (if (neg? c)
                            (and (subset? l1 l2) (node-find n2 k1 cmp) (subset? r1 n2))
                            (and (subset? r1 r2) (node-find n2 k1 cmp) (subset? l1 n2))))))))))]
      (or (leaf? sub) (boolean (subset? sub super))))))

(def node-set-compare (partial node-compare :k))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Map Operations (Worst-Case Linear Time)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: adjust this

(defn node-map-merge
  "Merge two maps in worst case linear time."
  [n1 n2 merge-fn]
  (cond
    (leaf? n1) n2
    (leaf? n2) n1
    true       (kvlr [ak av l r] n2
                 (let [[l1 x r1] (node-split n1 ak)
                       val       (if x
                                   (merge-fn ak av (-v x))
                                   av)]
                   (node-concat3 ak val
                     (node-map-merge l1 l)
                     (node-map-merge r1 r))))))

(def node-map-compare (partial node-compare :kv))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Vector Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-nth
  "Return nth node from the beginning of the ordered tree rooted at n.
   (Logarithmic Time)"
  [n ^long index]
  (letfn [(srch [n ^long index]
            (lr [l r] n
              (let [lsize (node-size l)]
                (cond
                  (< index lsize) (recur l index)
                  (> index lsize) (recur r (- index (inc lsize)))
                  true            n))))]
    (if-not (and (<= 0 index) (< index (node-size n)))
      (throw (ex-info "index out of range" {:i index :max (node-size n)}))
      (srch n (long index)))))

(defn node-rank
  "Return the rank (sequential position) of a given KEY within the
  ordered tree rooted at n. (Logarithmic Time)"
  [n k]
  (let [^Comparator cmp order/*compare*]
    (loop [n n k k rank (long 0)]
      (when-not (leaf? n)
        (let [c (.compare cmp k (-k n))]
          (if (zero? c)
            (+ rank (node-size (-l n)))
            (if (neg? c)
              (recur (-l n) k rank)
              (recur (-r n) k (+ 1 rank (node-size (-l n)))))))))))

;; MAYBE: other splits? <= < > ?

(defn node-split-nth
  "return a tree of all nodes whose position is >= i. (Logarithmic Time)"
  [n ^long i]
  (if-not (pos? i)
    n
    (->> i dec (node-nth n) -k (node-split-greater n))))

(defn node-vec
  "Eagerly return a vector of all nodes in tree rooted at n in
  the specified order, optionally using an accessor to extract
  specific node consitituent values: :k, :v, :kv, or any
  user-specifed function.  Default, when not specified, to the
  entire node structure."
  [n & {:keys [accessor reverse?]}]
  (let [acc   (transient [])
        fold  (if reverse? node-fold-right* node-fold-left*)
        nval  (cond-> accessor
                (not (fn? accessor)) node-accessor)]
    (fold #(conj! %1 (nval %2)) acc n)
    (persistent! acc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Seq Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- node-seq-fn [dir]
  (let [next-fn (case dir
                  :< node-enum-rest
                  :> node-enum-prior)]
    (fn seq-fn [enum]
      (lazy-seq
        (when-not (nil? enum)
          (cons (node-enum-first enum)
                (seq-fn (next-fn enum))))))))

(defn node-seq
  "Return a (lazy) seq of nodes in tree rooted at n in the order they occur.
   (Logarithmic Time)"
  [n]
  ((node-seq-fn :<) (node-enumerator n)))

(defn node-seq-reverse
  "Return a (lazy) seq of nodes in tree rooted at n in reverse order."
  [n]
  ((node-seq-fn :>) (node-enumerator-reverse n)))

(defn node-subseq
  "Return a (lazy) seq of nodes for the slice of the tree beginning
  at position `from` ending at `to`."
  ([n from]
   (node-subseq n from (node-size n)))
  ([n ^long from ^long to]
   (let [cnt (inc (- to from))]
     (cond
       (leaf? n)        nil
       (not (pos? cnt)) nil
       true (->> from (node-split-nth n) node-seq (take cnt))))))

(defn node-chunked-fold
  "Parallel chunked fold mechansim to suport clojure.core.reducers/CollFold"
  [^long i n combinef reducef]
  {:pre [(pos? i)]}
  (let [offsets (vec (range 0 (node-size n) i))
        chunk   (fn [^long offset] (node-subseq n offset (dec (+ offset i))))
        rf      (fn [_ ^long offset] (r/reduce reducef (combinef) (chunk offset)))]
    (r/fold 1 combinef rf offsets)))
