(ns ordered-collections.tree.rope
  "Chunked implicit-index rope helpers.

  Rope trees are ordered by position, not by comparator. Each node stores a
  chunk vector in the key slot and the total element count of the subtree in
  the value slot. Weight balancing is still performed by node count, while the
  subtree element counts support indexed operations."
  (:require [ordered-collections.tree.node :as node
             :refer [leaf leaf? -k -v -l -r]]
            [ordered-collections.tree.tree :as tree]))


 (def ^:const +target-chunk-size+ 64)
 (def ^:const +min-chunk-size+    32)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope Node Basics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope-size
  ^long [root]
  (if (leaf? root) 0 (long (-v root))))

(defn- rope-node-create
  "Create a rope node rooted at chunk. The node value stores subtree element
  count; the balance metric remains ordinary node count."
  [chunk _ l r]
  (node/->SimpleNode chunk
    (+ (count chunk) (rope-size l) (rope-size r))
    l r
    (+ 1 (tree/node-size l) (tree/node-size r))))

(defn- chunk-node
  [chunk]
  (when (seq chunk)
    (rope-node-create (vec chunk) nil (leaf) (leaf))))

(defn- node-chunk
  [n]
  (-k n))

(defn- node-chunk-count
  ^long [n]
  (count (node-chunk n)))

(defn- build-root
  [chunks]
  (let [n (count chunks)]
    (when (pos? n)
      (let [mid   (quot n 2)
            chunk (nth chunks mid)]
        (rope-node-create chunk nil
          (build-root (subvec chunks 0 mid))
          (build-root (subvec chunks (inc mid) n)))))))

(defn chunks->root
  [chunks]
  (build-root (vec (remove empty? chunks))))

(defn root->chunks
  [root]
  (if (leaf? root)
    []
    (tree/node-reduce-keys conj [] root)))

(defn coll->root
  [coll]
  (chunks->root (mapv vec (partition-all +target-chunk-size+ coll))))

(defn normalize-chunks
  "Rechunk a chunk sequence into the rope target chunk size."
  [chunks]
  (mapv vec (partition-all +target-chunk-size+ (mapcat seq chunks))))

