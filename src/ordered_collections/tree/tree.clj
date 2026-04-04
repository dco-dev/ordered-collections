(ns ordered-collections.tree.tree
  (:require [clojure.core.reducers                      :as r]
            [ordered-collections.parallel      :as parallel]
            [ordered-collections.tree.interval :as interval]
            [ordered-collections.tree.node     :as node
             :refer [leaf? leaf -k -v -l -r -x -z -kv]]
            [ordered-collections.tree.order    :as order])
  (:import  [clojure.lang ASeq MapEntry RT ISeq Seqable Sequential IPersistentCollection]
            [java.util Comparator]))


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
;;     <https://okmij.org/ftp/papers/LL3-collections-enumerators.txt>
;;
;; --  Nievergelt and Reingold (1972)
;;     'Binary Search Trees of Bounded Balance'
;;     STOC '72 Proceedings
;;     4th Annual ACM symposium on Theory of Computing
;;     Pages 137-142
;;     <https://dl.acm.org/doi/abs/10.1145/800152.804906>
;;
;; --  Driscoll, Sarnak, Sleator, and Tarjan (1989)
;;     'Making Data Structures Persistent'
;;     Journal of Computer and System Sciences Volume 38 Issue 1, February 1989
;;     18th Annual ACM Symposium on Theory of Computing
;;     Pages 86-124
;;     <https://www.sciencedirect.com/science/article/pii/0022000089900342>
;;
;; --  MIT Scheme weight balanced tree as reimplemented by Yoichi Hirai
;;     and Kazuhiko Yamamoto using the revised non-variant algorithm recommended
;;     integer balance parameters from (Hirai/Yamomoto 2011).
;;     <https://www.cambridge.org/core/journals/journal-of-functional-programming/article/
;;     balancing-weightbalanced-trees/7281C4DE7E56B74F2D13F06E31DCBC5B>
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

