(ns ordered-collections.tree.rope
  "Chunked implicit-index rope helpers.

  Rope trees are ordered by position, not by comparator. Each node stores a
  chunk vector in the key slot and the total element count of the subtree in
  the value slot. Weight balancing is still performed by node count, while the
  subtree element counts support indexed operations.

  Chunk Size Invariant (CSI)
  ─────────────────────────
  Every chunk has size in [min, target] except:
    - If the rope has 0 chunks: the rope is empty (root is nil).
    - If the rope has 1 chunk: the chunk may be any size in [1, target].
    - If the rope has >= 2 chunks: only the rightmost chunk may be smaller
      than min (the 'runt'). All other chunks must be in [min, target].

  No chunk may exceed target.

  This is analogous to a B-tree minimum fill factor. The rightmost exception
  exists because conj (the most frequent growth operation) appends to the
  rightmost chunk, so a short rightmost chunk is naturally filled by
  subsequent appends.

  CSI is maintained by:
    - rope-concat:      position-aware boundary check + merge-boundary
    - rope-split-at:    ensure-split-parts (fringe repair on both halves)
    - rope-subvec-root: ensure-left-fringe + ensure-right-fringe
    - rope-conj-right:  fills rightmost chunk, overflows to new node
    - rope-pop-right:   shrinks rightmost chunk, removes if empty
    - coll->root:       partition-all target produces valid chunks"
  (:require [ordered-collections.tree.node :as node
             :refer [leaf leaf? -k -v -l -r]]
            [ordered-collections.tree.tree :as tree]))


(def ^:const +target-chunk-size+ 512)
(def ^:const +min-chunk-size+    256)

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

(defn chunks->root-csi
  "Build a rope tree from a sequence of chunks, ensuring CSI.
  Scans left to right, accumulating a current chunk. When the current
  chunk reaches [min, target] it is emitted; when it would exceed target
  it is split evenly. The last chunk is emitted at any size (the runt)."
  [chunks]
  (let [chunks (vec (remove empty? chunks))
        n      (count chunks)]
    (if (<= n 1)
      (build-root chunks)
      (let [fixed (loop [i (int 0), cur nil, acc (transient [])]
                    (if (>= i n)
                      (persistent! (if cur (conj! acc cur) acc))
                      (let [chunk (nth chunks i)
                            merged (if cur (into cur chunk) chunk)
                            mn     (count merged)]
                        (cond
                          ;; Merged chunk fits in target — keep accumulating
                          (<= mn +target-chunk-size+)
                          (if (and (>= mn +min-chunk-size+)
                                   (< (unchecked-inc-int i) n))
                            ;; Large enough and not last — emit and reset
                            (recur (unchecked-inc-int i) nil (conj! acc merged))
                            ;; Still small or last — carry forward
                            (recur (unchecked-inc-int i) merged acc))

                          ;; Exceeds target — split evenly, emit first half
                          :else
                          (let [half (quot mn 2)]
                            (recur (unchecked-inc-int i)
                              (subvec merged half)
                              (conj! acc (subvec merged 0 half))))))))]
        (build-root fixed)))))

(defn- rechunk-balanced
  "Partition a flat vector of elements into chunks satisfying CSI:
  every chunk in [min, target] except possibly the last which may be
  smaller. When the last full-sized chunk would leave a remainder
  below min, the final two pieces are split evenly instead.

  For the small inputs produced by boundary normalization (~512 elements)
  this is constant-time work."
  [elems]
  (let [n (count elems)]
    (cond
      (<= n 0)                  []
      (<= n +target-chunk-size+) [elems]
      :else
      (loop [pos 0, acc (transient [])]
        (let [rem (- n pos)]
          (cond
            (<= rem +target-chunk-size+)
            (persistent! (conj! acc (subvec elems pos n)))

            (< (- rem +target-chunk-size+) +min-chunk-size+)
            ;; Taking a full target chunk would leave a runt below min.
            ;; Split the remaining portion evenly so both halves >= min.
            (let [half (quot rem 2)]
              (persistent!
                (conj! (conj! acc (subvec elems pos (+ pos half)))
                  (subvec elems (+ pos half) n))))

            :else
            (recur (+ pos +target-chunk-size+)
              (conj! acc (subvec elems pos (+ pos +target-chunk-size+))))))))))

(defn normalize-root
  "Full O(n) rechunk of a rope tree so every chunk satisfies CSI."
  [root]
  (if (leaf? root)
    nil
    (chunks->root
      (rechunk-balanced (vec (mapcat seq (root->chunks root)))))))

