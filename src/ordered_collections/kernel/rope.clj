(ns ordered-collections.kernel.rope
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
  (:refer-clojure :exclude [chunk-append])
  (:require [ordered-collections.protocol :as proto
             :refer [chunk-length chunk-slice chunk-merge chunk-nth
                     chunk-append chunk-last chunk-butlast chunk-update
                     chunk-of chunk-reduce-init chunk-append-sb
                     chunk-splice chunk-splice-split]]
            ;; Force-load PRopeChunk extensions for the built-in chunk
            ;; backends (APersistentVector, String, byte[]). These must be
            ;; loaded before any rope-kernel function dispatches on a chunk.
            [ordered-collections.kernel.chunk]
            [ordered-collections.kernel.node :as node
             :refer [leaf leaf? -k -v -l -r]]
            [ordered-collections.kernel.tree :as tree]
            [ordered-collections.parallel :as par])
  (:import  [clojure.lang Murmur3 SeqIterator Util]))


;; Library-wide default CSI values. Kept as plain defs so external code
;; (tests, benchmarks) can reference them. Internal kernel code reads
;; from the dynamic vars below, which each rope variant binds inside
;; its `with-tree` macro to its own per-variant constants. This lets
;; the generic rope, string-rope, and byte-rope each carry a chunk size
;; that is appropriate for their underlying storage.
;;
;; Defaults are 1024/512 after tuning via `lein bench-rope-tuning`.
;; The generic rope, string rope, and byte rope all benefit from larger
;; chunks at 100K+ element counts. See each types/*_rope.clj file for
;; the per-variant rationale.
(def +target-chunk-size+ 1024)
(def +min-chunk-size+    512)

(def ^:dynamic *target-chunk-size* +target-chunk-size+)
(def ^:dynamic *min-chunk-size*    +min-chunk-size+)

(declare raw-rope-concat)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope Node Basics
;;
;; The chunk-backend protocol extensions this kernel dispatches on
;; (APersistentVector, String, byte[]) live in
;; `ordered-collections.kernel.chunk`, loaded via the ns require above.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope-size
  "Total element count across all chunks."
  ^long [root]
  (if (leaf? root) 0 (long (-v root))))

(defn chunk-count
  "Number of chunk nodes in the tree."
  ^long [root]
  (tree/node-size root))

(defn rope-node-create
  "Create a rope node for generic (vector) chunks. The node value stores
  subtree element count; the balance metric remains ordinary node count.
  This is the *t-join* binding for the generic Rope type."
  [chunk _ l r]
  (node/->SimpleNode chunk
    (+ (chunk-length chunk) (rope-size l) (rope-size r))
    l r
    (+ 1 (tree/node-size l) (tree/node-size r))))

(defn string-rope-node-create
  "Create a rope node for String chunks. The node value stores subtree
  character count. This is the *t-join* binding for StringRope."
  [^String chunk _ l r]
  (node/->SimpleNode chunk
    (+ (.length chunk) (rope-size l) (rope-size r))
    l r
    (+ 1 (tree/node-size l) (tree/node-size r))))

(defn byte-rope-node-create
  "Create a rope node for byte[] chunks. The node value stores subtree
  byte count. This is the *t-join* binding for ByteRope."
  [^bytes chunk _ l r]
  (node/->SimpleNode chunk
    (+ (alength chunk) (rope-size l) (rope-size r))
    l r
    (+ 1 (tree/node-size l) (tree/node-size r))))

(defn- chunk-node
  [chunk]
  (when (pos? (chunk-length chunk))
    (tree/*t-join* chunk nil (leaf) (leaf))))

(defn- node-chunk
  [n]
  (-k n))

(defn- build-root
  [chunks]
  (let [create tree/*t-join*]
    (letfn [(build [chunks]
              (let [n (count chunks)]
                (when (pos? n)
                  (let [mid   (quot n 2)
                        chunk (nth chunks mid)]
                    (create chunk nil
                      (build (subvec chunks 0 mid))
                      (build (subvec chunks (inc mid) n)))))))]
      (build chunks))))

(defn chunks->root
  "Build a balanced rope tree from a sequence of chunks.
  Empty chunks are filtered out."
  [chunks]
  (build-root (into [] (remove #(zero? (chunk-length %))) chunks)))

(defn root->chunks
  "Extract all chunks from a rope tree in order."
  [root]
  (if (leaf? root)
    []
    (tree/node-reduce-keys conj [] root)))

(defn coll->root
  "Build a rope tree from a sequential collection, partitioned into chunks.
  Uses the currently bound `*target-chunk-size*`."
  [coll]
  (chunks->root (mapv vec (partition-all (long *target-chunk-size*) coll))))

(defn str->root
  "Build a rope tree from a String, partitioned into substring chunks.
  Uses the currently bound `*target-chunk-size*`."
  [^String s]
  (let [n (.length s)
        target (long *target-chunk-size*)]
    (when (pos? n)
      (build-root
        (loop [pos 0, acc (transient [])]
          (if (>= pos n)
            (persistent! acc)
            (let [end (min n (+ pos target))]
              (recur end (conj! acc (.substring s pos end))))))))))

(defn bytes->root
  "Build a rope tree from a byte array, partitioned into target-sized byte[] chunks.
  Always copies the input so the tree never shares mutable state with the caller.
  Uses the currently bound `*target-chunk-size*`."
  [^bytes data]
  (let [n (alength data)
        target (long *target-chunk-size*)]
    (when (pos? n)
      (build-root
        (loop [pos 0, acc (transient [])]
          (if (>= pos n)
            (persistent! acc)
            (let [end (int (min n (+ pos target)))]
              (recur end (conj! acc
                           (java.util.Arrays/copyOfRange data (int pos) end))))))))))

(defn byte-rope->bytes
  "Materialize a byte rope tree to a single byte array via bulk arraycopy. O(n)."
  ^bytes [root]
  (if (leaf? root)
    (byte-array 0)
    (let [n (long (rope-size root))
          result (byte-array n)
          offset (long-array 1)]
      (letfn [(walk [n]
                (when-not (leaf? n)
                  (walk (-l n))
                  (let [^bytes chunk (-k n)
                        clen (alength chunk)
                        off  (aget offset 0)]
                    (System/arraycopy chunk 0 result (int off) clen)
                    (aset offset 0 (unchecked-add off clen)))
                  (walk (-r n))))]
        (walk root))
      result)))

(defn chunks->root-csi
  "Build a rope tree from a sequence of chunks, ensuring CSI.
  Scans left to right, accumulating a current chunk. When the current
  chunk reaches [min, target] it is emitted; when it would exceed target
  it is split evenly. The last chunk is emitted at any size (the runt).
  Uses the currently bound `*target-chunk-size*` and `*min-chunk-size*`."
  [chunks]
  (let [chunks (into [] (remove #(zero? (chunk-length %))) chunks)
        n      (count chunks)
        target (long *target-chunk-size*)
        minsz  (long *min-chunk-size*)]
    (if (<= n 1)
      (build-root chunks)
      (let [fixed (loop [i (int 0), cur nil, acc (transient [])]
                    (if (>= i n)
                      (persistent! (if cur (conj! acc cur) acc))
                      (let [chunk (nth chunks i)
                            merged (if cur (chunk-merge cur chunk) chunk)
                            mn     (long (chunk-length merged))]
                        (cond
                          ;; Merged chunk fits in target — keep accumulating
                          (<= mn target)
                          (if (and (>= mn minsz)
                                   (< (unchecked-inc-int i) n))
                            ;; Large enough and not last — emit and reset
                            (recur (unchecked-inc-int i) nil (conj! acc merged))
                            ;; Still small or last — carry forward
                            (recur (unchecked-inc-int i) merged acc))

                          ;; Exceeds target — split evenly, emit first half
                          :else
                          (let [half (quot mn 2)]
                            (recur (unchecked-inc-int i)
                              (chunk-slice merged half mn)
                              (conj! acc (chunk-slice merged 0 half))))))))]
        (build-root fixed)))))

(defn- rechunk-balanced
  "Partition a merged chunk into sub-chunks satisfying CSI:
  every chunk in [min, target] except possibly the last which may be
  smaller. When the last full-sized chunk would leave a remainder
  below min, the final two pieces are split evenly instead.

  For the small inputs produced by boundary normalization (~512 elements)
  this is constant-time work."
  [elems]
  (let [n      (long (chunk-length elems))
        target (long *target-chunk-size*)
        minsz  (long *min-chunk-size*)]
    (cond
      (<= n 0)      []
      (<= n target) [elems]
      :else
      (loop [pos 0, acc (transient [])]
        (let [rem (- n pos)]
          (cond
            (<= rem target)
            (persistent! (conj! acc (chunk-slice elems pos n)))

            (< (- rem target) minsz)
            ;; Taking a full target chunk would leave a runt below min.
            ;; Split the remaining portion evenly so both halves >= min.
            (let [half (quot rem 2)]
              (persistent!
                (conj! (conj! acc (chunk-slice elems pos (+ pos half)))
                  (chunk-slice elems (+ pos half) n))))

            :else
            (recur (+ pos target)
              (conj! acc (chunk-slice elems pos (+ pos target))))))))))

(defn- rechunk-root
  "Build a rope root from a merged chunk by rechunking to CSI.
  Boundary repair only produces a handful of chunks, so it is worth
  constructing those shapes directly instead of routing through the
  general chunks->root builder."
  [elems]
  (let [chunks (rechunk-balanced elems)
        create tree/*t-join*]
    (case (count chunks)
      0 nil
      1 (chunk-node (nth chunks 0))
      2 (raw-rope-concat
          (chunk-node (nth chunks 0))
          (chunk-node (nth chunks 1)))
      3 (create (nth chunks 1) nil
          (chunk-node (nth chunks 0))
          (chunk-node (nth chunks 2)))
      (build-root chunks))))

(defn normalize-root
  "Full O(n) rechunk of a rope tree so every chunk satisfies CSI.
  Note: materializes all chunks to elements and reassembles as vectors.
  For string ropes, use chunks->root-csi on root->chunks instead."
  [root]
  (if (leaf? root)
    nil
    (chunks->root
      (rechunk-balanced (vec (mapcat seq (root->chunks root)))))))

(defn- rope-remove-greatest
  [n]
  (let [create tree/*t-join*]
    (letfn [(rm-greatest [n]
              (cond
                (leaf? n)      (throw (ex-info "remove-greatest: empty rope" {:node n}))
                (leaf? (-r n)) (-l n)
                :else          (tree/node-stitch (-k n) nil (-l n)
                                 (rm-greatest (-r n)) create)))]
      (rm-greatest n))))

(defn- raw-rope-concat
  ([l r] (raw-rope-concat l r tree/*t-join*))
  ([l r create]
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
  (let [create tree/*t-join*]
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
    (let [minsz (long *min-chunk-size*)]
      (if (or (>= (chunk-length (node-chunk (tree/node-greatest root))) minsz)
              (<= (tree/node-size root) 1))
        root
        (let [last-chunk (node-chunk (tree/node-greatest root))
              rest       (rope-remove-greatest root)
              prev-chunk (node-chunk (tree/node-greatest rest))
              rest2      (rope-remove-greatest rest)
              combined   (chunk-merge prev-chunk last-chunk)
              mid        (rechunk-root combined)]
          (raw-rope-concat rest2 mid))))))

(defn- ensure-left-fringe
  "Restore CSI when the leftmost chunk may be undersized.
  If the leftmost chunk is below min and there are >= 2 chunks,
  merge it with its right neighbor. If the merged result fits in
  one chunk, use it. Otherwise split at min so both halves are
  in [min, target]."
  [root]
  (if (leaf? root)
    root
    (let [create tree/*t-join*
          target (long *target-chunk-size*)
          minsz  (long *min-chunk-size*)]
      (if (or (>= (chunk-length (node-chunk (tree/node-least root))) minsz)
              (<= (tree/node-size root) 1))
        root
        (let [first-chunk (node-chunk (tree/node-least root))
              rest        (tree/node-remove-least root create)
              next-chunk  (node-chunk (tree/node-least rest))
              rest2       (tree/node-remove-least rest create)
              combined    (chunk-merge first-chunk next-chunk)
              cn          (long (chunk-length combined))]
          (if (<= cn target)
            (raw-rope-concat (chunk-node combined) rest2)
            ;; Split at min: left piece = min (valid internal),
            ;; right piece = cn - min which is in [min+1, target-1]
            ;; because cn is in (target, 2*target) and min = target/2.
            (let [c1 (chunk-slice combined 0 minsz)
                  c2 (chunk-slice combined minsz cn)]
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
  (let [create   tree/*t-join*
        minsz    (long *min-chunk-size*)
        l'       (rope-remove-greatest l)
        r'       (tree/node-remove-least r create)
        combined (chunk-merge lchunk rchunk)
        cn       (long (chunk-length combined))]
    (if (or (>= cn minsz)
            (and (leaf? l') (leaf? r')))
      ;; Combined is large enough or it is the only content
      (let [mid (rechunk-root combined)]
        (raw-rope-concat (raw-rope-concat l' mid) r'))
      ;; Combined still below min — pull one more neighbor
      (if-not (leaf? l')
        (let [prev  (node-chunk (tree/node-greatest l'))
              l''   (rope-remove-greatest l')
              all   (chunk-merge prev combined)
              mid   (rechunk-root all)]
          (raw-rope-concat (raw-rope-concat l'' mid) r'))
        ;; l' is empty so r' must be non-empty (both-empty handled above)
        (let [nxt  (node-chunk (tree/node-least r'))
              r''  (tree/node-remove-least r' create)
              all  (chunk-merge combined nxt)
              mid  (rechunk-root all)]
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
    (let [minsz  (long *min-chunk-size*)
          lchunk (node-chunk (tree/node-greatest l))
          rchunk (node-chunk (tree/node-least r))
          ;; l's rightmost becomes internal after concat
          l-ok (>= (chunk-length lchunk) minsz)
          ;; r's leftmost stays as rightmost (any size ok) when r has 1 chunk
          r-ok (or (<= (tree/node-size r) 1)
                   (>= (chunk-length rchunk) minsz))]
      (if (and l-ok r-ok)
        (raw-rope-concat l r)
        (merge-boundary l r lchunk rchunk)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Indexed Access and Update
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope-nth
  [root ^long i]
  (loop [n root, i i]
    (let [l  (-l n)
          ls (if (leaf? l) 0 (long (-v l)))
          ck (-k n)
          cs (long (chunk-length ck))
          rs (+ ls cs)]
      (cond
        (< i ls) (recur l i)
        (< i rs) (chunk-nth ck (- i ls))
        :else (recur (-r n) (- i rs))))))

(defn rope-chunk-at
  "Find the chunk containing index i and its global start offset.
   Returns [chunk chunk-start-offset]. Index must be in bounds."
  [root ^long i]
  (loop [n root, i i, offset (long 0)]
    (let [l  (-l n)
          ls (if (leaf? l) 0 (long (-v l)))
          ck (-k n)
          cs (long (chunk-length ck))
          rs (+ ls cs)]
      (cond
        (< i ls) (recur l i offset)
        (< i rs) [ck (+ offset ls)]
        :else    (recur (-r n) (- i rs) (+ offset rs))))))

(defn rope-assoc
  [root ^long i x]
  (let [create tree/*t-join*]
    (letfn [(assoc* [n ^long i]
              (let [ck (-k n)
                    l  (-l n)
                    r  (-r n)
                    ls (if (leaf? l) 0 (long (-v l)))
                    cs (long (chunk-length ck))
                    rs (+ ls cs)]
                (cond
                  (< i ls)
                  (tree/node-stitch ck nil (assoc* l i) r create)

                  (< i rs)
                  (create (chunk-update ck (- i ls) x) nil l r)

                  :else
                  (tree/node-stitch ck nil l
                    (assoc* r (- i rs))
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
                (let [chunk (-k n)
                      l     (-l n)
                      r     (-r n)
                      ls    (if (leaf? l) 0 (long (-v l)))
                      cs    (long (chunk-length chunk))
                      rs    (+ ls cs)]
                  (cond
                    (< i ls)
                    (let [[ll lr] (split* l i)]
                      [ll (rope-join chunk lr r)])

                    (= i ls)
                    [l (rope-join chunk (leaf) r)]

                    (< i rs)
                    (let [offset (- i ls)
                          lc     (chunk-slice chunk 0 offset)
                          rc     (chunk-slice chunk offset cs)]
                      [(if (pos? (chunk-length lc))
                         (raw-rope-concat l (chunk-node lc))
                         l)
                       (if (pos? (chunk-length rc))
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
  (letfn [(slice-3 [left mid-chunk right]
            (cond
              (nil? mid-chunk)
              (raw-rope-concat left right)

              (leaf? left)
              (if (leaf? right)
                (chunk-node mid-chunk)
                (raw-rope-concat (chunk-node mid-chunk) right))

              (leaf? right)
              (raw-rope-concat left (chunk-node mid-chunk))

              :else
              (rope-join mid-chunk left right)))
          (slice* [n ^long start ^long end]
            (cond
              (leaf? n) nil
              (>= start end) nil
              :else
              (let [size (long (-v n))]
                (if (and (<= start 0) (>= end size))
                  n
                  (let [chunk (-k n)
                        l     (-l n)
                        r     (-r n)
                        ls    (if (leaf? l) 0 (long (-v l)))
                        cs    (long (chunk-length chunk))
                        rs    (+ ls cs)]
                    (cond
                      (<= end ls)
                      (slice* l start end)

                      (>= start rs)
                      (slice* r (- start rs) (- end rs))

                      (and (>= start ls) (<= end rs))
                      (chunk-node (chunk-slice chunk (- start ls) (- end ls)))

                      :else
                      (let [left      (when (< start ls)
                                        (slice* l start ls))
                            c0        (max 0 (- start ls))
                            c1        (min cs (- end ls))
                            mid-chunk (when (< c0 c1)
                                        (chunk-slice chunk c0 c1))
                            right     (when (> end rs)
                                        (slice* r 0 (- end rs)))]
                        (slice-3 left mid-chunk right))))))))]
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

(defn rope-splice-root
  "Replace [start, end) in root with mid-root, where all arguments are rope
  roots. Uses raw positional splits and repairs only the fringes that remain
  exposed in the final result."
  [root ^long start ^long end mid-root]
  (let [[l r]  (rope-split-at root start)
        [_ rr] (rope-split-at r (- end start))]
    (cond
      (leaf? mid-root)
      (cond
        (leaf? l)  (ensure-left-fringe rr)
        (leaf? rr) (ensure-right-fringe l)
        :else      (rope-concat l rr))

      (leaf? l)
      (if (leaf? rr)
        mid-root
        (rope-concat mid-root rr))

      :else
      (let [left+mid (rope-concat l mid-root)]
        (if (leaf? rr)
          left+mid
          (rope-concat left+mid rr))))))

(defn rope-splice-inplace
  "Fused single-chunk splice: replace [start, end) with replacement-chunk in
  a single tree traversal. Returns new root when [start, end) falls entirely
  within one chunk and the result chunk is in [1, target], or nil to signal
  fallback to the multi-traversal path.

  replacement-chunk may be nil for pure removal. The chunk type must match
  the tree's chunk type (String for StringRope, vector for generic Rope)."
  [root start end replacement-chunk create]
  (when-not (leaf? root)
    (let [start         (long start)
          end           (long end)
          target        (long *target-chunk-size*)
          minsz         (long *min-chunk-size*)
          ;; Single-chunk trees allow any result in [1, target].
          ;; Multi-chunk trees require >= min to preserve CSI.
          single-chunk? (and (leaf? (-l root)) (leaf? (-r root)))
          min-len       (if single-chunk? 1 minsz)]
      (letfn [(splice* [n ^long start ^long end]
                (when-not (leaf? n)
                  (let [ck (-k n)
                        l  (-l n)
                        r  (-r n)
                        ls (if (leaf? l) 0 (long (-v l)))
                        cs (long (chunk-length ck))
                        rs (+ ls cs)]
                    (cond
                      ;; Range starts in left subtree
                      (< start ls)
                      (when (<= end ls)
                        (when-let [new-l (splice* l start end)]
                          (tree/node-stitch ck nil new-l r create)))

                      ;; Range starts in (or at end of) this chunk
                      (<= start rs)
                      (when (<= end rs)
                        (let [c-start (- start ls)
                              c-end   (- end ls)
                              rep-len (if replacement-chunk
                                        (long (chunk-length replacement-chunk))
                                        0)
                              new-len (long (+ cs (- rep-len (- c-end c-start))))]
                          (cond
                            ;; Result fits — simple replacement
                            (and (>= new-len min-len)
                                 (<= new-len target))
                            (create (chunk-splice ck c-start c-end
                                      replacement-chunk)
                              nil l r)

                            ;; Overflow — build two halves directly, no intermediate
                            (> new-len target)
                            (let [half    (quot new-len 2)
                                  [c1 c2] (chunk-splice-split ck c-start c-end
                                             replacement-chunk half)
                                  c2-node (create c2 nil (leaf) (leaf))]
                              (tree/node-stitch c1 nil l
                                (if (leaf? r)
                                  c2-node
                                  (raw-rope-concat c2-node r create))
                                create))

                            ;; Too small — fall back
                            :else nil)))

                      ;; Range starts in right subtree
                      :else
                      (when-let [new-r (splice* r (- start rs) (- end rs))]
                        (tree/node-stitch ck nil l new-r create))))))]
        (splice* root start end)))))

(defn rope-insert-root
  "Insert mid-root at start in root."
  [root ^long start mid-root]
  (rope-splice-root root start start mid-root))

(defn rope-remove-root
  "Remove [start, end) from root."
  [root ^long start ^long end]
  (rope-splice-root root start end nil))

(defn rope-peek-right
  [root]
  (when-not (leaf? root)
    (let [chunk (node-chunk (tree/node-greatest root))]
      (chunk-last chunk))))

(defn rope-pop-right
  [root]
  (if (leaf? root)
    (throw (IllegalStateException. "Can't pop empty vector"))
    (let [create tree/*t-join*]
      (letfn [(pop* [n]
                (let [ck (-k n)
                      l  (-l n)
                      r  (-r n)]
                  (if (leaf? r)
                    (if (> (chunk-length ck) 1)
                      (create (chunk-butlast ck) nil l r)
                      l)
                    (tree/node-stitch ck nil l (pop* r) create))))]
        (pop* root)))))

(defn rope-conj-right
  [root x]
  (if (leaf? root)
    (chunk-node [x])
    (let [create tree/*t-join*
          target (long *target-chunk-size*)]
      (letfn [(conj* [n]
                (let [ck (-k n)
                      l  (-l n)
                      r  (-r n)]
                  (if (leaf? r)
                    (if (< (chunk-length ck) target)
                      (create (chunk-append ck x) nil l r)
                      (tree/node-stitch ck nil l
                        (chunk-node (chunk-of ck x)) create))
                    (tree/node-stitch ck nil l (conj* r) create))))]
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

(defn- seq-equiv
  "Element-wise sequential equivalence."
  [s1 o]
  (if-not (or (instance? clojure.lang.Sequential o)
              (instance? java.util.List o))
    false
    (loop [s1 (seq s1) s2 (seq o)]
      (cond
        (nil? s1) (nil? s2)
        (nil? s2) false
        (not (Util/equiv (first s1) (first s2))) false
        :else (recur (next s1) (next s2))))))

(deftype RopeSeq [enum chunk ^long i cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (.nth ^clojure.lang.Indexed chunk (unchecked-int i)))
  (next [_]
    (let [next-cnt (when cnt (unchecked-dec-int cnt))
          next-i   (unchecked-inc i)]
      (if (< next-i (count chunk))
        (RopeSeq. enum chunk next-i next-cnt nil)
        (when-let [e (tree/node-enum-rest enum)]
          (let [chunk' (-k (tree/node-enum-first e))]
            (RopeSeq. e chunk' 0 next-cnt nil))))))
  (more [this]
    (or (.next this) ()))
  (cons [this o]
    (clojure.lang.Cons. o this))

  clojure.lang.Seqable
  (seq [this] this)

  clojure.lang.Sequential

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. this))

  clojure.lang.Counted
  (count [_]
    (or cnt
        (loop [e enum
               chunk chunk
               i i
               n 0]
          (let [n (+ n (- (count chunk) i))]
            (if-let [e' (tree/node-enum-rest e)]
              (let [chunk' (-k (tree/node-enum-first e'))]
                (recur e' chunk' 0 n))
              n)))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum
           chunk chunk
           i i
           acc init]
      (let [acc (loop [idx i
                       acc acc]
                  (if (< idx (count chunk))
                    (let [ret (f acc (.nth ^clojure.lang.Indexed chunk (unchecked-int idx)))]
                      (if (reduced? ret)
                        ret
                        (recur (unchecked-inc idx) ret)))
                    acc))]
        (if (reduced? acc)
          @acc
          (if-let [e' (tree/node-enum-rest e)]
            (let [chunk' (-k (tree/node-enum-first e'))]
              (recur e' chunk' 0 acc))
            acc)))))

  clojure.lang.IReduce
  (reduce [this f]
    (if enum
      (let [acc (.nth ^clojure.lang.Indexed chunk (unchecked-int i))
            next-i (unchecked-inc i)]
        (if (< next-i (count chunk))
          (.reduce ^clojure.lang.IReduceInit
            (RopeSeq. enum chunk next-i nil nil) f acc)
          (if-let [e' (tree/node-enum-rest enum)]
            (let [chunk' (-k (tree/node-enum-first e'))]
              (.reduce ^clojure.lang.IReduceInit
                (RopeSeq. e' chunk' 0 nil nil) f acc))
            acc)))
      (f)))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  clojure.lang.IPersistentCollection
  (empty [_] ())
  (equiv [this o]
    (seq-equiv this o))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (RopeSeq. enum chunk i cnt m)))

(deftype RopeSeqReverse [enum chunk ^long i cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (.nth ^clojure.lang.Indexed chunk (unchecked-int i)))
  (next [_]
    (let [next-cnt (when cnt (unchecked-dec-int cnt))]
      (if (pos? i)
        (RopeSeqReverse. enum chunk (unchecked-dec i) next-cnt nil)
        (when-let [e (tree/node-enum-prior enum)]
          (let [chunk' (-k (tree/node-enum-first e))]
            (RopeSeqReverse. e chunk' (dec (count chunk')) next-cnt nil))))))
  (more [this]
    (or (.next this) ()))
  (cons [this o]
    (clojure.lang.Cons. o this))

  clojure.lang.Seqable
  (seq [this] this)

  clojure.lang.Sequential

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. this))

  clojure.lang.Counted
  (count [_]
    (or cnt
        (loop [e enum
               chunk chunk
               i i
               n 0]
          (let [n (+ n (inc i))]
            (if-let [e' (tree/node-enum-prior e)]
              (let [chunk' (-k (tree/node-enum-first e'))]
                (recur e' chunk' (dec (count chunk')) n))
              n)))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [e enum
           chunk chunk
           i i
           acc init]
      (let [acc (loop [idx i
                       acc acc]
                  (if (neg? idx)
                    acc
                    (let [ret (f acc (.nth ^clojure.lang.Indexed chunk (unchecked-int idx)))]
                      (if (reduced? ret)
                        ret
                        (recur (unchecked-dec idx) ret)))))]
        (if (reduced? acc)
          @acc
          (if-let [e' (tree/node-enum-prior e)]
            (let [chunk' (-k (tree/node-enum-first e'))]
              (recur e' chunk' (dec (count chunk')) acc))
            acc)))))

  clojure.lang.IReduce
  (reduce [this f]
    (if enum
      (let [acc (.nth ^clojure.lang.Indexed chunk (unchecked-int i))]
        (if (pos? i)
          (.reduce ^clojure.lang.IReduceInit
            (RopeSeqReverse. enum chunk (unchecked-dec i) nil nil) f acc)
          (if-let [e' (tree/node-enum-prior enum)]
            (let [chunk' (-k (tree/node-enum-first e'))]
              (.reduce ^clojure.lang.IReduceInit
                (RopeSeqReverse. e' chunk' (dec (count chunk')) nil nil) f acc))
            acc)))
      (f)))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  clojure.lang.IPersistentCollection
  (empty [_] ())
  (equiv [this o]
    (seq-equiv this o))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (RopeSeqReverse. enum chunk i cnt m)))

(defn rope-seq
  "Forward seq over a rope tree's elements."
  [root]
  (when-let [enum (tree/node-enumerator root)]
    (let [chunk (-k (tree/node-enum-first enum))]
      (RopeSeq. enum chunk 0 (rope-size root) nil))))

(defn rope-rseq
  "Reverse seq over a rope tree's elements."
  [root]
  (when-let [enum (tree/node-enumerator-reverse root)]
    (let [chunk (-k (tree/node-enum-first enum))]
      (RopeSeqReverse. enum chunk (dec (count chunk)) (rope-size root) nil))))

(defn rope-chunks-reduce
  "Reduce over chunks (not individual elements) of a rope tree."
  ([f init root]
   (if (leaf? root)
     init
     (tree/node-reduce-keys f init root)))
  ([f root]
   (if (leaf? root)
     (f)
     (tree/node-reduce-keys f root))))


(defn- rope-tree-walk
  "In-order tree walk reducing with wf (a wrapper around f that tracks
  early termination via the stopped volatile). Left subtree recurses on
  the stack (bounded by height O(log n)); right subtree uses recur for
  zero stack growth. Returns result, possibly (reduced ...)."
  [wf stopped init root]
  (letfn [(reduce-chunk [acc chunk]
            (let [result (chunk-reduce-init chunk wf acc)]
              (if @stopped (reduced result) result)))
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
    (walk init root)))

(defn- wrap-reduce-fn
  "Create the stopped volatile + wrapper fn pair for rope-reduce.
  chunk-reduce-init derefs reduced values internally, so the stopped
  volatile provides a side-channel for the walk to detect early stop."
  [f]
  (let [stopped (volatile! false)
        wf      (fn [acc x]
                  (let [ret (f acc x)]
                    (if (reduced? ret)
                      (do (vreset! stopped true) (reduced @ret))
                      ret)))]
    [stopped wf]))

(defn rope-reduce
  "Direct in-order tree walk: left subtree, chunk, right subtree.
  Bypasses the enumerator infrastructure to eliminate EnumFrame allocation
  and per-chunk lambda overhead."
  ([f init root]
   (if (leaf? root)
     init
     (let [[stopped wf] (wrap-reduce-fn f)
           result (rope-tree-walk wf stopped init root)]
       (if (reduced? result) @result result))))
  ([f root]
   (if (leaf? root)
     (f)
     (let [[stopped wf] (wrap-reduce-fn f)
           least  (tree/node-least root)
           chunk0 (-k least)
           init   (chunk-nth chunk0 0)
           rest0  (chunk-slice chunk0 1 (chunk-length chunk0))
           acc0   (let [result (chunk-reduce-init rest0 wf init)]
                    (if @stopped (reduced result) result))]
       (if (reduced? acc0)
         @acc0
         (let [rest-root (tree/node-remove-least root)
               result    (rope-tree-walk wf stopped acc0 rest-root)]
           (if (reduced? result) @result result)))))))

(defn rope-fold
  "Parallel fold over the rope's existing tree shape.

  Unlike a split-based fold, this does not rebuild intermediate half-ropes.
  It recursively folds left subtree, current chunk, and right subtree in
  left-to-right order, using subtree sizes to decide when to stop splitting."
  [root ^long n combinef reducef]
  (letfn [(reduce-chunk [acc chunk]
            (chunk-reduce-init chunk reducef acc))
          (fold-node [node]
            (cond
              (leaf? node)
              (combinef)

              (<= (long (-v node)) n)
              (letfn [(walk [acc n]
                        (if (leaf? n)
                          acc
                          (let [acc (walk acc (-l n))
                                acc (reduce-chunk acc (-k n))]
                            (recur acc (-r n)))))]
                (walk (combinef) node))

              :else
              (let [l      (-l node)
                    chunk  (-k node)
                    r      (-r node)
                    chunkv (reduce-chunk (combinef) chunk)]
                (par/fork-join
                  [lv (fold-node l) rv (fold-node r)]
                  (combinef (combinef lv chunkv) rv)))))]
    (if (par/in-fork-join-pool?)
      (fold-node root)
      (par/invoke-root #(fold-node root)))))


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
                  (chunk-append-sb (-k n) sb)
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
  Returns true if CSI holds, false otherwise.

  Reads the currently bound `*target-chunk-size*` and `*min-chunk-size*`,
  so callers testing a particular rope variant should invoke this from
  inside that variant's `with-tree` binding (or the 3-arity form that
  takes explicit sizes)."
  ([root]
   (invariant-valid? root (long *target-chunk-size*) (long *min-chunk-size*)))
  ([root ^long target ^long minsz]
   (if (leaf? root)
     true
     (let [chunks (root->chunks root)
           sizes  (mapv #(long (chunk-length %)) chunks)]
       (and
         (every? pos? sizes)
         (every? #(<= (long %) target) sizes)
         (every? #(>= (long %) minsz) (butlast sizes)))))))
