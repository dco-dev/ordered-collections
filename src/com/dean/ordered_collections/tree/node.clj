(ns com.dean.ordered-collections.tree.node
  (:import  [clojure.lang MapEntry]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Leaf Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; It can sometimes be the case that "leaf" nodes aren't a static value,
;; but computed/generated/populated in some way. so i usually make `leaf`
;; a function rather than value just as a matter of practice in order to
;; have a complete abstraction layer between node and tree layers.

(definline leaf []
  nil)

(definline leaf? [x]
  `(identical? ~x nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node Capability
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: this exists to work around spurious build warnings during clojurescript
;; build phase of enclosing project

(defmacro ^:private definterface-once [iname & args]
  (when-not (resolve iname)
    `(definterface ~iname ~@args)))

(definterface-once INode
  (k  []  "key:             any value")
  (v  []  "value:           any value")
  (l  []  "left-child:      a Node or Leaf")
  (r  []  "right-child:     a Node or Leaf")
  (kv []  "key-val:         a pair containing both key and value"))

(definterface-once IBalancedNode
  (^long x []  "balance-metric:  an integer value"))

(definterface-once IAugmentedNode
  (z  []  "auxiliary constituent(s) for extended tree algorithms"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Model
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype SimpleNode [k v l r ^long x]
  IBalancedNode
  (x  [_] x)
  INode
  (k  [_] k)
  (v  [_] v)
  (l  [_] l)
  (r  [_] r)
  (kv [_] (MapEntry. k v)))

(deftype IntervalNode [k v l r ^long x z]
  IBalancedNode
  (x  [_] x)
  IAugmentedNode
  (z  [_] z)     ;; max node child interval span
  INode
  (k  [_] k)
  (v  [_] v)
  (l  [_] l)
  (r  [_] r)
  (kv [_] (MapEntry. k v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Array-Backed Leaf Nodes (Cache-Friendly Small Collections)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; ArrayLeaf stores up to ARRAY_LEAF_MAX elements in contiguous sorted arrays.
;; This improves cache locality for small subtrees by avoiding pointer chasing.
;;
;; When an ArrayLeaf would exceed ARRAY_LEAF_MAX elements, it's converted to
;; a tree structure. When a tree node shrinks below a threshold, it can be
;; collapsed back to an ArrayLeaf.

(def ^:const ARRAY_LEAF_MAX
  "Maximum elements in an ArrayLeaf before converting to tree structure.
   8 is a good balance: fits in a cache line, binary search is fast."
  8)

(definterface-once IArrayLeaf
  (ks   [] "sorted array of keys")
  (vs   [] "parallel array of values (same indices as keys)")
  (^long size [] "number of elements (may be less than array length)"))

(deftype ArrayLeaf [ks vs ^long size]
  IBalancedNode
  (x [_] size)  ;; size doubles as balance metric
  IArrayLeaf
  (ks   [_] ks)
  (vs   [_] vs)
  (size [_] size))

(definline array-leaf? [x]
  `(instance? ArrayLeaf ~x))

(defn array-leaf-binary-search
  "Binary search for key k in ArrayLeaf. Returns index if found, or (- insertion-point 1) if not."
  ^long [^ArrayLeaf node k ^java.util.Comparator cmp]
  (let [^objects ks (.ks node)
        n  (.size node)]
    (loop [lo 0 hi (dec n)]
      (if (> lo hi)
        (- (- lo) 1)  ;; not found, return (- insertion-point 1)
        (let [mid (unchecked-add lo (bit-shift-right (unchecked-subtract hi lo) 1))
              mk  (aget ks mid)
              c   (.compare cmp k mk)]
          (cond
            (zero? c) mid
            (neg? c)  (recur lo (dec mid))
            :else     (recur (inc mid) hi)))))))

(defn array-leaf-find
  "Find value for key k in ArrayLeaf. Returns [found? value]."
  [^ArrayLeaf node k ^java.util.Comparator cmp]
  (let [idx (array-leaf-binary-search node k cmp)]
    (if (neg? idx)
      [false nil]
      [true (aget ^objects (.vs node) idx)])))

(defn array-leaf-add
  "Add k/v to ArrayLeaf. Returns new ArrayLeaf or nil if would exceed max size.
   If key exists, replaces value."
  [^ArrayLeaf node k v ^java.util.Comparator cmp]
  (let [^objects ks (.ks node)
        ^objects vs (.vs node)
        size (.size node)
        idx  (array-leaf-binary-search node k cmp)]
    (if (>= idx 0)
      ;; Key exists - replace value
      (let [new-vs (aclone vs)]
        (aset new-vs idx v)
        (ArrayLeaf. ks new-vs size))
      ;; Key doesn't exist - insert
      (let [ins (- (- idx) 1)]  ;; insertion point
        (if (>= size ARRAY_LEAF_MAX)
          nil  ;; signal caller to convert to tree
          (let [new-size (inc size)
                new-ks   (object-array new-size)
                new-vs   (object-array new-size)]
            ;; Copy elements before insertion point
            (when (pos? ins)
              (System/arraycopy ks 0 new-ks 0 ins)
              (System/arraycopy vs 0 new-vs 0 ins))
            ;; Insert new element
            (aset new-ks ins k)
            (aset new-vs ins v)
            ;; Copy elements after insertion point
            (when (< ins size)
              (System/arraycopy ks ins new-ks (inc ins) (- size ins))
              (System/arraycopy vs ins new-vs (inc ins) (- size ins)))
            (ArrayLeaf. new-ks new-vs new-size)))))))

(defn array-leaf-remove
  "Remove key k from ArrayLeaf. Returns new ArrayLeaf (possibly with size 0)."
  [^ArrayLeaf node k ^java.util.Comparator cmp]
  (let [idx (array-leaf-binary-search node k cmp)]
    (if (neg? idx)
      node  ;; key not found
      (let [^objects ks (.ks node)
            ^objects vs (.vs node)
            size     (.size node)
            new-size (dec size)]
        (if (zero? new-size)
          nil  ;; becomes empty (leaf)
          (let [new-ks (object-array new-size)
                new-vs (object-array new-size)]
            ;; Copy elements before removed index
            (when (pos? idx)
              (System/arraycopy ks 0 new-ks 0 idx)
              (System/arraycopy vs 0 new-vs 0 idx))
            ;; Copy elements after removed index
            (when (< idx new-size)
              (System/arraycopy ks (inc idx) new-ks idx (- new-size idx))
              (System/arraycopy vs (inc idx) new-vs idx (- new-size idx)))
            (ArrayLeaf. new-ks new-vs new-size)))))))

(defn array-leaf-singleton
  "Create an ArrayLeaf with a single k/v pair."
  [k v]
  (let [ks (object-array 1)
        vs (object-array 1)]
    (aset ks 0 k)
    (aset vs 0 v)
    (ArrayLeaf. ks vs 1)))

(defn array-leaf-split
  "Split a full ArrayLeaf after inserting k/v, returning [mid-k mid-v left-al right-al].
   The middle element becomes the root key of a new internal node.
   Left ArrayLeaf contains elements < mid, right contains elements > mid.
   Precondition: ArrayLeaf is at max capacity."
  [^ArrayLeaf node k v ^java.util.Comparator cmp]
  (let [^objects ks (.ks node)
        ^objects vs (.vs node)
        size     (.size node)
        ;; Create temporary arrays with the new element inserted
        new-size (inc size)
        temp-ks  (object-array new-size)
        temp-vs  (object-array new-size)
        ;; Find insertion point
        idx      (array-leaf-binary-search node k cmp)
        ins      (if (>= idx 0) idx (- (- idx) 1))]
    ;; If key already exists, just update (shouldn't happen at split, but handle it)
    (if (>= idx 0)
      ;; Key exists - return updated ArrayLeaf as left with empty right (edge case)
      (let [new-vs (aclone vs)]
        (aset new-vs idx v)
        [k v (ArrayLeaf. ks new-vs size) nil])
      ;; Normal case: insert and split
      (do
        ;; Copy elements before insertion point
        (when (pos? ins)
          (System/arraycopy ks 0 temp-ks 0 ins)
          (System/arraycopy vs 0 temp-vs 0 ins))
        ;; Insert new element
        (aset temp-ks ins k)
        (aset temp-vs ins v)
        ;; Copy elements after insertion point
        (when (< ins size)
          (System/arraycopy ks ins temp-ks (inc ins) (- size ins))
          (System/arraycopy vs ins temp-vs (inc ins) (- size ins)))
        ;; Now split: mid is at new-size/2
        (let [mid      (quot new-size 2)
              mid-k    (aget temp-ks mid)
              mid-v    (aget temp-vs mid)
              ;; Left: elements [0, mid)
              left-size mid
              left-ks   (object-array left-size)
              left-vs   (object-array left-size)
              ;; Right: elements (mid, new-size)
              right-size (- new-size mid 1)
              right-ks   (object-array right-size)
              right-vs   (object-array right-size)]
          (System/arraycopy temp-ks 0 left-ks 0 left-size)
          (System/arraycopy temp-vs 0 left-vs 0 left-size)
          (System/arraycopy temp-ks (inc mid) right-ks 0 right-size)
          (System/arraycopy temp-vs (inc mid) right-vs 0 right-size)
          [mid-k mid-v
           (ArrayLeaf. left-ks left-vs left-size)
           (ArrayLeaf. right-ks right-vs right-size)])))))

(defn array-leaf-from-sorted
  "Create an ArrayLeaf from pre-sorted arrays. Arrays are used directly (not copied)."
  [^objects ks ^objects vs ^long size]
  (ArrayLeaf. ks vs size))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constitutent Accessors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; @gunnarson style

(definline -k  [n] `(.k  ~(with-meta n {:tag 'com.dean.ordered_collections.tree.node.INode})))
(definline -v  [n] `(.v  ~(with-meta n {:tag 'com.dean.ordered_collections.tree.node.INode})))
(definline -l  [n] `(.l  ~(with-meta n {:tag 'com.dean.ordered_collections.tree.node.INode})))
(definline -r  [n] `(.r  ~(with-meta n {:tag 'com.dean.ordered_collections.tree.node.INode})))
(definline -x  [n] `(.x  ~(with-meta n {:tag 'com.dean.ordered_collections.tree.node.IBalancedNode})))
(definline -z  [n] `(.z  ~(with-meta n {:tag 'com.dean.ordered_collections.tree.node.IAugmentedNode})))
(definline -kv [n] `(.kv ~(with-meta n {:tag 'com.dean.ordered_collections.tree.node.INode})))