(definline node-weight
  "Returns node weight for rotation calculations using the 'revised non-variant
   algorithm' for weight balanced binary trees. Weight = size + 1."
  [n]
  `(let [n# ~n]
     (unchecked-inc (if (leaf? n#) 0 (long (-x n#))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node Builders (t-join)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; `*compare*` and `*t-join*` are the core representation hooks for the
;; tree algebra. Dynamic binding lets one split/join/search implementation serve
;; many concrete node families: generic nodes, primitive-key nodes, interval
;; nodes, segment/augmented nodes, and future variants with different storage
;; or augmentation strategy. That is the basis of the library's open
;; representational polymorphism: collection constructors choose a comparator
;; and node constructor, then the shared tree algorithms operate uniformly over
;; that representation.

(defn node-create-weight-balanced
  "Join left and right weight-balanced subtrees at root k/v.
  Assumes all keys in l < k < all keys in r."
  [k v l r]
  (node/->SimpleNode k v l r (+ 1 (node-size l) (node-size r))))

(defn node-create-weight-balanced-long
  "Join left and right weight-balanced subtrees at primitive long root k/v.
  Specialized for Long keys - avoids boxing overhead.
  Assumes all keys in l < k < all keys in r."
  [k v l r]
  (node/->LongKeyNode (long k) v l r (+ 1 (node-size l) (node-size r))))

(defn node-create-weight-balanced-double
  "Join left and right weight-balanced subtrees at primitive double root k/v.
  Specialized for Double keys - avoids boxing overhead.
  Assumes all keys in l < k < all keys in r."
  [k v l r]
  (node/->DoubleKeyNode (double k) v l r (+ 1 (node-size l) (node-size r))))

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
;;
;; Enumerators provide efficient partial (lazy) in-order traversal of tree
;; structure without materializing the entire sequence upfront. They work by
;; decomposing the tree into a "left spine" — a linked list of frames, each
;; holding a node and its unvisited subtree.
;;
;; CONCEPT: Left Spine Decomposition
;;
;; Given this tree:
;;
;;                    ,---,
;;                    | 4 |
;;                    :---:
;;                   :     :
;;              ,---:       :---,
;;              | 2 |       | 6 |
;;              :---:       :---:
;;             :     :     :     :
;;         ,--:   :--,  ,--:   :--,
;;         |1 |   |3 |  |5 |   |7 |
;;         '--'   '--'  '--'   '--'
;;
;; The forward enumerator walks down the LEFT spine, building a chain of
;; EnumFrames. Each frame saves (node, right-subtree, next-frame):
;;
;;     node-enumerator(4)
;;
;;     [1 | nil | next] -> [2 | subtree 3 | next] -> [4 | subtree 6 | nil]
;;
;; The head of the chain is the current element. Each frame is:
;;   [node | saved-right-subtree | next-frame]
;;
;; TRAVERSAL:
;;
;;   1. node-enum-first returns the current node (head of spine)
;;
;;   2. node-enum-rest advances by:
;;      - Taking the saved right-subtree
;;      - Recursively building a new left spine from it
;;      - Continuing with the next frame
;;
;;      After visiting node 1:
;;        subtree=nil, next=Frame(2,...)
;;        → returns Frame(2,...) directly (no subtree to enumerate)
;;
;;      After visiting node 2:
;;        subtree=3, next=Frame(4,...)
;;        → enumerate the left spine of subtree 3 and link it to Frame(4,...)
;;
;;          before: [2 | subtree 3 | next -> [4 | subtree 6 | nil]]
;;          after:  [3 | nil      | next -> [4 | subtree 6 | nil]]
;;
;; This produces the in-order sequence: 1, 2, 3, 4, 5, 6, 7
;;
;; The reverse enumerator (node-enumerator-reverse) works symmetrically,
;; walking down the RIGHT spine and saving left subtrees.
;;
;; EFFICIENCY:
;;
;; - O(1) to get current node
;; - O(log n) amortized per advance (each node visited once across full traversal)
;; - O(log n) space (depth of spine = tree height)
;; - Lazy: only materializes nodes as needed
;;
;; EnumFrame is a simple deftype triple that avoids the allocation overhead
;; of persistent lists (1 object vs 3 cons cells per frame).

(deftype EnumFrame [node subtree next])

(defn node-enumerator
  "Efficient mechanism to accomplish partial enumeration of
   tree-structure into a seq representation without incurring the
   overhead of operating over the entire tree. Used internally for
   implementation of higher-level collection api routines.

   Returns an EnumFrame representing the leftmost spine of the tree,
   where each frame holds (current-node, right-subtree, next-frame)."
  ([n] (node-enumerator n nil))
  ([n enum]
   (if (leaf? n)
     enum
     (recur (-l n) (EnumFrame. n (-r n) enum)))))

(defn node-enumerator-reverse
  "Reverse enumerator: builds rightmost spine where each frame holds
   (current-node, left-subtree, next-frame)."
  ([n] (node-enumerator-reverse n nil))
  ([n enum]
   (if (leaf? n)
     enum
     (recur (-r n) (EnumFrame. n (-l n) enum)))))

(defn node-enum-first
  "Return the current node from an enumerator frame."
  [^EnumFrame enum]
  (.-node enum))

(defn node-enum-rest
  "Advance forward enumerator to the next node."
  [enum]
  (when (some? enum)
    (let [^EnumFrame ef enum
          subtree (.-subtree ef)
          next    (.-next ef)]
      (when-not (and (nil? subtree) (nil? next))
        (node-enumerator subtree next)))))

(defn node-enum-prior
  "Advance reverse enumerator to the next (prior) node."
  [enum]
  (when (some? enum)
    (let [^EnumFrame ef enum
          subtree (.-subtree ef)
          next    (.-next ef)]
      (when-not (and (nil? subtree) (nil? next))
        (node-enumerator-reverse subtree next)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree Rotations (Weight Balanced)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; macros because they are directly in the hot path.

(defmacro rotate-single-left
  "Single left rotation. Move Y (the left subtree of the right subtree of A)
  into the left subtree. Required when: weight(X) < δ × weight(B) and
  weight(Y) < γ × weight(Z).

                ,---,                                  ,---,
                | A |                                  | B |
                :---:                                  :---:
               :     :                                :     :
          ,---:       :---,                      ,---:       :---,
          | X |       | B |           =>         | A |       | Z |
          '---'       :---:                      :---:       '---'
                 ,---:     :---,            ,---:     :---,
                 | Y |     | Z |            | X |     | Y |
                 '---'     '---'            '---'     '---'
  "
  [create ak av x b]
  `(let [b# ~b
         bk# (-k b#) bv# (-v b#) y# (-l b#) z# (-r b#)]
     (~create bk# bv# (~create ~ak ~av ~x y#) z#)))

(defmacro rotate-double-left
  "Double left rotation. Move Y1 (the left subtree of B, which is the left
  subtree of C, which is the right subtree of A) into the left subtree.
  Required when: weight(X) < δ × weight(C) and weight(Y) >= γ × weight(Z).

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
              '---'     '---'
  "
  [create ak av x c]
  `(let [c# ~c
         ck# (-k c#) cv# (-v c#) b# (-l c#) z# (-r c#)
         bk# (-k b#) bv# (-v b#) y1# (-l b#) y2# (-r b#)]
     (~create bk# bv# (~create ~ak ~av ~x y1#) (~create ck# cv# y2# z#))))

(defmacro rotate-single-right
  "Single right rotation. Move Y (the right subtree of the left subtree of B)
  into the right subtree. Required when: weight(Z) < δ × weight(A) and
  weight(Y) < γ × weight(X).

                ,---,                                  ,---,
                | B |                                  | A |
                :---:                                  :---:
               :     :                                :     :
          ,---:       :---,                      ,---:       :---,
          | A |       | Z |          =>          | X |       | B |
          :---:       '---'                      '---'       :---:
     ,---:     :---,                                    ,---:     :---,
     | X |     | Y |                                    | Y |     | Z |
     '---'     '---'                                    '---'     '---'
  "
  [create bk bv a z]
  `(let [a# ~a
         ak# (-k a#) av# (-v a#) x# (-l a#) y# (-r a#)]
     (~create ak# av# x# (~create ~bk ~bv y# ~z))))

(defmacro rotate-double-right
  "Double right rotation. Move Y2 (the right subtree of B, which is the right
  subtree of A, which is the left subtree of C) into the right subtree.
  Required when: weight(Z) < δ × weight(A) and weight(Y) >= γ × weight(X).

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
        '---'     '---'
  "
  [create ck cv a z]
  `(let [a# ~a
         ak# (-k a#) av# (-v a#) x# (-l a#) b# (-r a#)
         bk# (-k b#) bv# (-v b#) y1# (-l b#) y2# (-r b#)]
     (~create bk# bv# (~create ak# av# x# y1#) (~create ~ck ~cv y2# ~z))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stitch - the fundamental balancing constructor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-stitch
  "Join left and right subtrees at root k/v, performing a single or double
  rotation to restore weight balance if needed.

  This is the tree's fundamental balancing constructor. It assumes all keys
  in `l` are less than `k`, all keys in `r` are greater than `k`, and that
  at most one single or double rotation is needed to restore balance.

  The 5-arity form takes an explicit node constructor as its final argument
  and is used in hot internal paths to avoid dynamic-var indirection. The
  4-arity form uses the current `*t-join*` binding."
  ([k v l r]
   (node-stitch k v l r *t-join*))
  ([k v l r create]
   (let [lw (node-weight l)
         rw (node-weight r)]
     (cond
       ;; Right-heavy: rotate left
       (> rw (* +delta+ lw))
       (let [rl  (-l r)
             rlw (node-weight rl)
             rrw (node-weight (-r r))]
         (if (< rlw (* +gamma+ rrw))
           (rotate-single-left create k v l r)
           (rotate-double-left create k v l r)))

       ;; Left-heavy: rotate right
       (> lw (* +delta+ rw))
       (let [lr  (-r l)
             llw (node-weight (-l l))
             lrw (node-weight lr)]
         (if (< lrw (* +gamma+ llw))
           (rotate-single-right create k v l r)
           (rotate-double-right create k v l r)))

       ;; Balanced
       :else
       (create k v l r)))))

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
                   (cond
                     (zero? c) (create key v l r)
                     (neg? c)  (node-stitch key val (add l) r create)
                     :else     (node-stitch key val l (add r) create))))))]
     (add n))))

(defn node-add-if-absent
  "Insert key/value only if key doesn't exist. Returns new tree or nil if key exists.
   Single traversal - more efficient than contains? + add for assocEx."
  ([n k v ^Comparator cmp create]
   (letfn [(add [n]
             (if (leaf? n)
               (create k v (leaf) (leaf))
               (kvlr [key val l r] n
                 (let [c (.compare cmp k key)]
                   (cond
                     (zero? c) nil  ; key exists, signal failure
                     (neg? c)  (when-let [new-l (add l)]
                                 (node-stitch key val new-l r create))
                     :else     (when-let [new-r (add r)]
                                 (node-stitch key val l new-r create)))))))]
     (add n))))

(defn node-concat3
  "Join two trees, the left rooted at l, and the right at r,
  with a new key/value, performing rotation operations on the resulting
  trees and subtrees. Assumes all keys in l are smaller than all keys in
  r, and the relative balance of l and r is such that no more than one
  rotation operation will be required to balance the resulting tree."
  ([k v l r]
   (node-concat3 k v l r order/*compare* *t-join*))
  ([k v l r ^Comparator cmp create]
   (letfn [(add [n]
             (if (leaf? n)
               (create k v (leaf) (leaf))
               (kvlr [key val l r] n
                 (let [c (.compare cmp k key)]
                   (cond
                     (zero? c) (create key v l r)
                     (neg? c)  (node-stitch key val (add l) r create)
                     :else     (node-stitch key val l (add r) create))))))
           (cat3 [k v l r]
             (cond
               (leaf? l) (add r)
               (leaf? r) (add l)
               true      (let [lw (node-weight l)
                               rw (node-weight r)]
                           (cond
                             (< (* +delta+ lw) rw) (kvlr [k2 v2 l2 r2] r
                                                     (node-stitch k2 v2
                                                       (cat3 k v l l2) r2 create))
                             (< (* +delta+ rw) lw) (kvlr [k1 v1 l1 r1] l
                                                     (node-stitch k1 v1 l1
                                                       (cat3 k v r1 r) create))
                             true                  (create k v l r)))))]
     (cat3 k v l r))))

(defn node-least-kv
  "Return [k v] for the minimum key of the tree rooted at n."
  [n]
  (cond
    (leaf? n)      (throw (ex-info "least: empty tree" {:node n}))
    (leaf? (-l n)) [(-k n) (-v n)]
    :else          (recur (-l n))))

(defn node-least
  "Return the node containing the minimum key of the tree rooted at n."
  [n]
  (cond
    (leaf? n)      (throw (ex-info "least: empty tree" {:node n}))
    (leaf? (-l n)) n
    :else          (recur (-l n))))

(defn node-greatest-kv
  "Return [k v] for the maximum key of the tree rooted at n."
  [n]
  (cond
    (leaf? n)      (throw (ex-info "greatest: empty tree" {:node n}))
    (leaf? (-r n)) [(-k n) (-v n)]
    :else          (recur (-r n))))

(defn node-greatest
  "Return the node containing the maximum key of the tree rooted at n."
  [n]
  (cond
    (leaf? n)      (throw (ex-info "greatest: empty tree" {:node n}))
    (leaf? (-r n)) n
    :else          (recur (-r n))))

(defn node-remove-least
  "Return a tree the same as the one rooted at n, with the node
  containing the minimum key removed. See node-least."
  ([n]
   (node-remove-least n *t-join*))
  ([n create]
   (letfn [(rm-least [n]
             (cond
               (leaf? n)      (throw (ex-info "remove-least: empty tree" {:node n}))
               (leaf? (-l n)) (-r n)
               :else          (node-stitch (-k n) (-v n)
                                (rm-least (-l n)) (-r n) create)))]
     (rm-least n))))

(defn node-remove-greatest
  "Return a tree the same as the one rooted at n, with the node
  containing the maximum key removed. See node-greatest."
  [n]
  (let [create *t-join*]
    (letfn [(rm-greatest [n]
              (cond
                (leaf? n)      (throw (ex-info "remove-greatest: empty tree" {:node n}))
                (leaf? (-r n)) (-l n)
                :else          (node-stitch (-k n) (-v n) (-l n)
                                 (rm-greatest (-r n)) create)))]
      (rm-greatest n))))

(defn node-concat2
  "Join two trees, the left rooted at l, and the right at r,
  performing a single balancing operation on the resulting tree, if
  needed. Assumes all keys in l are smaller than all keys in r, and
  the relative balance of l and r is such that no more than one rotation
  operation will be required to balance the resulting tree."
  ([l r]
   (node-concat2 l r *t-join*))
  ([l r create]
   (cond
     (leaf? l) r
     (leaf? r) l
     :else     (let [[k v] (node-least-kv r)]
                 (node-stitch k v l (node-remove-least r create) create)))))

(defn node-remove
  "remove the node whose key is equal to k, if present."
  ([n k]
   (node-remove n k order/*compare* *t-join*))
  ([n k ^Comparator cmp create]
   (letfn [(rm [n]
             (if (leaf? n)
               (leaf)
               (kvlr [key val l r] n
                 (let [c (.compare cmp k key)]
                   (cond
                     (zero? c) (node-concat2 l r create)
                     (neg? c)  (node-stitch key val (rm l) r create)
                     :else     (node-stitch key val l (rm r) create))))))]
     (rm n))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lookup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; These are the hottest paths in the library. Every lookup, contains?, and get
;; operation flows through here. Optimizations applied:
;;
;; 1. Use definline for zero-overhead accessor calls
;; 2. Avoid dynamic var lookup - always pass comparator explicitly
;; 3. Minimize branching in the loop
;; 4. Type hints to avoid reflection
;; 5. Primitive specializations for Long keys bypass Comparator entirely
;;
;; PERFORMANCE NOTE: The 3-arity versions with explicit ^Comparator are the
;; fast path. The 2-arity versions that use order/*compare* have ~200ns
;; overhead per call from dynamic binding lookup.
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Primitive-specialized lookup for Long keys.
;; Bypasses Comparator dispatch entirely by using Long/compare directly.
;; This is ~30% faster than going through the Comparator interface.

(defn node-contains-long?
  "Primitive-specialized membership check for Long keys.

  Use this when the caller only needs a boolean presence test. It avoids both
  Comparator dispatch and the broader node-returning contract of `node-find-long`."
  [n ^long k]
  (loop [n n]
    (if (leaf? n)
      false
      (let [nk (long (-k n))
            c (Long/compare k nk)]
        (if (zero? c) true (recur (if (neg? c) (-l n) (-r n))))))))

(defn node-find-long
  "Primitive-specialized node lookup for Long keys.

  Use this when the caller needs the matching node rather than just a boolean.
  Kept separate from `node-contains-long?` so membership-only call sites can stay
  on the minimal boolean path."
  ([n ^long k]
   (node-find-long n k nil))
  ([n ^long k not-found]
   (loop [n n]
     (if (leaf? n)
       not-found
       (let [nk (long (-k n))
             c (Long/compare k nk)]
         (if (zero? c) n (recur (if (neg? c) (-l n) (-r n)))))))))

(defn node-find-val-long
  "Primitive-specialized value lookup for Long keys.

  Use this when the caller needs only the associated value (or `not-found`),
  without materializing the broader node-returning path."
  [n ^long k not-found]
  (loop [n n]
    (if (leaf? n)
      not-found
      (let [nk (long (-k n))
            c (Long/compare k nk)]
        (if (zero? c) (-v n) (recur (if (neg? c) (-l n) (-r n))))))))

;; String-specialized lookup functions.
;; Uses String.compareTo directly, avoiding Comparator dispatch.

(defn node-contains-string?
  "String-specialized membership check.

  Use this when the caller only needs presence/absence. It uses
  `String.compareTo` directly and avoids the broader node-returning contract of
  `node-find-string`."
  [n ^String k]
  (loop [n n]
    (if (leaf? n)
      false
      (let [c (.compareTo k ^String (-k n))]
        (if (zero? c) true (recur (if (neg? c) (-l n) (-r n))))))))

(defn node-find-string
  "String-specialized node lookup.

  Use this when the caller needs the matching node rather than just a boolean.
  Kept separate from `node-contains-string?` so membership-only call sites can
  stay on the minimal boolean path."
  ([n ^String k]
   (node-find-string n k nil))
  ([n ^String k not-found]
   (loop [n n]
     (if (leaf? n)
       not-found
       (let [c (.compareTo k ^String (-k n))]
         (if (zero? c) n (recur (if (neg? c) (-l n) (-r n)))))))))

(defn node-find-val-string
  "String-specialized value lookup.

  Use this when the caller needs only the associated value (or `not-found`),
  without materializing the broader node-returning path."
  [n ^String k not-found]
  (loop [n n]
    (if (leaf? n)
      not-found
      (let [c (.compareTo k ^String (-k n))]
        (if (zero? c) (-v n) (recur (if (neg? c) (-l n) (-r n))))))))

(defn node-find
  "find a node in n whose key = k.
   Returns a node implementing INode, or `not-found` when absent.

   Arity notes:
   - `(node-find n k)` uses the current dynamic comparator
   - `(node-find n k cmp)` uses an explicit comparator
   - `(node-find n k not-found cmp)` uses both an explicit fallback and comparator"
  ([n k]
   (node-find n k nil order/*compare*))
  ([n k ^Comparator cmp]
   (node-find n k nil cmp))
  ([n k not-found ^Comparator cmp]
   (loop [n n]
     (if (leaf? n)
       not-found
       (let [c (.compare cmp k (-k n))]
         (if (zero? c) n (recur (if (neg? c) (-l n) (-r n)))))))))

(defn node-find-val
  "Find value for key k in tree. Returns the value or not-found."
  ([n k not-found]
   (node-find-val n k not-found order/*compare*))
  ([n k not-found ^Comparator cmp]
   (loop [n n]
     (if (leaf? n)
       not-found
       (let [c (.compare cmp k (-k n))]
         (if (zero? c) (-v n) (recur (if (neg? c) (-l n) (-r n)))))))))

(defn node-contains?
  "Check if key k exists in tree."
  ([n k]
   (node-contains? n k order/*compare*))
  ([n k ^Comparator cmp]
   (loop [n n]
     (if (leaf? n)
       false
       (let [c (.compare cmp k (-k n))]
         (if (zero? c) true (recur (if (neg? c) (-l n) (-r n)))))))))

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

(defn node-predecessor
  "Find the predecessor of key k (greatest element strictly less than k).
   Returns the node, or nil if no predecessor exists.
   O(log n) - single traversal that tracks the last right turn."
  [n k]
  (let [^Comparator cmp order/*compare*]
    (loop [n n
           predecessor nil]
      (if (leaf? n)
        predecessor
        (let [c (.compare cmp k (-k n))]
          (cond
            ;; k < current: go left, predecessor unchanged
            (neg? c) (recur (-l n) predecessor)
            ;; k > current: current is potential predecessor, go right
            (pos? c) (recur (-r n) n)
            ;; k = current: predecessor is max of left subtree, if any
            :else (if (leaf? (-l n))
                    predecessor
                    (node-greatest (-l n)))))))))

(defn node-successor
  "Find the successor of key k (least element strictly greater than k).
   Returns the node, or nil if no successor exists.
   O(log n) - single traversal that tracks the last left turn."
  [n k]
  (let [^Comparator cmp order/*compare*]
    (loop [n n
           successor nil]
      (if (leaf? n)
        successor
        (let [c (.compare cmp k (-k n))]
          (cond
            ;; k > current: go right, successor unchanged
            (pos? c) (recur (-r n) successor)
            ;; k < current: current is potential successor, go left
            (neg? c) (recur (-l n) n)
            ;; k = current: successor is min of right subtree, if any
            :else (if (leaf? (-r n))
                    successor
                    (node-least (-r n)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Tree Augmentation and Search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; AUGMENTED INTERVAL TREE
;;
;; An interval tree stores intervals [a,b] and supports efficient queries for
;; all intervals that overlap a given point or interval. The key insight is
;; the -z augmentation: each node stores the MAXIMUM ENDPOINT of all intervals
;; in its subtree.
;;
;; NODE STRUCTURE (IntervalNode):
;;
;;   -k : interval [a,b] — the key, sorted by start point 'a'
;;   -v : associated value
;;   -l : left subtree  (intervals with smaller start points)
;;   -r : right subtree (intervals with larger start points)
;;   -x : subtree size (for weight balancing)
;;   -z : MAX endpoint 'b' across this node and all descendants
;;
;; EXAMPLE: Intervals [1,5], [3,8], [6,7], [4,9], [2,3]
;;
;; Sorted by start point and built into a balanced tree:
;;
;;                          ┌─────────────────┐
;;                          │ key: [3,8]      │
;;                          │ z: 9  ←─────────────── max(8, 5, 9) from subtree
;;                          └────────┬────────┘
;;                      ┌────────────┴────────────┐
;;              ┌───────┴───────┐         ┌───────┴───────┐
;;              │ key: [1,5]    │         │ key: [6,7]    │
;;              │ z: 5          │         │ z: 9          │
;;              └───────┬───────┘         └───────┬───────┘
;;                      │                 ┌───────┴───────┐
;;              ┌───────┴───────┐         │ key: [4,9]    │
;;              │ key: [2,3]    │         │ z: 9          │
;;              │ z: 3          │         └───────────────┘
;;              └───────────────┘
;;
;; SEARCH ALGORITHM (finding intervals that overlap query interval Q=[qa,qb]):
;;
;; Two intervals [a,b] and [qa,qb] overlap iff: a <= qb AND qa <= b
;;
;; The -z augmentation enables PRUNING:
;;
;;   1. PRUNE LEFT SUBTREE: If qa > left.-z, no interval in the left subtree
;;      can overlap Q (all endpoints are too small).
;;
;;   2. PRUNE RIGHT SUBTREE: If qb < node.key.a, no interval in the right
;;      subtree can overlap Q (all start points are too large).
;;
;; SEARCH WALKTHROUGH: Query Q=[5,6] on the tree above
;;
;;   At [3,8]: z=9
;;     • Right subtree: qb=6 >= key.a=3? Yes → search right
;;     • Check [3,8]: overlaps [5,6]? 3<=6 ∧ 5<=8 → YES, collect it
;;     • Left subtree: qa=5 <= left.z=5? Yes → search left
;;
;;   At [6,7]: z=9
;;     • Right subtree: qb=6 >= key.a=6? Yes → search right
;;     • Check [6,7]: overlaps [5,6]? 6<=6 ∧ 5<=7 → YES, collect it
;;     • Left subtree: (has child [4,9])
;;
;;   At [4,9]: z=9
;;     • Check [4,9]: overlaps [5,6]? 4<=6 ∧ 5<=9 → YES, collect it
;;
;;   At [1,5]: z=5
;;     • Right subtree: none
;;     • Check [1,5]: overlaps [5,6]? 1<=6 ∧ 5<=5 → YES, collect it
;;     • Left subtree: qa=5 <= left.z=3? No → PRUNE (skip [2,3])
;;
;; Result: [[3,8], [6,7], [4,9], [1,5]] — found 4 overlapping intervals,
;;         pruned [2,3] without examining it.
;;
;; COMPLEXITY: O(k + log n) where k = number of overlapping intervals.
;; The -z augmentation ensures we only visit nodes that could contain matches.

(defn- node-find-interval-fn [i pred]
  (let [i      (interval/ordered-pair i)
        result (volatile! nil)
        accum  (if pred
                 (fn [n] (vswap! result #(if (or (nil? %) (pred n %)) n %)))
                 (fn [n] (vswap! result conj n)))]
    (fn [n]
      (letfn [(srch [this]
                (when-not (leaf? this)
                  ;; Search right if query endpoint >= node's start point
                  (when (order/compare>= (interval/b i) (-> this -k interval/a))
                    (-> this -r srch))
                  ;; Check current node for intersection
                  (when (interval/intersects? i (-k this))
                    (accum this))
                  ;; Search left only if query start <= max endpoint in left subtree
                  (when (and (not (leaf? (-l this)))
                             (order/compare<= (interval/a i) (-> this -l -z)))
                    (-> this -l srch))))]
        (srch n)
        @result))))

(defn node-find-intervals [n i]
  ((node-find-interval-fn i nil) n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Iteration - `node-run!` et al. for eager side-effecting walks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-run!
  "Eagerly apply f to each node of the tree rooted at n, from least to greatest."
  [n f]
  (when-not (leaf? n)
    (lr [l r] n
      (node-run! l f)
      (f n)
      (node-run! r f))))

(defn node-run-kv!
  "Eagerly apply f to each key/value pair in the tree rooted at n, from least to greatest."
  [n f]
  (when-not (leaf? n)
    (lr [l r] n
      (node-run-kv! l f)
      (f (-k n) (-v n))
      (node-run-kv! r f))))

(defn node-run-reverse!
  "Eagerly apply f to each node of the tree rooted at n, from greatest to least."
  [n f]
  (when-not (leaf? n)
    (lr [l r] n
      (node-run-reverse! r f)
      (f n)
      (node-run-reverse! l f))))

(defn node-run-kv-reverse!
  "Eagerly apply f to each key/value pair in the tree rooted at n, from greatest to least."
  [n f]
  (when-not (leaf? n)
    (lr [l r] n
      (node-run-kv-reverse! r f)
      (f (-k n) (-v n))
      (node-run-kv-reverse! l f))))

(defmacro ^:private enum-count
  [enum next-fn]
  `(loop [e# ~enum n# 0]
     (if e#
       (recur (~next-fn e#) (unchecked-inc-int n#))
       n#)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reduction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Reduction is layered on top of enumerators.
;;
;; There are two distinct reduction shapes:
;;
;; 1. Unary reduction over a projected node value
;;    `(f acc x)`
;;    Used for:
;;      - nodes
;;      - keys
;;      - entries
;;    These share one implementation strategy:
;;      - direction is chosen by the enumerator step (`node-enum-rest` or
;;        `node-enum-prior`)
;;      - projection is chosen by a small function (`identity`, `-k`,
;;        `node-entry`)
;;
;; 2. Key/value reduction
;;    `(f acc k v)`
;;    This stays separate because it has no natural seeded 2-arity form, and
;;    forcing it through the unary projection layer would add avoidable packing.
;;
;; All reducers support `reduced` short-circuiting. The unary reducers share
;; the seeded and explicit-init enumerator kernels below.

(declare node-add node-nth node-split)

(defmacro ^:private enum-reduce-init
  "Shared enumerator reduction kernel for explicit initial values."
  [enum next-fn acc-sym init-expr node-sym step-expr]
  `(loop [e# ~enum ~acc-sym ~init-expr]
     (if (nil? e#)
       ~acc-sym
       (let [~node-sym (node-enum-first e#)
             ret# ~step-expr]
         (if (reduced? ret#)
           @ret#
           (recur (~next-fn e#) ret#))))))

(defmacro ^:private enum-reduce-first
  "Shared enumerator reduction kernel for the 1-arity IReduce case, where the
   first element seeds the accumulator and empty enumerations call (f)."
  [enum-expr next-fn acc-sym node-sym seed-expr step-expr empty-expr]
  `(if-let [e0# ~enum-expr]
     (let [~node-sym (node-enum-first e0#)]
       (loop [e# (~next-fn e0#) ~acc-sym ~seed-expr]
         (if (nil? e#)
           ~acc-sym
           (let [~node-sym (node-enum-first e#)
             ret# ~step-expr]
             (if (reduced? ret#)
               @ret#
               (recur (~next-fn e#) ret#))))))
     ~empty-expr))

(defn- make-unary-reducer
  "Build a reducer over nodes traversed by `enum-fn` / `next-fn`, projecting
   each visited node through `project` before calling `f`."
  [enum-fn next-fn project]
  (fn
    ([f init root]
     (enum-reduce-init (enum-fn root) next-fn acc init n
       (f acc (project n))))
    ([f root]
     (enum-reduce-first (enum-fn root) next-fn acc n (project n)
       (f acc (project n))
       (f)))))

(defn- node-entry [n]
  (clojure.lang.MapEntry. (-k n) (-v n)))

(def node-reduce
  (make-unary-reducer node-enumerator node-enum-rest identity))

(def node-reduce-right
  (make-unary-reducer node-enumerator-reverse node-enum-prior identity))

(def node-reduce-keys
  (make-unary-reducer node-enumerator node-enum-rest -k))

(def node-reduce-keys-right
  (make-unary-reducer node-enumerator-reverse node-enum-prior -k))

(def node-reduce-entries
  (make-unary-reducer node-enumerator node-enum-rest node-entry))

(def node-reduce-entries-right
  (make-unary-reducer node-enumerator-reverse node-enum-prior node-entry))

(defn node-reduce-kv
  "Reduce tree key/value pairs from least to greatest via (f acc k v).
   Supports early termination via clojure.core/reduced."
  [f init root]
  (letfn [(reduce-node [acc n]
            (cond
              (leaf? n) acc
              (reduced? acc) acc
              :else
              (lr [l r] n
                (let [acc (reduce-node acc l)]
                  (if (reduced? acc)
                    acc
                    (let [acc (f acc (-k n) (-v n))]
                      (if (reduced? acc)
                        acc
                        (reduce-node acc r))))))))]
    (let [result (reduce-node init root)]
      (if (reduced? result) @result result))))

(defn node-reduce-kv-right
  "Reduce tree key/value pairs from greatest to least via (f acc k v).
   Supports early termination via clojure.core/reduced."
  [f init root]
  (letfn [(reduce-node [acc n]
            (cond
              (leaf? n) acc
              (reduced? acc) acc
              :else
              (lr [l r] n
                (let [acc (reduce-node acc r)]
                  (if (reduced? acc)
                    acc
                    (let [acc (f acc (-k n) (-v n))]
                      (if (reduced? acc)
                        acc
                        (reduce-node acc l))))))))]
    (let [result (reduce-node init root)]
      (if (reduced? result) @result result))))

(defn node-chunks
  "Partition tree `n` into roughly equal subtrees of about `chunk-size`
  elements."
  [^long chunk-size n]
  (let [sz         (node-size n)
        num-chunks (max 2 (quot sz chunk-size))]
    (loop [i         1
           remaining n
           result    []]
      (if (or (= i num-chunks) (leaf? remaining))
        (conj result remaining)
        (let [pos       (long (* sz (/ (double i) num-chunks)))
              split-key (-k (node-nth n pos))
              [lt present gt] (node-split remaining split-key)
              gt (if present
                   (node-add gt (first present) (second present))
                   gt)]
          (recur (inc i) gt (conj result lt)))))))

(defn node-fold
  "Parallel fold to support clojure.core.reducers/CollFold.

   Splits the tree into chunks, then folds them in parallel via r/fold.
   Splitting is done eagerly in the caller's thread (where dynamic bindings
   are available); each chunk's sequential reduce uses node-reduce which
   needs no bindings."
  [^long chunk-size n combinef reducef]
  {:pre [(pos? chunk-size)]}
  (let [sz (node-size n)]
    (if (<= sz chunk-size)
      (node-reduce reducef (combinef) n)
      (let [subtrees    (node-chunks chunk-size n)
            rf (fn [_ ^long idx]
                 (node-reduce reducef (combinef) (nth subtrees idx)))]
        (r/fold 1 combinef rf (vec (range (count subtrees))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree Health
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-healthy?
  "verify node `n` and all descendants satisfy the node-invariants
  of a weight-balanced binary tree."
  [n]
  (if (leaf? n)
    true
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
;;
;; SPLIT OPERATION
;;
;; `node-split` decomposes a tree at a pivot key k into three parts:
;;   (L, present, R)
;;
;; Where:
;;   L       = tree of all elements < k
;;   present = nil if k not found, or (k v) if found
;;   R       = tree of all elements > k
;;
;;
;; COMPLEXITY: O(log n) — each recursive call descends one level
;;
;; WHY IT MATTERS: Split is the foundation for efficient set operations.
;; Instead of element-by-element insertion (O(n log n)), we can implement
;; union, intersection, and difference in O(n) time using divide-and-conquer.

(defn- node-split-lesser*
  [n k ^Comparator cmp create]
  (loop [n n]
    (if (leaf? n)
      n
      (kvlr [kn vn ln rn] n
        (let [c (.compare cmp k kn)]
          (cond
            (zero? c) ln
            (neg? c)  (recur ln)
            :else     (node-concat3 kn vn ln (node-split-lesser* rn k cmp create) cmp create)))))))

(defn node-split-lesser
  "return a tree of all nodes whose key is less than k (Logarithmic time)."
  [n k]
  (node-split-lesser* n k order/*compare* *t-join*))

(defn- node-split-greater*
  [n k ^Comparator cmp create]
  (loop [n n]
    (if (leaf? n)
      n
      (kvlr [kn vn ln rn] n
        (let [c (.compare cmp k kn)]
          (cond
            (zero? c) rn
            (neg? c)  (node-concat3 kn vn (node-split-greater* ln k cmp create) rn cmp create)
            :else     (recur rn)))))))

(defn node-split-greater
  "return a tree of all nodes whose key is greater than k (Logarithmic time)."
  [n k]
  (node-split-greater* n k order/*compare* *t-join*))

(defn- node-split*
  [n k ^Comparator cmp create]
  (letfn [(split [n]
            (if (leaf? n)
              [nil nil nil]
              (kvlr [ak v l r] n
                (let [c (.compare cmp k ak)]
                  (cond
                    (zero? c) [l (list k v) r]
                    (neg? c)  (let [[ll pres rl] (split l)]
                                [ll pres (node-concat3 ak v rl r cmp create)])
                    :else     (let [[lr pres rr] (split r)]
                                [(node-concat3 ak v l lr cmp create) pres rr]))))))]
    (split n)))

(defn node-split
  "returns a triple (l present r) where: l is the set of elements of
  n that are < k, r is the set of elements of n that are > k, present
  is false if n contains no element equal to k, or (k v) if n contains
  an element with key equal to k."
  [n k]
  (node-split* n k order/*compare* *t-join*))

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
        (nil? e1)                  -1
        (nil? e2)                   1
        :else
        (let [^EnumFrame ef1 e1
              ^EnumFrame ef2 e2
              x1   (.-node ef1)
              x2   (.-node ef2)
              val1 (acc-fn x1)
              val2 (acc-fn x2)
              c    (.compare cmp val1 val2)]
          (if-not (zero? c)
            c
            (recur (node-enumerator (.-subtree ef1) (.-next ef1))
                   (node-enumerator (.-subtree ef2) (.-next ef2)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Set Operations (Worst-Case Linear Time)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; SET OPERATIONS VIA DIVIDE-AND-CONQUER
;;
;; Union, intersection, and difference are implemented using a powerful
;; divide-and-conquer strategy based on `node-split`. This achieves O(m+n)
;; time complexity instead of the naive O(m log n) element-by-element approach.
;;
;; THE PATTERN (using union as example):
;;
;;   union(T1, T2):
;;     if T1 is empty: return T2
;;     if T2 is empty: return T1
;;
;;     Pick the root of T2: key=k, left=L2, right=R2
;;     Split T1 at k:       (L1, _, R1) = split(T1, k)
;;
;;     Recursively:
;;       left-result  = union(L1, L2)    ← elements < k from both trees
;;       right-result = union(R1, R2)    ← elements > k from both trees
;;
;;     Combine: concat3(k, v, left-result, right-result)
;;
;; VISUAL: union of {1,3,5} and {2,3,4}
;;
;;         T1              T2
;;        ,---,           ,---,
;;        | 3 |           | 3 |
;;        :---:           :---:
;;       :     :         :     :
;;    ,-:       :-,   ,-:       :-,
;;    |1 |     |5 |   |2 |     |4 |
;;    '--'     '--'   '--'     '--'
;;
;;   Step 1: Split T1 at T2's root (3)
;;           L1={1}, present=(3,v), R1={5}
;;
;;   Step 2: Recurse
;;           union({1}, {2}) → {1,2}
;;           union({5}, {4}) → {4,5}
;;
;;   Step 3: Combine
;;           concat3(3, v, {1,2}, {4,5}) → {1,2,3,4,5}
;;
;;
;; INTERSECTION works similarly but only keeps k if present in BOTH trees:
;;
;;   intersection(T1, T2):
;;     Split T1 at T2's root k: (L1, present, R1)
;;     If present:  concat3(k, v, intersect(L1,L2), intersect(R1,R2))
;;     If absent:   concat2(intersect(L1,L2), intersect(R1,R2))
;;                           └─ no middle element to join with
;;
;; DIFFERENCE removes elements of T2 from T1:
;;
;;   difference(T1, T2):
;;     Split T1 at T2's root k: (L1, _, R1)
;;     concat2(difference(L1,L2), difference(R1,R2))
;;             └─ always concat2, never include k (it's in T2)
;;
;; WHY O(m+n)?
;;
;; Each node from both trees is visited exactly once. The split and concat3
;; operations are O(log n), but the total work across all recursive calls
;; telescopes to O(m+n) because:
;;   - Each split divides T1 into disjoint parts
;;   - Each element participates in only O(1) concat3 operations
;;
;; This is the "Adams' algorithm" from the 1992 paper, refined by many others.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Explicit-Argument Algebra Hot Paths
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; The public tree algebra functions are intentionally dynamic-var friendly,
;; but the recursive set/map kernels are hot enough that repeated lookups of
;; `order/*compare*` and `*t-join*` are measurable overhead. These private
;; variants thread the comparator and node constructor explicitly so the caller
;; can capture them once and reuse them through the whole recursion. This keeps
;; the extensible dynamic-binding architecture at the public boundary while
;; avoiding paying for it at every recursive step in the hottest paths.

(defn- node-set-union*
  [n1 n2 ^Comparator cmp create]
  (cond
    (leaf? n1) n2
    (leaf? n2) n1
    :else      (kvlr [ak av l r] n2
                 (let [[l1 _ r1] (node-split* n1 ak cmp create)]
                   (node-concat3 ak av
                                  (node-set-union* l1 l cmp create)
                                  (node-set-union* r1 r cmp create)
                                  cmp create)))))

(defn- node-set-intersection*
  [n1 n2 ^Comparator cmp create]
  (cond
    (leaf? n1) (leaf)
    (leaf? n2) (leaf)
    :else      (kvlr [ak av l r] n2
                 (let [[l1 x r1] (node-split* n1 ak cmp create)]
                   (if x
                     (node-concat3 ak av
                                    (node-set-intersection* l1 l cmp create)
                                    (node-set-intersection* r1 r cmp create)
                                    cmp create)
                     (node-concat2 (node-set-intersection* l1 l cmp create)
                                    (node-set-intersection* r1 r cmp create)
                                    create))))))

(defn- node-set-difference*
  [n1 n2 ^Comparator cmp create]
  (cond
    (leaf? n1) (leaf)
    (leaf? n2) n1
    :else      (kvlr [ak _ l r] n2
                 (let [[l1 _ r1] (node-split* n1 ak cmp create)]
                   (node-concat2 (node-set-difference* l1 l cmp create)
                                  (node-set-difference* r1 r cmp create)
                                  create)))))

(defn- node-map-merge*
  [n1 n2 merge-fn ^Comparator cmp create]
  (cond
    (leaf? n1) n2
    (leaf? n2) n1
    :else      (kvlr [ak av l r] n2
                 (let [[l1 x r1] (node-split* n1 ak cmp create)
                       val       (if x
                                   (merge-fn ak av (second x))
                                   av)]
                   (node-concat3 ak val
                                  (node-map-merge* l1 l merge-fn cmp create)
                                  (node-map-merge* r1 r merge-fn cmp create)
                                  cmp create)))))

(defn node-set-union
  "set union"
  [n1 n2]
  (node-set-union* n1 n2 order/*compare* *t-join*))

(defn node-set-intersection
  "set intersection"
  [n1 n2]
  (node-set-intersection* n1 n2 order/*compare* *t-join*))

(defn node-set-difference
  "set difference"
  [n1 n2]
  (node-set-difference* n1 n2 order/*compare* *t-join*))

(defn node-subset?
  "return true if `sub` is a subset of `super`"
  [super sub]
  (let [^Comparator cmp order/*compare*]
    (letfn [(subset? [n1 n2]
              (cond
                (leaf? n1) true
                (leaf? n2) false
                :else
                (and
                  (<= (node-size n1) (node-size n2))
                  (kvlr [k1 _ l1 r1] n1
                    (kvlr [k2 _ l2 r2] n2
                      (let [c (.compare cmp k1 k2)]
                        (cond
                          (zero? c) (and (subset? l1 l2) (subset? r1 r2))
                          (neg? c)  (and (subset? l1 l2) (node-find n2 k1 cmp) (subset? r1 n2))
                          :else     (and (subset? r1 r2) (node-find n2 k1 cmp) (subset? l1 n2)))))))))]
      (or (leaf? sub) (boolean (subset? sub super))))))

(defn node-disjoint?
  "Return true if the two trees share no elements.
   Short-circuits on the first common element found.
   Complexity: O(m log(n/m + 1)) where m <= n."
  [n1 n2]
  (cond
    (leaf? n1) true
    (leaf? n2) true
    :else
    (kvlr [k _ l r] n1
      (let [[bl present br] (node-split n2 k)]
        (if present
          false
          (and (node-disjoint? l bl)
               (node-disjoint? r br)))))))

(def node-set-compare (partial node-compare :k))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parallel Set Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; These implementations use Java's ForkJoinPool for efficient work-stealing
;; parallelism. The algorithms are based on:
;;
;;   Blelloch, Ferizovic, Sun (2016, 2022)
;;   "Just Join for Parallel Ordered Sets" / "Joinable Parallel Balanced Binary Trees"
;;   SPAA '16, TOPC '22
;;
;; Key insight: All set operations reduce to split + recursive operation + join.
;; The divide-and-conquer structure naturally maps to fork-join parallelism.
;;
;; Performance characteristics:
;;   - Work: O(m log(n/m + 1)) where m <= n
;;   - Span: O(log^2 n) - polylogarithmic, enabling high parallelism
;;   - Scalability: Linear speedup up to O(n/log^2 n) processors
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Root-entry thresholds for the parallel path. These are operation-specific
;; because the current benchmark data does not support one universal crossover:
;; comparator cost and result reconstruction differ enough between union,
;; intersection, difference, and merge that a single conservative threshold
;; would leave many practical wins on the table.
(def ^:const ^long +parallel-union-root-threshold+ 131072)
(def ^:const ^long +parallel-intersection-root-threshold+ 65536)
(def ^:const ^long +parallel-difference-root-threshold+ 131072)
(def ^:const ^long +parallel-merge-root-threshold+ 65536)

;; Recursive re-fork thresholds, again kept operation-specific. Once we have
;; paid the top-level entry cost and the spawn guard has filtered out lopsided
;; subproblems, the benchmark evidence supports lower cutoffs than a single
;; conservative recursive threshold.
(def ^:const ^long +parallel-union-recursive-threshold+ 65536)
(def ^:const ^long +parallel-intersection-recursive-threshold+ 65536)
(def ^:const ^long +parallel-difference-recursive-threshold+ 65536)
(def ^:const ^long +parallel-merge-recursive-threshold+ 65536)

;; Once we are in the parallel path, only fork a recursive split when both
;; branches are substantive enough to amortize task overhead. This keeps the
;; recursive kernels from spawning on highly lopsided splits, where a single
;; large branch can still recurse and find better-shaped parallel work deeper
;; in the tree.
(def ^:const ^long +parallel-min-branch+ 65536)

;; Secondary threshold for very small subtrees where even sequential
;; divide-and-conquer has overhead. Use direct linear merge instead.
(def ^:const ^long +sequential-cutoff+ 64)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parallel Spawn Heuristics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; The top-level threshold decides whether an operation enters the ForkJoin
;; regime at all. Once there, each recursive split still needs a local spawn
;; decision: balanced subproblems benefit from forking, but lopsided splits
;; often do better by continuing in one worker and letting the larger branch
;; find parallelism lower in the tree.

(defn- parallel-spawn-side
  [^long left-total ^long right-total]
  (let [smaller (min left-total right-total)]
    (when (>= smaller +parallel-min-branch+)
      (if (>= left-total right-total)
        :left
        :right))))

(defn- parallel-recursive?
  [^long total ^long threshold]
  (>= total threshold))

(defn- node-set-union-parallel*
  [n1 n2 ^Comparator cmp create recursive-threshold]
  (cond
    (leaf? n1) n2
    (leaf? n2) n1
    :else
    (let [total (+ (node-size n1) (node-size n2))]
      (kvlr [ak av l r] n2
        (let [[l1 _ r1] (node-split* n1 ak cmp create)
              left-total (+ (node-size l1) (node-size l))
              right-total (+ (node-size r1) (node-size r))
              spawn-side (parallel-spawn-side left-total right-total)]
          (if-not (parallel-recursive? total recursive-threshold)
            (node-concat3 ak av
                           (node-set-union* l1 l cmp create)
                           (node-set-union* r1 r cmp create)
                           cmp create)
            (case spawn-side
              :left
              (parallel/fork-join [left-result (node-set-union-parallel* l1 l cmp create recursive-threshold)
                                   right-result (node-set-union-parallel* r1 r cmp create recursive-threshold)]
                (node-concat3 ak av left-result right-result cmp create))

              :right
              (parallel/fork-join [right-result (node-set-union-parallel* r1 r cmp create recursive-threshold)
                                   left-result (node-set-union-parallel* l1 l cmp create recursive-threshold)]
                (node-concat3 ak av left-result right-result cmp create))

              (node-concat3 ak av
                             (node-set-union-parallel* l1 l cmp create recursive-threshold)
                             (node-set-union-parallel* r1 r cmp create recursive-threshold)
                             cmp create))))))))

(defn- node-set-intersection-parallel*
  [n1 n2 ^Comparator cmp create recursive-threshold]
  (cond
    (leaf? n1) (leaf)
    (leaf? n2) (leaf)
    :else
    (let [total (+ (node-size n1) (node-size n2))]
      (kvlr [ak av l r] n2
        (let [[l1 x r1] (node-split* n1 ak cmp create)
              left-total (+ (node-size l1) (node-size l))
              right-total (+ (node-size r1) (node-size r))
              spawn-side (parallel-spawn-side left-total right-total)]
          (if-not (parallel-recursive? total recursive-threshold)
            (if x
              (node-concat3 ak av
                             (node-set-intersection* l1 l cmp create)
                             (node-set-intersection* r1 r cmp create)
                             cmp create)
              (node-concat2 (node-set-intersection* l1 l cmp create)
                             (node-set-intersection* r1 r cmp create)
                             create))
            (case spawn-side
              :left
              (parallel/fork-join [left-result (node-set-intersection-parallel* l1 l cmp create recursive-threshold)
                                   right-result (node-set-intersection-parallel* r1 r cmp create recursive-threshold)]
                (if x
                  (node-concat3 ak av left-result right-result cmp create)
                  (node-concat2 left-result right-result create)))

              :right
              (parallel/fork-join [right-result (node-set-intersection-parallel* r1 r cmp create recursive-threshold)
                                   left-result (node-set-intersection-parallel* l1 l cmp create recursive-threshold)]
                (if x
                  (node-concat3 ak av left-result right-result cmp create)
                  (node-concat2 left-result right-result create)))

              (let [left-result (node-set-intersection-parallel* l1 l cmp create recursive-threshold)
                    right-result (node-set-intersection-parallel* r1 r cmp create recursive-threshold)]
                (if x
                  (node-concat3 ak av left-result right-result cmp create)
                  (node-concat2 left-result right-result create))))))))))

(defn- node-set-difference-parallel*
  [n1 n2 ^Comparator cmp create recursive-threshold]
  (cond
    (leaf? n1) (leaf)
    (leaf? n2) n1
    :else
    (let [total (+ (node-size n1) (node-size n2))]
      (kvlr [ak _ l r] n2
        (let [[l1 _ r1] (node-split* n1 ak cmp create)
              left-total (+ (node-size l1) (node-size l))
              right-total (+ (node-size r1) (node-size r))
              spawn-side (parallel-spawn-side left-total right-total)]
          (if-not (parallel-recursive? total recursive-threshold)
            (node-concat2 (node-set-difference* l1 l cmp create)
                           (node-set-difference* r1 r cmp create)
                           create)
            (case spawn-side
              :left
              (parallel/fork-join [left-result (node-set-difference-parallel* l1 l cmp create recursive-threshold)
                                   right-result (node-set-difference-parallel* r1 r cmp create recursive-threshold)]
                (node-concat2 left-result right-result create))

              :right
              (parallel/fork-join [right-result (node-set-difference-parallel* r1 r cmp create recursive-threshold)
                                   left-result (node-set-difference-parallel* l1 l cmp create recursive-threshold)]
                (node-concat2 left-result right-result create))

              (node-concat2 (node-set-difference-parallel* l1 l cmp create recursive-threshold)
                             (node-set-difference-parallel* r1 r cmp create recursive-threshold)
                             create))))))))

(defn- node-map-merge-parallel*
  [n1 n2 merge-fn ^Comparator cmp create recursive-threshold]
  (cond
    (leaf? n1) n2
    (leaf? n2) n1
    :else
    (let [total (+ (node-size n1) (node-size n2))]
      (kvlr [ak av l r] n2
        (let [[l1 x r1] (node-split* n1 ak cmp create)
              val (if x (merge-fn ak av (second x)) av)
              left-total (+ (node-size l1) (node-size l))
              right-total (+ (node-size r1) (node-size r))
              spawn-side (parallel-spawn-side left-total right-total)]
          (if-not (parallel-recursive? total recursive-threshold)
            (node-concat3 ak val
                           (node-map-merge* l1 l merge-fn cmp create)
                           (node-map-merge* r1 r merge-fn cmp create)
                           cmp create)
            (case spawn-side
              :left
              (parallel/fork-join [left-result (node-map-merge-parallel* l1 l merge-fn cmp create recursive-threshold)
                                   right-result (node-map-merge-parallel* r1 r merge-fn cmp create recursive-threshold)]
                (node-concat3 ak val left-result right-result cmp create))

              :right
              (parallel/fork-join [right-result (node-map-merge-parallel* r1 r merge-fn cmp create recursive-threshold)
                                   left-result (node-map-merge-parallel* l1 l merge-fn cmp create recursive-threshold)]
                (node-concat3 ak val left-result right-result cmp create))

              (node-concat3 ak val
                             (node-map-merge-parallel* l1 l merge-fn cmp create recursive-threshold)
                             (node-map-merge-parallel* r1 r merge-fn cmp create recursive-threshold)
                             cmp create))))))))

(defn node-set-union-parallel
  "Parallel set union using ForkJoinPool.

   Algorithm: Adams' divide-and-conquer with work-stealing parallelism.
   1. Split T1 at T2's root key
   2. Recursively union (T1.left, T2.left) and (T1.right, T2.right) in parallel
   3. Join results at T2's root

   Complexity:
     Work: O(m + n)
     Span: O(log^2 n)
     Speedup: Near-linear up to ~16 cores for large trees"
  [n1 n2]
  (let [cmp order/*compare*
        join *t-join*]
    (if (parallel/in-fork-join-pool?)
      (node-set-union-parallel* n1 n2 cmp join +parallel-union-recursive-threshold+)
      (parallel/invoke-root
        #(node-set-union-parallel* n1 n2 cmp join +parallel-union-recursive-threshold+)))))

(defn node-set-intersection-parallel
  "Parallel set intersection using ForkJoinPool.

   Algorithm: Split T1 at T2's root, recursively intersect subtrees,
   include root only if present in both trees.

   Complexity: Same as union - O(m+n) work, O(log^2 n) span."
  [n1 n2]
  (let [cmp order/*compare*
        join *t-join*]
    (if (parallel/in-fork-join-pool?)
      (node-set-intersection-parallel* n1 n2 cmp join +parallel-intersection-recursive-threshold+)
      (parallel/invoke-root
        #(node-set-intersection-parallel* n1 n2 cmp join +parallel-intersection-recursive-threshold+)))))

(defn node-set-difference-parallel
  "Parallel set difference using ForkJoinPool.

   Algorithm: Split T1 at T2's root, recursively compute difference,
   never include T2's root (since we're computing T1 - T2).

   Complexity: Same as union - O(m+n) work, O(log^2 n) span."
  [n1 n2]
  (let [cmp order/*compare*
        join *t-join*]
    (if (parallel/in-fork-join-pool?)
      (node-set-difference-parallel* n1 n2 cmp join +parallel-difference-recursive-threshold+)
      (parallel/invoke-root
        #(node-set-difference-parallel* n1 n2 cmp join +parallel-difference-recursive-threshold+)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Map Operations (Worst-Case Linear Time)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: adjust this

(defn node-map-merge
  "Merge two maps in worst case linear time."
  [n1 n2 merge-fn]
  (node-map-merge* n1 n2 merge-fn order/*compare* *t-join*))

(defn node-map-merge-parallel
  "Parallel map merge using ForkJoinPool.

   Algorithm: Split T1 at T2's root key, recursively merge subtrees,
   resolve collisions with merge-fn. Fork-join parallelism for large trees."
  [n1 n2 merge-fn]
  (let [cmp order/*compare*
        join *t-join*]
    (if (parallel/in-fork-join-pool?)
      (node-map-merge-parallel* n1 n2 merge-fn cmp join +parallel-merge-recursive-threshold+)
      (parallel/invoke-root
        #(node-map-merge-parallel* n1 n2 merge-fn cmp join +parallel-merge-recursive-threshold+)))))

(defn node-map-compare
  "Compare two map trees element-by-element. Keys are compared using
   the bound *compare* comparator; on key equality, values are compared
   using clojure.lang.Util/compare to handle arbitrary value types."
  [n1 n2]
  (let [^java.util.Comparator cmp order/*compare*]
    (loop [e1 (node-enumerator n1 nil)
           e2 (node-enumerator n2 nil)]
      (cond
        (and (nil? e1) (nil? e2))  0
        (nil? e1)                  -1
        (nil? e2)                   1
        :else
        (let [^EnumFrame ef1 e1
              ^EnumFrame ef2 e2
              x1 (.-node ef1)
              x2 (.-node ef2)
              kc (.compare cmp (-k x1) (-k x2))]
          (if-not (zero? kc)
            kc
            (let [vc (clojure.lang.Util/compare (-v x1) (-v x2))]
              (if-not (zero? vc)
                vc
                (recur (node-enumerator (.-subtree ef1) (.-next ef1))
                       (node-enumerator (.-subtree ef2) (.-next ef2)))))))))))

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
                  :else           n))))]
    (if-not (and (<= 0 index) (< index (node-size n)))
      (throw (ex-info "index out of range" {:i index :max (node-size n)}))
      (srch n (long index)))))

(defn node-rank
  "Return the rank (sequential position) of a given KEY within the
  ordered tree rooted at n. (Logarithmic Time)"
  ([n k]
   (node-rank n k order/*compare*))
  ([n k ^Comparator cmp]
   (loop [n n k k rank (long 0)]
     (if (leaf? n)
       nil
       (let [c (.compare cmp k (-k n))]
         (cond
           (zero? c) (+ rank (node-size (-l n)))
           (neg? c)  (recur (-l n) k rank)
           :else     (recur (-r n) k (+ 1 rank (node-size (-l n))))))))))

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
        fold  (if reverse? node-reduce-right node-reduce)
        nval  (cond-> accessor
                (not (fn? accessor)) node-accessor)]
    (fold #(conj! %1 (nval %2)) acc n)
    (persistent! acc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lazy Seq
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Efficient Direct Seq
;;
;; The tree already supports ordinary lazy iteration via `node-seq`,
;; `node-seq-reverse`, `seq`, `rseq`, `subseq`, and friends. These direct seq
;; types preserve normal seq behavior while avoiding the extra allocation and
;; wrapper overhead.
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seq-equiv
  "Compare two sequences for equivalence, element by element."
  [s1 o]
  (if-not (or (instance? clojure.lang.Sequential o) (instance? java.util.List o))
    false
    (loop [s1 (seq s1) s2 (seq o)]
      (cond
        (nil? s1) (nil? s2)
        (nil? s2) false
        (not (clojure.lang.Util/equiv (first s1) (first s2))) false
        :else (recur (next s1) (next s2))))))

(defmacro ^:private def-projected-seq-type [name next-fn project ctor]
  (let [enum-sym 'enum
        cnt-sym 'cnt
        meta-sym '_meta
        acc-sym 'acc
        n-sym 'n
        f-sym 'f
        init-sym 'init
        m-sym 'm]
    `(deftype ~name [~enum-sym ~cnt-sym ~meta-sym]
       clojure.lang.ISeq
       (first [_]
         (~project (node-enum-first ~enum-sym)))
       (next [_]
         (when-let [e# (~next-fn ~enum-sym)]
           (~ctor e# (when ~cnt-sym (unchecked-dec-int ~cnt-sym)) nil)))
       (more [this#]
         (or (.next this#) ()))
       (cons [this# o#]
         (clojure.lang.Cons. o# this#))

       clojure.lang.Seqable
       (seq [this#] this#)

       clojure.lang.Sequential

       java.lang.Iterable
       (iterator [this#]
         (clojure.lang.SeqIterator. this#))

       clojure.lang.Counted
       (count [_]
         (if ~cnt-sym ~cnt-sym (enum-count ~enum-sym ~next-fn)))

       clojure.lang.IReduceInit
       (reduce [_ ~f-sym ~init-sym]
         (enum-reduce-init ~enum-sym ~next-fn ~acc-sym ~init-sym ~n-sym
                           (~f-sym ~acc-sym (~project ~n-sym))))

       clojure.lang.IReduce
       (reduce [_ ~f-sym]
         (enum-reduce-first ~enum-sym ~next-fn ~acc-sym ~n-sym (~project ~n-sym)
                            (~f-sym ~acc-sym (~project ~n-sym))
                            (~f-sym)))

       clojure.lang.IHashEq
       (hasheq [this#]
         (clojure.lang.Murmur3/hashOrdered this#))

       clojure.lang.IPersistentCollection
       (empty [_] ())
       (equiv [this# o#]
         (seq-equiv this# o#))

       java.lang.Object
       (hashCode [this#]
         (clojure.lang.Util/hash this#))
       (equals [this# o#]
         (clojure.lang.Util/equals this# o#))

       clojure.lang.IMeta
       (meta [_] ~meta-sym)

       clojure.lang.IObj
       (withMeta [_ ~m-sym]
         (~ctor ~enum-sym ~cnt-sym ~m-sym)))))

(def-projected-seq-type KeySeq   node-enum-rest -k KeySeq.)
(def-projected-seq-type EntrySeq node-enum-rest -kv EntrySeq.)

(defn node-key-seq
  "Return an efficient seq of keys from tree rooted at n."
  ([n] (node-key-seq n nil))
  ([n cnt]
   (when-let [e (node-enumerator n)]
     (KeySeq. e cnt nil))))

(defn node-entry-seq
  "Return an efficient seq of map entries from tree rooted at n."
  ([n] (node-entry-seq n nil))
  ([n cnt]
   (when-let [e (node-enumerator n)]
     (EntrySeq. e cnt nil))))

(def-projected-seq-type KeySeqReverse node-enum-prior -k KeySeqReverse.)
(def-projected-seq-type EntrySeqReverse node-enum-prior -kv EntrySeqReverse.)

(defn node-key-seq-reverse
  "Return an efficient reverse seq of keys from tree rooted at n."
  ([n] (node-key-seq-reverse n nil))
  ([n cnt]
   (when-let [e (node-enumerator-reverse n)]
     (KeySeqReverse. e cnt nil))))

(defn node-entry-seq-reverse
  "Return an efficient reverse seq of map entries from tree rooted at n."
  ([n] (node-entry-seq-reverse n nil))
  ([n cnt]
   (when-let [e (node-enumerator-reverse n)]
     (EntrySeqReverse. e cnt nil))))

(defn node-subseq
  "Return a seq of nodes for the slice of the tree from position
  `from` to `to` (inclusive)."
  ([n from]
   (node-subseq n from (dec (node-size n))))
  ([n ^long from ^long to]
   (let [cnt (inc (- to from))]
     (cond
       (leaf? n)        nil
       (not (pos? cnt)) nil
       true (->> from (node-split-nth n) node-seq (take cnt))))))