(defn- rope-remove-greatest
  [n]
  (let [create rope-node-create]
    (letfn [(rm-greatest [n]
              (cond
                (leaf? n)      (throw (ex-info "remove-greatest: empty rope" {:node n}))
                (leaf? (-r n)) (-l n)
                :else          (tree/node-stitch (-k n) nil (-l n)
                                 (rm-greatest (-r n)) create)))]
      (rm-greatest n))))

(defn- raw-rope-concat
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

(defn- rope-join
  "Balanced join: elements of l, then chunk, then elements of r.
  Analogous to node-concat3 but positional rather than comparator-ordered.
  Cost is O(|height(l) - height(r)|) when both are non-leaf."
  [chunk l r]
  (let [create rope-node-create]
    (cond
      (and (leaf? l) (leaf? r))
      (chunk-node chunk)

      (leaf? l)
      (raw-rope-concat (chunk-node chunk) r)

      (leaf? r)
      (raw-rope-concat l (chunk-node chunk))

      :else
      (let [lw (tree/node-weight l)
            rw (tree/node-weight r)]
        (cond
          (< (* tree/+delta+ lw) rw)
          (tree/node-stitch (node-chunk r) nil
            (rope-join chunk l (-l r))
            (-r r)
            create)

          (< (* tree/+delta+ rw) lw)
          (tree/node-stitch (node-chunk l) nil
            (-l l)
            (rope-join chunk (-r l) r)
            create)

          :else
          (create chunk nil l r))))))

(defn- ensure-right-fringe
  "Restore CSI when the rightmost chunk may be undersized.
  If the rightmost chunk is below min and there are >= 2 chunks,
  merge it with its left neighbor and rechunk the pair so both
  halves are in [min, target]."
  [root]
  (if (leaf? root)
    root
    (if (or (>= (count (node-chunk (tree/node-greatest root))) +min-chunk-size+)
            (<= (tree/node-size root) 1))
      root
      (let [last-chunk (node-chunk (tree/node-greatest root))
            rest       (rope-remove-greatest root)
            prev-chunk (node-chunk (tree/node-greatest rest))
            rest2      (rope-remove-greatest rest)
            combined   (into prev-chunk last-chunk)
            mid        (chunks->root (rechunk-balanced combined))]
        (raw-rope-concat rest2 mid)))))

(defn- ensure-left-fringe
  "Restore CSI when the leftmost chunk may be undersized.
  If the leftmost chunk is below min and there are >= 2 chunks,
  merge it with its right neighbor. If the merged result fits in
  one chunk, use it. Otherwise split at min so both halves are
  in [min, target]."
  [root]
  (if (leaf? root)
    root
    (let [create rope-node-create]
      (if (or (>= (count (node-chunk (tree/node-least root))) +min-chunk-size+)
              (<= (tree/node-size root) 1))
        root
        (let [first-chunk (node-chunk (tree/node-least root))
              rest        (tree/node-remove-least root create)
              next-chunk  (node-chunk (tree/node-least rest))
              rest2       (tree/node-remove-least rest create)
              combined    (into first-chunk next-chunk)
              cn          (count combined)]
          (if (<= cn +target-chunk-size+)
            (raw-rope-concat (chunk-node combined) rest2)
            ;; Split at min: left piece = min (valid internal),
            ;; right piece = cn - min which is in [min+1, target-1]
            ;; because cn is in (target, 2*target) and min = target/2.
            (let [c1 (subvec combined 0 +min-chunk-size+)
                  c2 (subvec combined +min-chunk-size+)]
              (raw-rope-concat
                (raw-rope-concat (chunk-node c1) (chunk-node c2))
                rest2))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural Concatenation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- merge-boundary
  "Merge the boundary chunks between two trees to restore CSI.
  The rightmost chunk of l (the runt-allowed position) is becoming
  internal, so it must be >= min. If the combined boundary chunks
  are still below min, pull one more neighbor chunk."
  [l r lchunk rchunk]
  (let [create   rope-node-create
        l'       (rope-remove-greatest l)
        r'       (tree/node-remove-least r create)
        combined (into lchunk rchunk)
        cn       (count combined)]
    (if (or (>= cn +min-chunk-size+)
            (and (leaf? l') (leaf? r')))
      ;; Combined is large enough or it is the only content
      (let [mid (chunks->root (rechunk-balanced combined))]
        (raw-rope-concat (raw-rope-concat l' mid) r'))
      ;; Combined still below min — pull one more neighbor
      (if-not (leaf? l')
        (let [prev  (node-chunk (tree/node-greatest l'))
              l''   (rope-remove-greatest l')
              all   (into prev combined)
              mid   (chunks->root (rechunk-balanced all))]
          (raw-rope-concat (raw-rope-concat l'' mid) r'))
        ;; l' is empty so r' must be non-empty (both-empty handled above)
        (let [nxt  (node-chunk (tree/node-least r'))
              r''  (tree/node-remove-least r' create)
              all  (into combined nxt)
              mid  (chunks->root (rechunk-balanced all))]
          (raw-rope-concat (raw-rope-concat l' mid) r''))))))

(defn rope-concat
  "Concatenate two rope trees, preserving left-before-right order.
  Ensures the Chunk Size Invariant holds on the result: every chunk
  is in [min, target] except possibly the rightmost.

  Position-aware: l's rightmost becomes internal (must be >= min).
  r's leftmost becomes internal only if r has >= 2 chunks; if r has
  exactly 1 chunk it becomes the rightmost of the result and any
  size in [1, target] is valid."
  [l r]
  (cond
    (leaf? l) r
    (leaf? r) l
    :else
    (let [lchunk (node-chunk (tree/node-greatest l))
          rchunk (node-chunk (tree/node-least r))
          ;; l's rightmost becomes internal after concat
          l-ok (>= (count lchunk) +min-chunk-size+)
          ;; r's leftmost stays as rightmost (any size ok) when r has 1 chunk
          r-ok (or (<= (tree/node-size r) 1)
                   (>= (count rchunk) +min-chunk-size+))]
      (if (and l-ok r-ok)
        (raw-rope-concat l r)
        (merge-boundary l r lchunk rchunk)))))


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
  "Split rope tree at element index i, returning [left right].
  Uses rope-join (concat3) during unwind so total cost is O(log n),
  matching the standard split-join pattern from Blelloch et al."
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
                      [ll (rope-join chunk lr r)])

                    (= i ls)
                    [l (rope-join chunk (leaf) r)]

                    (< i rs)
                    (let [offset (- i ls)
                          lc     (subvec chunk 0 offset)
                          rc     (subvec chunk offset cs)]
                      [(if (pos? (count lc))
                         (raw-rope-concat l (chunk-node lc))
                         l)
                       (if (pos? (count rc))
                         (raw-rope-concat (chunk-node rc) r)
                         r)])

                    (= i rs)
                    [(rope-join chunk l (leaf)) r]

                    :else
                    (let [[rl rr] (split* r (- i rs))]
                      [(rope-join chunk l rl) rr]))))]
        (split* root i)))))

(defn rope-subvec-root
  [root ^long start ^long end]
  (letfn [(slice* [n ^long start ^long end]
            (cond
              (leaf? n) nil
              (>= start end) nil
              :else
              (let [size  (rope-size n)]
                (if (and (<= start 0) (>= end size))
                  n
                  (let [chunk (node-chunk n)
                        l     (-l n)
                        r     (-r n)
                        ls    (rope-size l)
                        cs    (count chunk)
                        rs    (+ ls cs)
                        left  (when (< start ls)
                                (slice* l start (min end ls)))
                        c0    (max 0 (- start ls))
                        c1    (min cs (- end ls))
                        mid   (when (< c0 c1)
                                (chunk-node (subvec chunk c0 c1)))
                        right (when (> end rs)
                                (slice* r (max 0 (- start rs)) (- end rs)))]
                    (raw-rope-concat
                      (raw-rope-concat left mid)
                      right))))))]
    (-> (slice* root start end)
      ensure-left-fringe
      ensure-right-fringe)))

(defn ensure-split-parts
  "Restore CSI on both halves of a split. The left half may have an
  undersized right fringe; the right half may have an undersized left
  fringe."
  [[l r]]
  [(ensure-right-fringe l)
   (ensure-left-fringe r)])

(defn rope-peek-right
  [root]
  (when-not (leaf? root)
    (let [chunk (node-chunk (tree/node-greatest root))]
      (peek chunk))))

(defn rope-pop-right
  [root]
  (if (leaf? root)
    (throw (IllegalStateException. "Can't pop empty vector"))
    (let [create rope-node-create]
      (letfn [(pop* [n]
                (let [chunk (node-chunk n)
                      l     (-l n)
                      r     (-r n)]
                  (if (leaf? r)
                    (if (> (count chunk) 1)
                      (create (pop chunk) nil l r)
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

(defn- reduce-chunk-indexed
  "Fallback reduce for SubVector and other non-IReduceInit chunks."
  [f acc chunk]
  (let [^clojure.lang.Indexed chunk chunk
        n (.count ^clojure.lang.Counted chunk)]
    (loop [i (int 0) acc acc]
      (if (< i n)
        (let [ret (f acc (.nth chunk i))]
          (if (reduced? ret)
            ret
            (recur (unchecked-inc-int i) ret)))
        acc))))

(defn rope-reduce
  "Direct in-order tree walk: left subtree, chunk, right subtree.
  Bypasses the enumerator infrastructure to eliminate EnumFrame allocation
  and per-chunk lambda overhead. The right-subtree continuation uses recur
  for zero stack growth on that branch; left-subtree recursion depth is
  bounded by tree height (O(log n)).

  For IReduceInit chunks (PersistentVector), delegates to the vector's
  native reduce for tight array iteration. A single volatile + wrapper
  closure is allocated once per reduce call (not per chunk) to detect
  early termination from reduced."
  ([f init root]
   (if (leaf? root)
     init
     (let [stopped (volatile! false)
           wf      (fn [acc x]
                     (let [ret (f acc x)]
                       (if (reduced? ret)
                         (do (vreset! stopped true) (reduced @ret))
                         ret)))]
       (letfn [(reduce-chunk [acc chunk]
                 (if (instance? clojure.lang.IReduceInit chunk)
                   (let [result (.reduce ^clojure.lang.IReduceInit chunk wf acc)]
                     (if @stopped (reduced result) result))
                   (reduce-chunk-indexed f acc chunk)))
               (walk [acc n]
                 (if (leaf? n)
                   acc
                   (let [acc (walk acc (-l n))]
                     (if (reduced? acc)
                       acc
                       (let [acc (reduce-chunk acc (-k n))]
                         (if (reduced? acc)
                           acc
                           (recur acc (-r n))))))))]
         (let [result (walk init root)]
           (if (reduced? result) @result result))))))
  ;; 1-arity duplicates the walk/reduce-chunk letfn because both arities
  ;; close over the same volatile + wrapper; factoring it out would require
  ;; passing them as arguments through the recursive walk for no readability gain.
  ([f root]
   (if (leaf? root)
     (f)
     (let [stopped (volatile! false)
           wf      (fn [acc x]
                     (let [ret (f acc x)]
                       (if (reduced? ret)
                         (do (vreset! stopped true) (reduced @ret))
                         ret)))]
       (letfn [(reduce-chunk [acc chunk]
                 (if (instance? clojure.lang.IReduceInit chunk)
                   (let [result (.reduce ^clojure.lang.IReduceInit chunk wf acc)]
                     (if @stopped (reduced result) result))
                   (reduce-chunk-indexed f acc chunk)))
               (walk [acc n]
                 (if (leaf? n)
                   acc
                   (let [acc (walk acc (-l n))]
                     (if (reduced? acc)
                       acc
                       (let [acc (reduce-chunk acc (-k n))]
                         (if (reduced? acc)
                           acc
                           (recur acc (-r n))))))))]
         ;; Bootstrap: first element as init, then reduce the rest
         (let [least  (tree/node-least root)
               chunk0 (-k least)
               init   (.nth ^clojure.lang.Indexed chunk0 0)
               acc0   (reduce-chunk init
                        (subvec chunk0 1 (.count ^clojure.lang.Counted chunk0)))]
           (if (reduced? acc0)
             @acc0
             (let [rest-root (tree/node-remove-least root rope-node-create)
                   result    (walk acc0 rest-root)]
               (if (reduced? result) @result result)))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Materialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope->str
  "Efficient rope-to-string via StringBuilder. Appends each chunk's
  elements directly, avoiding lazy seq overhead."
  ^String [root]
  (if (leaf? root)
    ""
    (let [sb (StringBuilder. (int (rope-size root)))]
      (letfn [(walk [n]
                (when-not (leaf? n)
                  (walk (-l n))
                  (let [chunk (-k n)]
                    (dotimes [i (count chunk)]
                      (.append sb (nth chunk i))))
                  (walk (-r n))))]
        (walk root))
      (.toString sb))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant Checking
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn invariant-valid?
  "Check that a rope root satisfies the Chunk Size Invariant:
  - every chunk has size in [1, target]
  - every chunk except the rightmost has size >= min
  Returns true if CSI holds, false otherwise."
  [root]
  (if (leaf? root)
    true
    (let [chunks (root->chunks root)
          sizes  (mapv count chunks)]
      (and
        (every? pos? sizes)
        (every? #(<= % +target-chunk-size+) sizes)
        (every? #(>= % +min-chunk-size+) (butlast sizes))))))
