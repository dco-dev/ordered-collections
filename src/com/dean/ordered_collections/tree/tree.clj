(ns com.dean.ordered-collections.tree.tree
  (:require [clojure.core.reducers       :as r]
            [com.dean.ordered-collections.tree.interval :as interval]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.node     :as node
             :refer [leaf? leaf -k -v -l -r -x -z -kv
                     array-leaf? array-leaf-singleton array-leaf-add
                     array-leaf-remove array-leaf-binary-search
                     ARRAY_LEAF_MAX]])
  (:import  [clojure.lang MapEntry]
            [java.util Comparator]
            [com.dean.ordered_collections.tree.node ArrayLeaf]))

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
  "returns the balance metric of the tree rooted at n.
   Works for both tree nodes and ArrayLeaf nodes."
  ^long [n]
  (cond
    (leaf? n)       0
    (array-leaf? n) (.size ^ArrayLeaf n)
    :else           (-x n)))

(definline node-weight
  "Returns node weight for rotation calculations using the 'revised non-variant
   algorithm' for weight balanced binary trees. Weight = size + 1.

   Works for both tree nodes and ArrayLeaf nodes via IBalancedNode interface.
   ArrayLeaf.x() returns size, SimpleNode.x() returns subtree size."
  [n]
  `(let [n# ~n]
     (unchecked-inc (if (leaf? n#) 0 (long (-x n#))))))

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
;;         │
;;         ▼
;;     ┌─────────────────────────────────────────────────────────┐
;;     │ EnumFrame                                               │
;;     │   node: 1                                               │
;;     │   subtree: nil  ─────────────────────────────────────┐  │
;;     │   next: ───┐                                         │  │
;;     └────────────│────────────────────────────────────────│──┘
;;                  ▼                                         │
;;     ┌─────────────────────────────────────────────────┐    │
;;     │ EnumFrame                                       │    │
;;     │   node: 2                                       │    │
;;     │   subtree: ─────► subtree rooted at 3           │    │
;;     │   next: ───┐                                    │    │
;;     └────────────│────────────────────────────────────┘    │
;;                  ▼                                         │
;;     ┌─────────────────────────────────────────────────┐    │
;;     │ EnumFrame                                       │    │
;;     │   node: 4                                       │    │
;;     │   subtree: ─────► subtree rooted at 6           │    │
;;     │   next: nil                                     │    │
;;     └─────────────────────────────────────────────────┘    │
;;                                                            │
;;     The leftmost node (1) is at the head ◄─────────────────┘
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
;;        → enumerates subtree 3, producing Frame(3, nil, Frame(4,...))
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

;; ArrayLeafEnumFrame for iterating through ArrayLeaf elements
(deftype ArrayLeafEnumFrame [^ArrayLeaf al ^long idx ^long direction next-frame])

(defn node-enumerator
  "Efficient mechanism to accomplish partial enumeration of
   tree-structure into a seq representation without incurring the
   overhead of operating over the entire tree. Used internally for
   implementation of higher-level collection api routines.

   Returns an EnumFrame representing the leftmost spine of the tree,
   where each frame holds (current-node, right-subtree, next-frame).
   Works with both tree nodes and ArrayLeaf nodes."
  ([n] (node-enumerator n nil))
  ([n enum]
   (cond
     (leaf? n) enum
     (array-leaf? n) (ArrayLeafEnumFrame. n 0 1 enum)  ;; forward: start at 0, step +1
     :else (recur (-l n) (EnumFrame. n (-r n) enum)))))

(defn node-enumerator-reverse
  "Reverse enumerator: builds rightmost spine where each frame holds
   (current-node, left-subtree, next-frame).
   Works with both tree nodes and ArrayLeaf nodes."
  ([n] (node-enumerator-reverse n nil))
  ([n enum]
   (cond
     (leaf? n) enum
     (array-leaf? n) (let [^ArrayLeaf al n]
                       (ArrayLeafEnumFrame. al (dec (.size al)) -1 enum))  ;; reverse: start at end, step -1
     :else (recur (-r n) (EnumFrame. n (-l n) enum)))))

(defn node-enum-first
  "Return the current node from an enumerator frame."
  [enum]
  (cond
    (instance? EnumFrame enum)
    (.-node ^EnumFrame enum)

    (instance? ArrayLeafEnumFrame enum)
    (let [^ArrayLeafEnumFrame af enum
          ^ArrayLeaf al (.-al af)
          idx (.-idx af)]
      (node/->SimpleNode (aget ^objects (.ks al) idx) (aget ^objects (.vs al) idx) nil nil 1))))

(defn node-enum-rest
  "Advance forward enumerator to the next node."
  [enum]
  (when (some? enum)
    (cond
      (instance? EnumFrame enum)
      (let [^EnumFrame ef enum
            subtree (.-subtree ef)
            next    (.-next ef)]
        (when-not (and (nil? subtree) (nil? next))
          (node-enumerator subtree next)))

      (instance? ArrayLeafEnumFrame enum)
      (let [^ArrayLeafEnumFrame af enum
            ^ArrayLeaf al (.-al af)
            next-idx (+ (.-idx af) (.-direction af))
            next-frame (.-next-frame af)]
        (if (and (>= next-idx 0) (< next-idx (.size al)))
          (ArrayLeafEnumFrame. al next-idx (.-direction af) next-frame)
          next-frame)))))

(defn node-enum-prior
  "Advance reverse enumerator to the next (prior) node."
  [enum]
  (when (some? enum)
    (cond
      (instance? EnumFrame enum)
      (let [^EnumFrame ef enum
            subtree (.-subtree ef)
            next    (.-next ef)]
        (when-not (and (nil? subtree) (nil? next))
          (node-enumerator-reverse subtree next)))

      (instance? ArrayLeafEnumFrame enum)
      (let [^ArrayLeafEnumFrame af enum
            ^ArrayLeaf al (.-al af)
            next-idx (+ (.-idx af) (.-direction af))
            next-frame (.-next-frame af)]
        (if (and (>= next-idx 0) (< next-idx (.size al)))
          (ArrayLeafEnumFrame. al next-idx (.-direction af) next-frame)
          next-frame)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree Rotations (Weight Balanced)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

  Macro for inlining in hot rotation paths."
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

  Macro for inlining in hot rotation paths."
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

  Macro for inlining in hot rotation paths."
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

  Macro for inlining in hot rotation paths."
  [create ck cv a z]
  `(let [a# ~a
         ak# (-k a#) av# (-v a#) x# (-l a#) b# (-r a#)
         bk# (-k b#) bv# (-v b#) y1# (-l b#) y2# (-r b#)]
     (~create bk# bv# (~create ak# av# x# y1#) (~create ~ck ~cv y2# ~z))))

(defn- array-leaf-to-node
  "Convert an ArrayLeaf to a single node with ArrayLeaf children.
   Splits the ArrayLeaf in half, creating a balanced structure that
   preserves ArrayLeafs at the leaves (FSet-style).

   Returns a node with:
   - Middle element as k/v
   - Left ArrayLeaf with elements < mid
   - Right ArrayLeaf with elements > mid"
  [^ArrayLeaf al create]
  (let [^objects ks (.ks al)
        ^objects vs (.vs al)
        size (.size al)
        mid  (quot size 2)
        mid-k (aget ks mid)
        mid-v (aget vs mid)
        ;; Left: elements [0, mid)
        left-size mid
        left  (if (zero? left-size)
                (leaf)
                (let [left-ks (object-array left-size)
                      left-vs (object-array left-size)]
                  (System/arraycopy ks 0 left-ks 0 left-size)
                  (System/arraycopy vs 0 left-vs 0 left-size)
                  (ArrayLeaf. left-ks left-vs left-size)))
        ;; Right: elements (mid, size)
        right-size (- size mid 1)
        right (if (zero? right-size)
                (leaf)
                (let [right-ks (object-array right-size)
                      right-vs (object-array right-size)]
                  (System/arraycopy ks (inc mid) right-ks 0 right-size)
                  (System/arraycopy vs (inc mid) right-vs 0 right-size)
                  (ArrayLeaf. right-ks right-vs right-size)))]
    (create mid-k mid-v left right)))

(defn- array-leaf-to-tree
  "Convert an ArrayLeaf to a balanced tree structure.
   For small ArrayLeafs, uses array-leaf-to-node to preserve ArrayLeaf leaves.
   For larger ones, recursively builds a tree."
  [^ArrayLeaf al create]
  (let [size (.size al)]
    (if (<= size 4)
      ;; Small: just create one node with smaller ArrayLeaf children
      (array-leaf-to-node al create)
      ;; Larger: recursively split
      (let [^objects ks (.ks al)
            ^objects vs (.vs al)]
        (letfn [(build [^long lo ^long hi]
                  (cond
                    (> lo hi) (leaf)
                    ;; Small range: create ArrayLeaf
                    (<= (- hi lo) 3)
                    (let [n (inc (- hi lo))
                          arr-ks (object-array n)
                          arr-vs (object-array n)]
                      (System/arraycopy ks lo arr-ks 0 n)
                      (System/arraycopy vs lo arr-vs 0 n)
                      (ArrayLeaf. arr-ks arr-vs n))
                    ;; Larger: split recursively
                    :else
                    (let [mid (+ lo (quot (- hi lo) 2))
                          k   (aget ks mid)
                          v   (aget vs mid)]
                      (create k v (build lo (dec mid)) (build (inc mid) hi)))))]
          (build 0 (dec size)))))))

(defn- stitch-wb-tree
  "Fast weight-balanced stitch for tree nodes only (no ArrayLeaf checks).
   Used in hot paths when ArrayLeaf is disabled."
  [create k v l r]
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
      (create k v l r))))

(defn- stitch-wb
  "Weight-balanced stitch: join left and right subtrees at root k/v, performing
  a single or double rotation to restore balance if needed. Assumes all keys in
  l < k < all keys in r, and imbalance is at most one rotation away from balanced.

  Balance criteria (Hirai-Yamamoto):
    - Rotate left  when: weight(r) > δ × weight(l)
    - Rotate right when: weight(l) > δ × weight(r)
    - Single vs double determined by γ threshold on inner subtree weights.

  This version handles ArrayLeaf nodes for when *use-array-leaf* is true."
  [create k v l r]
  ;; Check weights first - node-weight handles ArrayLeaf
  (let [lw (node-weight l)
        rw (node-weight r)]
    (cond
      ;; Right-heavy: need to rotate left - convert r if ArrayLeaf (need to access its children)
      (> rw (* +delta+ lw))
      (let [r  (if (array-leaf? r) (array-leaf-to-tree r create) r)
            rl (-l r)
            rlw (node-weight rl)
            rrw (node-weight (-r r))]
        (if (< rlw (* +gamma+ rrw))
          (rotate-single-left create k v l r)
          ;; Double rotation accesses children of rl - convert if ArrayLeaf
          (let [r (if (array-leaf? rl)
                    (create (-k r) (-v r) (array-leaf-to-tree rl create) (-r r))
                    r)]
            (rotate-double-left create k v l r))))

      ;; Left-heavy: need to rotate right - convert l if ArrayLeaf (need to access its children)
      (> lw (* +delta+ rw))
      (let [l  (if (array-leaf? l) (array-leaf-to-tree l create) l)
            lr (-r l)
            llw (node-weight (-l l))
            lrw (node-weight lr)]
        (if (< lrw (* +gamma+ llw))
          (rotate-single-right create k v l r)
          ;; Double rotation accesses children of lr - convert if ArrayLeaf
          (let [l (if (array-leaf? lr)
                    (create (-k l) (-v l) (-l l) (array-leaf-to-tree lr create))
                    l)]
            (rotate-double-right create k v l r))))

      ;; Balanced: no rotation needed - ArrayLeaf children are fine as-is
      :else
      (create k v l r))))

(defn node-stitch-weight-balanced
  "Weight-Balancing Algorithm:

  Join left and right subtrees at root k/v, performing a single or
  double rotation to balance the resulting tree, if needed.  Assumes
  all keys in l < k < all keys in r, and the relative weight balance
  of the left and right subtrees is such that no more than one
  single/double rotation will result in each subtree being less than
  +delta+ times the weight of the other."
  [k v l r]
  (stitch-wb *t-join* k v l r))

(def ^:dynamic *n-join* node-stitch-weight-balanced)

(defn node-stitch
  "The `stitch` operation is the sole balancing constructor and
  interface to the specific balancing rotation algorithm of the tree.
  Sometimes referred to as `n-join` operation"
  [k v l r]
  (*n-join* k v l r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ArrayLeaf Control
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *use-array-leaf*
  "When true, use ArrayLeaf for collections of any size.

   ArrayLeaf (inspired by FSet's 'leaf vectors') stores up to 8 elements in
   contiguous sorted arrays at the tree leaves. When an ArrayLeaf overflows,
   it splits into two ArrayLeafs with a new internal node above them, keeping
   the array-based leaves throughout the tree's lifetime.

   Benefits:
   - Improved cache locality for iteration (sequential array access)
   - Faster lookups (binary search in final array vs more tree traversal)
   - Reduced memory overhead (fewer node allocations)

   Trade-offs:
   - Slightly more complex hot paths due to type checks
   - Specialized tree types (segment-tree, interval-map) that use custom nodes
     must bind this to false.

   Currently disabled by default for stability. Enable experimentally with:
   (binding [tree/*use-array-leaf* true] ...)"
  false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Tree Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-add
  "Insert a new key/value into the tree rooted at n.
   Uses ArrayLeaf for small collections when *use-array-leaf* is true,
   converts to tree when threshold exceeded."
  ([n k]
   (node-add n k k))
  ([n k v]
   (node-add n k v order/*compare* *t-join*))
  ([n k v ^Comparator cmp create]
   (if *use-array-leaf*
     ;; ArrayLeaf-enabled path (FSet-style: ArrayLeafs persist at leaves)
     (letfn [(add [n]
               (cond
                 ;; Empty: create singleton ArrayLeaf
                 (leaf? n)
                 (array-leaf-singleton k v)

                 ;; ArrayLeaf: try to add, split if overflow
                 (array-leaf? n)
                 (if-let [result (array-leaf-add n k v cmp)]
                   result
                   ;; Overflow: split into two ArrayLeafs with internal node
                   (let [[mid-k mid-v left-al right-al] (node/array-leaf-split n k v cmp)]
                     (create mid-k mid-v left-al right-al)))

                 ;; Tree node: standard tree insertion, stitch handles ArrayLeaf children
                 :else
                 (kvlr [key val l r] n
                   (let [c (.compare cmp k key)]
                     (if (zero? c)
                       (create key v l r)
                       (if (neg? c)
                         (stitch-wb create key val (add l) r)
                         (stitch-wb create key val l (add r))))))))]
       (add n))
     ;; Standard tree path (no ArrayLeaf) - use fast stitch-wb-tree
     (letfn [(add [n]
               (if (leaf? n)
                 (create k v (leaf) (leaf))
                 (kvlr [key val l r] n
                   (let [c (.compare cmp k key)]
                     (if (zero? c)
                       (create key v l r)
                       (if (neg? c)
                         (stitch-wb-tree create key val (add l) r)
                         (stitch-wb-tree create key val l (add r))))))))]
       (add n)))))

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
  "Return the node containing the minimum key of the tree rooted at n.
   Works with both tree nodes and ArrayLeaf nodes."
  [n]
  (cond
    (leaf? n)       (throw (ex-info "least: empty tree" {:node n}))
    (array-leaf? n) (let [^ArrayLeaf al n]
                      (node/->SimpleNode (aget ^objects (.ks al) 0)
                                         (aget ^objects (.vs al) 0)
                                         nil nil 1))
    (leaf? (-l n))  n
    true            (recur (-l n))))

(defn node-greatest
  "Return the node containing the maximum key of the tree rooted at n.
   Works with both tree nodes and ArrayLeaf nodes."
  [n]
  (cond
    (leaf? n)       (throw (ex-info "greatest: empty tree" {:node n}))
    (array-leaf? n) (let [^ArrayLeaf al n
                          idx (dec (.size al))]
                      (node/->SimpleNode (aget ^objects (.ks al) idx)
                                         (aget ^objects (.vs al) idx)
                                         nil nil 1))
    (leaf? (-r n))  n
    true            (recur (-r n))))

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
  "remove the node whose key is equal to k, if present.
   Works with both tree nodes and ArrayLeaf nodes."
  ([n k]
   (node-remove n k order/*compare* *t-join*))
  ([n k ^Comparator cmp create]
   (if *use-array-leaf*
     ;; ArrayLeaf-enabled path
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
               (cond
                 ;; Empty tree
                 (leaf? n)
                 (leaf)

                 ;; ArrayLeaf: use array-leaf-remove
                 (array-leaf? n)
                 (or (array-leaf-remove n k cmp) (leaf))

                 ;; Tree node: standard removal
                 :else
                 (kvlr [key val l r] n
                   (let [c (.compare cmp k key)]
                     (if (zero? c)
                       (concat2 l r)
                       (if (neg? c)
                         (stitch-wb create key val (rm l) r)
                         (stitch-wb create key val l (rm r))))))))]
       (rm n))
     ;; Fast path - no ArrayLeaf checks
     (letfn [(concat2 [l r]
               (cond
                 (leaf? l) r
                 (leaf? r) l
                 :else (kvlr [k v _ _] (node-least r)
                         (stitch-wb-tree create k v l (rm-least r)))))
             (rm-least [n]
               (cond
                 (leaf? n)      (throw (ex-info "rm-least: empty" {}))
                 (leaf? (-l n)) (-r n)
                 :else          (stitch-wb-tree create (-k n) (-v n)
                                  (rm-least (-l n)) (-r n))))
             (rm [n]
               (if (leaf? n)
                 (leaf)
                 (kvlr [key val l r] n
                   (let [c (.compare cmp k key)]
                     (if (zero? c)
                       (concat2 l r)
                       (if (neg? c)
                         (stitch-wb-tree create key val (rm l) r)
                         (stitch-wb-tree create key val l (rm r))))))))]
       (rm n)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree Search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-find
  "find a node in n whose key = k.
   Returns a node implementing INode, or nil if not found.
   Works with both tree nodes and ArrayLeaf nodes."
  ([n k]
   (node-find n k order/*compare*))
  ([n k ^Comparator cmp]
   (loop [n n]
     (cond
       (leaf? n) nil

       (array-leaf? n)
       (let [^ArrayLeaf al n
             idx (array-leaf-binary-search al k cmp)]
         (when-not (neg? idx)
           ;; Return a synthetic node for API compatibility
           (node/->SimpleNode (aget ^objects (.ks al) idx) (aget ^objects (.vs al) idx) nil nil 1)))

       :else
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

(defn node-find-best-interval [n i pred]
  ((node-find-interval-fn i pred) n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Iteration and Accumulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; MAYBE: replace/refactor as `node-traverse`
;;       options: forward/reverse, in-order/post-order/pre-order

(defn node-iter
  "For the side-effect, apply f to each node of the tree rooted at n.
   Works with both tree nodes and ArrayLeaf nodes."
  [n f]
  (cond
    (leaf? n) nil
    (array-leaf? n)
    (let [^ArrayLeaf al n
          ^objects ks (.ks al)
          ^objects vs (.vs al)
          size (.size al)]
      (dotimes [i size]
        (f (node/->SimpleNode (aget ks i) (aget vs i) nil nil 1))))
    :else
    (lr [l r] n
      (node-iter l f)
      (f n)
      (node-iter r f))))

(defn node-iter-reverse
  "For the side-effect, apply f to each node of the tree rooted at n.
   Works with both tree nodes and ArrayLeaf nodes."
  [n f]
  (cond
    (leaf? n) nil
    (array-leaf? n)
    (let [^ArrayLeaf al n
          ^objects ks (.ks al)
          ^objects vs (.vs al)
          size (.size al)]
      (loop [i (dec size)]
        (when (>= i 0)
          (f (node/->SimpleNode (aget ks i) (aget vs i) nil nil 1))
          (recur (dec i)))))
    :else
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
  "Reduction over nodes. Delegates to node-fold-left which handles
   both tree nodes and ArrayLeaf nodes via the enumerator.
   Supports early termination via clojure.core/reduced."
  ([f init root]
   (node-fold-left f init root))
  ([f root]
   (if (leaf? root)
     (f)
     (let [e (node-enumerator root)]
       (if (nil? e)
         (f)
         (loop [e (node-enum-rest e)
                acc (node-enum-first (node-enumerator root))]
           (if (nil? e)
             acc
             (let [res (f acc (node-enum-first e))]
               (if (reduced? res)
                 @res
                 (recur (node-enum-rest e) res))))))))))

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
  (cond
    (leaf? n) true
    ;; ArrayLeaf is always healthy (it's a flat sorted array)
    (array-leaf? n) true
    ;; Tree node: check balance invariants
    :else
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
;; EXAMPLE: split tree at key 5
;;
;;            ,---,
;;            | 4 |                          L (keys < 5)      R (keys > 5)
;;            :---:
;;           :     :                              ,---,           ,---,
;;      ,---:       :---,                         | 4 |           | 7 |
;;      | 2 |       | 7 |        split(5)         :---:           :---:
;;      :---:       :---:       ─────────►       :     :         :     :
;;     :     :     :     :                   ,--:   :--,     ,--:       :--,
;;  ,-:   :-,  ,-:   :-,                     |2 |   |3 |     |6 |       |8 |
;;  |1 |  |3 | |6 |  |8 |                    '--'   '--'     '--'       '--'
;;  '--'  '--' '--'  '--'
;;     └────┬───┘                            plus: present = nil (5 not in tree)
;;       ,-:   :-,
;;       |5:val|                             If 5 were present, present = (5, val)
;;       '-----'
;;
;; ALGORITHM (recursive):
;;
;;   split(node, k):
;;     if node is leaf: return (nil, nil, nil)
;;
;;     compare k with node.key:
;;       k = node.key  →  (node.left, (k,v), node.right)
;;       k < node.key  →  (ll, p, lr) = split(node.left, k)
;;                        return (ll, p, concat3(node.key, node.val, lr, node.right))
;;       k > node.key  →  (rl, p, rr) = split(node.right, k)
;;                        return (concat3(node.key, node.val, node.left, rl), p, rr)
;;
;; VISUAL: split(tree, 3) where 3 < 4
;;
;;            ,---,
;;            | 4 |
;;            :---:                         split left subtree at 3:
;;           :     :                           (L', present, R') = split([2], 3)
;;      ,---:       :---,
;;      | 2 |       | 7 |                   Then rebuild:
;;      '---'       '---'                     L = L'
;;                                            R = concat3(4, v, R', [7])
;;
;; COMPLEXITY: O(log n) — each recursive call descends one level
;;
;; WHY IT MATTERS: Split is the foundation for efficient set operations.
;; Instead of element-by-element insertion (O(n log n)), we can implement
;; union, intersection, and difference in O(n) time using divide-and-conquer.

(defn- ensure-tree-node
  "Convert ArrayLeaf to tree structure if needed. Returns the node unchanged
   if it's already a tree node or leaf."
  [n]
  (if (array-leaf? n)
    (array-leaf-to-tree n *t-join*)
    n))

(defn node-split-lesser
  "return a tree of all nodes whose key is less than k (Logarithmic time)."
  [n k]
  (let [n (ensure-tree-node n)
        ^Comparator cmp order/*compare*]
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
  (let [n (ensure-tree-node n)
        ^Comparator cmp order/*compare*]
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
  (let [n (ensure-tree-node n)
        ^Comparator cmp order/*compare*]
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

(defn- enum-frame-extract
  "Extract current element info from an enum frame (EnumFrame or ArrayLeafEnumFrame).
   Returns [current-k current-v next-subtree next-frame] or nil if at end."
  [frame]
  (cond
    (nil? frame) nil

    (instance? ArrayLeafEnumFrame frame)
    (let [^ArrayLeafEnumFrame af frame
          ^ArrayLeaf al (.-al af)
          idx (.-idx af)
          size (.size al)]
      (if (or (neg? idx) (>= idx size))
        nil  ;; exhausted
        [(aget ^objects (.ks al) idx)
         (aget ^objects (.vs al) idx)
         (leaf)  ;; no subtree - ArrayLeaf is flat
         (let [next-idx (+ idx (.-direction af))]
           (if (or (neg? next-idx) (>= next-idx size))
             (.-next-frame af)
             (ArrayLeafEnumFrame. al next-idx (.-direction af) (.-next-frame af))))]))

    :else  ;; EnumFrame
    (let [^EnumFrame ef frame]
      [(.-node ef)
       nil  ;; caller uses accessor
       (.-subtree ef)
       (.-next ef)])))

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
      (let [info1 (enum-frame-extract e1)
            info2 (enum-frame-extract e2)]
        (cond
          (and (nil? info1) (nil? info2))  0
          (nil? info1)                     -1
          (nil? info2)                      1
          :else
          (let [[x1-or-k v1 r1 ee1] info1
                [x2-or-k v2 r2 ee2] info2
                ;; For EnumFrame, x is the node; for ArrayLeafEnumFrame, x is the key
                val1 (if (instance? ArrayLeafEnumFrame e1)
                       (case accessor
                         :k  x1-or-k
                         :v  v1
                         :kv (clojure.lang.MapEntry. x1-or-k v1)
                         (clojure.lang.MapEntry. x1-or-k v1))
                       (acc-fn x1-or-k))
                val2 (if (instance? ArrayLeafEnumFrame e2)
                       (case accessor
                         :k  x2-or-k
                         :v  v2
                         :kv (clojure.lang.MapEntry. x2-or-k v2)
                         (clojure.lang.MapEntry. x2-or-k v2))
                       (acc-fn x2-or-k))
                c    (.compare cmp val1 val2)]
            (if-not (zero? c)
              c
              (recur (node-enumerator r1 ee1)
                     (node-enumerator r2 ee2)))))))))

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

(defn node-set-union
  "set union"
  [n1 n2]
  ;; Convert ArrayLeaf to tree for set operations (they need tree structure)
  (let [n1 (if (array-leaf? n1) (array-leaf-to-tree n1 *t-join*) n1)
        n2 (if (array-leaf? n2) (array-leaf-to-tree n2 *t-join*) n2)]
    (cond
      (leaf? n1) n2
      (leaf? n2) n1
      true       (kvlr [ak av l r] n2
                   (let [[l1 _ r1] (node-split n1 ak)]
                     (node-concat3 ak av
                       (node-set-union l1 l)
                       (node-set-union r1 r)))))))

(defn node-set-intersection
  "set intersection"
  [n1 n2]
  ;; Convert ArrayLeaf to tree for set operations
  (let [n1 (if (array-leaf? n1) (array-leaf-to-tree n1 *t-join*) n1)
        n2 (if (array-leaf? n2) (array-leaf-to-tree n2 *t-join*) n2)]
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
                         (node-set-intersection r1 r))))))))

(defn node-set-difference [n1 n2]
  "set difference"
  ;; Convert ArrayLeaf to tree for set operations
  (let [n1 (if (array-leaf? n1) (array-leaf-to-tree n1 *t-join*) n1)
        n2 (if (array-leaf? n2) (array-leaf-to-tree n2 *t-join*) n2)]
    (cond
      (leaf? n1) (leaf)
      (leaf? n2) n1
      true       (kvlr [ak _ l r] n2
                   (let  [[l1 _ r1] (node-split n1 ak)]
                     (node-concat2
                       (node-set-difference l1 l)
                       (node-set-difference r1 r)))))))

(defn node-subset?
  "return true if `sub` is a subset of `super`"
  [super sub]
  ;; Convert ArrayLeaf to tree for set operations
  (let [super (if (array-leaf? super) (array-leaf-to-tree super *t-join*) super)
        sub   (if (array-leaf? sub) (array-leaf-to-tree sub *t-join*) sub)
        ^Comparator cmp order/*compare*]
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
  ;; Convert ArrayLeaf to tree for merge operations
  (let [n1 (if (array-leaf? n1) (array-leaf-to-tree n1 *t-join*) n1)
        n2 (if (array-leaf? n2) (array-leaf-to-tree n2 *t-join*) n2)]
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
                       (node-map-merge r1 r)))))))

(def node-map-compare (partial node-compare :kv))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Vector Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-nth
  "Return nth node from the beginning of the ordered tree rooted at n.
   (Logarithmic Time)"
  [n ^long index]
  (letfn [(srch [n ^long index]
            (cond
              ;; ArrayLeaf: direct array access
              (array-leaf? n)
              (let [^ArrayLeaf al n]
                (node/->SimpleNode (aget ^objects (.ks al) index)
                                   (aget ^objects (.vs al) index)
                                   nil nil 1))
              ;; Tree node: binary search by size
              :else
              (lr [l r] n
                (let [lsize (node-size l)]
                  (cond
                    (< index lsize) (recur l index)
                    (> index lsize) (recur r (- index (inc lsize)))
                    true            n)))))]
    (if-not (and (<= 0 index) (< index (node-size n)))
      (throw (ex-info "index out of range" {:i index :max (node-size n)}))
      (srch n (long index)))))

(defn node-rank
  "Return the rank (sequential position) of a given KEY within the
  ordered tree rooted at n. (Logarithmic Time)"
  [n k]
  (let [^Comparator cmp order/*compare*]
    (loop [n n k k rank (long 0)]
      (cond
        (leaf? n) nil
        ;; ArrayLeaf: binary search
        (array-leaf? n)
        (let [idx (array-leaf-binary-search n k cmp)]
          (when-not (neg? idx)
            (+ rank idx)))
        ;; Tree node: standard search
        :else
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