(defn normalize-root
  "Normalize a rope tree so chunk sizes are repacked toward the target size."
  [root]
  (if (leaf? root)
    nil
    (chunks->root (normalize-chunks (root->chunks root)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural Concatenation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope-concat
  "Concatenate two rope trees, preserving left-before-right order."
  [l r]
  (let [create rope-node-create]
    (letfn [(cat [l r]
              (cond
                (leaf? l) r
                (leaf? r) l
                :else
                (let [lw (tree/node-weight l)
                      rw (tree/node-weight r)]
                  (cond
                    (< (* tree/+delta+ lw) rw)
                    (let [rk  (node-chunk r)
                          rl  (-l r)
                          rr  (-r r)]
                      (tree/node-stitch rk nil (cat l rl) rr create))

                    (< (* tree/+delta+ rw) lw)
                    (let [lk  (node-chunk l)
                          ll  (-l l)
                          lr  (-r l)]
                      (tree/node-stitch lk nil ll (cat lr r) create))

                    :else
                    (let [[chunk _] (tree/node-least-kv r)]
                      (tree/node-stitch chunk nil l
                        (tree/node-remove-least r create)
                        create))))))]
      (cat l r))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Indexed Access and Update
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope-nth
  [root ^long i]
  (letfn [(nth* [n ^long i]
            (let [l  (-l n)
                  ls (rope-size l)
                  chunk (node-chunk n)
                  cs (count chunk)]
              (cond
                (< i ls)
                (nth* l i)

                (< i (+ ls cs))
                (nth chunk (- i ls))

                :else
                (nth* (-r n) (- i ls cs)))))]
    (nth* root i)))

(defn rope-assoc
  [root ^long i x]
  (let [create rope-node-create]
    (letfn [(assoc* [n ^long i]
              (let [chunk (node-chunk n)
                    l     (-l n)
                    r     (-r n)
                    ls    (rope-size l)
                    cs    (count chunk)]
                (cond
                  (< i ls)
                  (tree/node-stitch chunk nil (assoc* l i) r create)

                  (< i (+ ls cs))
                  (create (assoc chunk (- i ls) x) nil l r)

                  :else
                  (tree/node-stitch chunk nil l
                    (assoc* r (- i ls cs))
                    create))))]
      (assoc* root i))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural Split, Slice, and Stack Ops
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope-split-at
  "Split rope tree at element index i, returning [left right]."
  [root ^long i]
  (let [n (rope-size root)]
    (cond
      (<= i 0) [nil root]
      (>= i n) [root nil]
      :else
      (letfn [(split* [n ^long i]
                (let [chunk (node-chunk n)
                      l     (-l n)
                      r     (-r n)
                      ls    (rope-size l)
                      cs    (count chunk)
                      rs    (+ ls cs)]
                  (cond
                    (< i ls)
                    (let [[ll lr] (split* l i)]
                      [ll (rope-concat lr (tree/node-stitch chunk nil (leaf) r rope-node-create))])

                    (= i ls)
                    [l (rope-concat (chunk-node chunk) r)]

                    (< i rs)
                    (let [offset (- i ls)
                          lc     (subvec chunk 0 offset)
                          rc     (subvec chunk offset cs)]
                      [(rope-concat l (chunk-node lc))
                       (rope-concat (chunk-node rc) r)])

                    (= i rs)
                    [(rope-concat l (chunk-node chunk)) r]

                    :else
                    (let [[rl rr] (split* r (- i rs))]
                      [(rope-concat (tree/node-stitch chunk nil l (leaf) rope-node-create) rl)
                       rr]))))]
        (split* root i)))))

(defn rope-subvec-root
  [root ^long start ^long end]
  (let [[_ right]  (rope-split-at root start)
        [mid _]    (rope-split-at right (- end start))]
    mid))

(defn rope-peek-right
  [root]
  (when-not (leaf? root)
    (let [chunk (node-chunk (tree/node-greatest root))]
      (peek chunk))))

(defn rope-pop-right
  [root]
  (cond
    (leaf? root)
    (throw (IllegalStateException. "Can't pop empty vector"))

    :else
    (let [create rope-node-create]
      (letfn [(pop* [n]
                (let [chunk (node-chunk n)
                      l     (-l n)
                      r     (-r n)]
                  (if (leaf? r)
                    (cond
                      (> (count chunk) 1)
                      (create (pop chunk) nil l r)

                      :else
                      l)

                    (tree/node-stitch chunk nil l (pop* r) create))))]
        (pop* root)))))

(defn rope-conj-right
  [root x]
  (if (leaf? root)
    (chunk-node [x])
    (let [create rope-node-create]
      (letfn [(conj* [n]
                (let [chunk (node-chunk n)
                      l     (-l n)
                      r     (-r n)]
                  (if (leaf? r)
                    (if (< (count chunk) +target-chunk-size+)
                      (create (conj chunk x) nil l r)
                      (tree/node-stitch chunk nil l (chunk-node [x]) create))
                    (tree/node-stitch chunk nil l (conj* r) create))))]
        (conj* root)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Traversal
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope-chunks-seq
  [root]
  (when-not (leaf? root)
    (tree/node-key-seq root (tree/node-size root))))

(defn rope-chunks-rseq
  [root]
  (when-not (leaf? root)
    (tree/node-key-seq-reverse root (tree/node-size root))))

(defn rope-seq
  [root]
  (when-not (leaf? root)
    (mapcat seq (rope-chunks-seq root))))

(defn rope-rseq
  [root]
  (when-not (leaf? root)
    (mapcat rseq (rope-chunks-rseq root))))

(defn rope-chunks-reduce
  ([f init root]
   (if (leaf? root)
     init
     (tree/node-reduce-keys f init root)))
  ([f root]
   (if (leaf? root)
     (f)
     (tree/node-reduce-keys f root))))

(defn rope-reduce
  ([f init root]
   (if (leaf? root)
     init
     (tree/node-reduce-keys
       (fn [acc chunk]
         (reduce f acc chunk))
       init
       root)))
  ([f root]
   (if (leaf? root)
     (f)
     (let [chunks (tree/node-key-seq root (tree/node-size root))
           chunk0 (first chunks)
           s0     (seq chunk0)
           init   (first s0)
           acc0   (reduce f init (next s0))]
       (reduce
         (fn [acc chunk]
           (reduce f acc chunk))
         acc0
         (next chunks))))))
