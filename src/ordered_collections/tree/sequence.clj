(ns ordered-collections.tree.sequence
  "Implicit-index sequence tree helpers for vector-like collections.

  These trees are ordered by position, not by comparator. Subtree sizes define
  element indices. Values are stored in the node key slot; the value slot is
  unused and remains nil."
  (:require [ordered-collections.tree.node :as node
             :refer [leaf leaf? -k -l -r]]
            [ordered-collections.tree.tree :as tree]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sequence Tree Basics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn seq-size
  ^long [root]
  (tree/node-size root))

(defn seq-singleton
  [x]
  (tree/node-create-weight-balanced x nil (leaf) (leaf)))

(defn seq-concat
  "Concatenate two sequence trees, preserving left-before-right order."
  [l r]
  (let [create tree/node-create-weight-balanced]
    (letfn [(cat [l r]
              (cond
                (leaf? l) r
                (leaf? r) l
                :else
                (let [lw (tree/node-weight l)
                      rw (tree/node-weight r)]
                  (cond
                    (< (* tree/+delta+ lw) rw)
                    (let [rk (-k r) rl (-l r) rr (-r r)]
                      (tree/node-stitch rk nil (cat l rl) rr create))

                    (< (* tree/+delta+ rw) lw)
                    (let [lk (-k l) ll (-l l) lr (-r l)]
                      (tree/node-stitch lk nil ll (cat lr r) create))

                    :else
                    (let [[k _] (tree/node-least-kv r)]
                      (tree/node-stitch k nil l (tree/node-remove-least r create) create))))))]
      (cat l r))))

(defn seq-conj-left
  [root x]
  (seq-concat (seq-singleton x) root))

(defn seq-conj-right
  [root x]
  (seq-concat root (seq-singleton x)))

(defn seq-peek-right
  [root]
  (when-not (leaf? root)
    (first (tree/node-greatest-kv root))))

(defn seq-pop-right
  [root]
  (cond
    (leaf? root)
    (throw (IllegalStateException. "Can't pop empty vector"))

    :else
    (binding [tree/*t-join* tree/node-create-weight-balanced]
      (tree/node-remove-greatest root))))

(defn seq-nth
  [root i]
  (-k (tree/node-nth root i)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Positional Update
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn seq-assoc-nth
  [root ^long i x]
  (letfn [(assoc-nth [n ^long i]
            (if (leaf? n)
              (throw (IndexOutOfBoundsException.))
              (let [l  (-l n)
                    r  (-r n)
                    ls (long (tree/node-size l))]
                (cond
                  (< i ls)
                  (tree/node-stitch (-k n) nil (assoc-nth l i) r tree/node-create-weight-balanced)

                  (= i ls)
                  (tree/node-create-weight-balanced x nil l r)

                  :else
                  (tree/node-stitch (-k n) nil l
                    (assoc-nth r (unchecked-dec (- i ls)))
                    tree/node-create-weight-balanced)))))]
    (assoc-nth root i)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural Split and Slice
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn seq-split-at
  "Split sequence tree at index i, returning [left right]."
  [root ^long i]
  (let [n (seq-size root)]
    (cond
      (<= i 0) [nil root]
      (>= i n) [root nil]
      :else
      (letfn [(split-at* [n ^long i]
                (if (leaf? n)
                  [nil nil]
                  (let [x  (-k n)
                        l  (-l n)
                        r  (-r n)
                        ls (long (seq-size l))]
                    (cond
                      (< i ls)
                      (let [[ll lr] (split-at* l i)]
                        [ll (seq-concat lr (seq-concat (seq-singleton x) r))])

                      (= i ls)
                      [l (seq-concat (seq-singleton x) r)]

                      (= i (unchecked-inc ls))
                      [(seq-concat l (seq-singleton x)) r]

                      :else
                      (let [[rl rr] (split-at* r (unchecked-dec (- i ls)))]
                        [(seq-concat (seq-concat l (seq-singleton x)) rl) rr])))))]
        (split-at* root i)))))

(defn seq-subvec-root
  [root ^long start ^long end]
  (let [[_ right]   (seq-split-at root start)
        [middle _]  (seq-split-at right (- end start))]
    middle))
