(ns ordered-collections.types.rope
  "Persistent rope-like indexed sequence backed by an implicit-index
  weight-balanced tree."
  (:require [clojure.core.protocols :as cp]
            [clojure.core.reducers :as r]
            [ordered-collections.protocol :as proto]
            [ordered-collections.kernel.node :as node
             :refer [leaf? -k -v -l -r]]
            [ordered-collections.kernel.tree :as tree]
            [ordered-collections.kernel.rope :as ropetree])
  (:import  [clojure.lang RT Murmur3 MapEntry Indexed Util
                           IPersistentCollection IPersistentStack IPersistentVector
                           IEditableCollection ITransientCollection
                           IReduce IReduceInit SeqIterator]
            [ordered_collections.kernel.root INodeCollection]
            [java.util ArrayList]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants & tree binding macro
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const ^:private +target-chunk-size+
  "Generic Rope target chunk size (element count). Bound into the
  kernel's `*target-chunk-size*` dynamic var via `with-tree`. Tuned
  via `lein bench-rope-tuning`: at 100K+ elements, 1024 gives better
  nth/reduce/concat than the historical 256 with only a small splice
  regression (which is still ~6000x faster than PersistentVector)."
  1024)

(def ^:const ^:private +min-chunk-size+
  "Generic Rope minimum internal chunk size (= target/2)."
  512)

(def ^:const ^:private +flat-threshold+
  "Maximum element count stored in flat (raw PersistentVector)
  representation. Below this, the rope skips the tree wrapper entirely
  and holds the vector directly, eliminating the per-rope SimpleNode
  header and one layer of indirection on every operation. Above the
  threshold, the representation is transparently promoted to the
  chunked tree form. Matches `+target-chunk-size+` — a rope small
  enough to live in one chunk goes flat."
  1024)

(defmacro ^:private with-tree
  "Bind the kernel's dynamic rope context for generic Rope operations:
  `tree/*t-join*` to the allocator, and the CSI target/min to the
  generic-rope constants. Every tree-mutating operation must execute
  inside this binding."
  [alloc & body]
  `(binding [tree/*t-join*                ~alloc
             ropetree/*target-chunk-size* +target-chunk-size+
             ropetree/*min-chunk-size*    +min-chunk-size+]
     ~@body))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Flat-mode helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- flat?
  "True when root is a raw PersistentVector (flat representation).
  Also matches APersistentVector$SubVector, which rope-sub returns."
  [root]
  (instance? clojure.lang.APersistentVector root))

(defn- flat-size
  "Size of a flat or tree root. Handles nil, flat vector, and tree nodes."
  ^long [root]
  (cond
    (nil? root)  0
    (flat? root) (.count ^clojure.lang.Counted root)
    :else        (long (node/-v root))))

(defn- ensure-tree-root
  "Promote a flat vector root to a tree root. Tree nodes are returned
  unchanged. Caller must bind tree/*t-join* (and CSI vars)."
  [root]
  (cond
    (nil? root)  nil
    (flat? root) (if (zero? (.count ^clojure.lang.Counted root))
                   nil
                   (ropetree/chunks->root [root]))
    :else        root))

(defn- make-root
  "Create a Rope root from a (flat) vector. Stays flat if ≤ threshold,
  otherwise promotes to tree via str-like rechunking so internal
  chunks satisfy CSI. Caller must bind tree/*t-join* for promotion."
  [^clojure.lang.IPersistentVector v]
  (let [n (.length v)]
    (cond
      (zero? n)               nil
      (<= n +flat-threshold+) v
      :else                   (ropetree/coll->root v))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- valid-index?
  [^long n k]
  (and (integer? k)
       (let [i (long k)]
         (and (<= 0 i) (< i n)))))

(defn- insert-index?
  [^long n k]
  (and (integer? k)
       (let [i (long k)]
         (and (<= 0 i) (<= i n)))))

(defn- check-index!
  [n k]
  (when-not (valid-index? n k)
    (throw (IndexOutOfBoundsException.))))

(defn- check-insert-index!
  [n k]
  (when-not (insert-index? n k)
    (throw (IndexOutOfBoundsException.))))

(defn- check-range!
  "Validate [start, end) against rope of size n."
  [start end ^long n]
  (when (or (not (integer? start))
            (not (integer? end))
            (neg? (long start))
            (neg? (long end))
            (> (long start) (long end))
            (> (long end) n))
    (throw (IndexOutOfBoundsException.))))

(declare ->rope rope-root)

(defn- rope-equiv
  "Sequential equality with an indexed fast path for vectors.
   If both sides are IPersistentVector, compare by index. Otherwise fall back
   to element-wise sequential equivalence."
  [^IPersistentVector this o]
  (cond
    (identical? this o) true

    (instance? IPersistentVector o)
    (let [n (.length this)]
      (and (= n (.length ^IPersistentVector o))
           (loop [i 0]
             (if (= i n)
               true
               (if (Util/equiv (.nth this i) (.nth ^IPersistentVector o i))
                 (recur (unchecked-inc i))
                 false)))))

    (or (instance? clojure.lang.Sequential o)
        (instance? java.util.List o))
    (let [n (.length this)]
      (if (and (instance? clojure.lang.Counted o)
               (not= n (.count ^clojure.lang.Counted o)))
        false
        (loop [s (seq o) i 0]
          (cond
            (= i n) (nil? s)
            (nil? s) false
            (not (Util/equiv (.nth this i) (first s))) false
            :else (recur (next s) (unchecked-inc i))))))

    :else false))

(defn- seq-compare
  "Lexicographic comparison of two sequential collections.
   This is ordering, not equality: it uses `compare` element-by-element rather
   than `Util/equiv`."
  [s1 s2]
  (loop [s1 (seq s1) s2 (seq s2)]
    (cond
      (nil? s1) (if (nil? s2) 0 -1)
      (nil? s2) 1
      :else
      (let [c (compare (first s1) (first s2))]
        (if (zero? c)
          (recur (next s1) (next s2))
          c)))))

(defn- linear-index-of
  "Linear scan for first index of x, or -1."
  [root x]
  (cond
    (nil? root) -1
    (flat? root) (.indexOf ^java.util.List root x)
    :else
    (let [n      (ropetree/rope-size root)
          result (ropetree/rope-reduce
                   (fn [^long i elem]
                     (if (Util/equiv elem x)
                       (reduced i)
                       (unchecked-inc i)))
                   (long 0)
                   root)]
      (if (< (long result) n) (long result) -1))))

(defn- linear-last-index-of
  "Forward linear scan tracking the last matching index. O(n)."
  [root x]
  (cond
    (nil? root) -1
    (flat? root) (.lastIndexOf ^java.util.List root x)
    :else
    (let [found (volatile! (long -1))]
      (ropetree/rope-reduce
        (fn [^long i elem]
          (when (Util/equiv elem x) (vreset! found i))
          (unchecked-inc i))
        (long 0)
        root)
      (long @found))))

(defn- rope-to-array
  ^objects [root]
  (cond
    (nil? root)  (object-array 0)
    (flat? root) (.toArray ^java.util.Collection root)
    :else
    (let [n   (ropetree/rope-size root)
          arr (object-array n)]
      (ropetree/rope-reduce
        (fn [^long i x]
          (aset arr i x)
          (unchecked-inc i))
        (long 0)
        root)
      arr)))

(declare ->TransientRope)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Fields:
;;   root  — implicit-index weight-balanced tree of chunk vectors, or nil
;;   _meta — metadata map
;;
;; Each tree node stores a chunk vector in the key slot and the total
;; element count of the subtree in the value slot. Implements
;; IPersistentVector for full vector-contract compatibility, including
;; indexed access, assoc, conj-to-end, peek/pop-right.

(deftype Rope [root alloc _meta]

  java.io.Serializable
  java.util.RandomAccess

  INodeCollection
  (getAllocator [_] alloc)

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m] (Rope. root alloc m))

  clojure.lang.Counted
  (count [_]
    (flat-size root))

  Indexed
  ;; Monomorphic nth — inlines the tree walk so we can replace the kernel's
  ;; protocol-dispatched `chunk-length` and `chunk-nth` with direct calls
  ;; on `Counted` and `Indexed` interfaces. APersistentVector is the only
  ;; chunk type for generic Rope, so the type is known statically.
  (nth [_ i]
    (let [ii (long i)
          n  (flat-size root)]
      (when-not (valid-index? n ii)
        (throw (IndexOutOfBoundsException.)))
      (if (flat? root)
        ;; Flat mode: direct .nth on the backing PersistentVector/SubVector.
        (.nth ^clojure.lang.Indexed root (unchecked-int ii))
        ;; Tree mode: inline monomorphic walk using Counted + Indexed.
        (loop [nd root, j ii]
          (let [l  (-l nd)
                ls (if (leaf? l) 0 (long (-v l)))
                ck (-k nd)
                cs (long (.count ^clojure.lang.Counted ck))
                rs (+ ls cs)]
            (cond
              (< j ls) (recur l j)
              (< j rs) (.nth ^clojure.lang.Indexed ck (unchecked-int (- j ls)))
              :else    (recur (-r nd) (- j rs))))))))
  (nth [this i not-found]
    (if (and (integer? i) (valid-index? (flat-size root) (long i)))
      (.nth this (int i))
      not-found))

  clojure.lang.ILookup
  (valAt [this k]
    (.nth this k nil))
  (valAt [this k not-found]
    (.nth this k not-found))

  clojure.lang.IFn
  (invoke [this k]
    (.valAt this k))
  (invoke [this k not-found]
    (.valAt this k not-found))
  (applyTo [this args]
    (let [n (RT/boundedLength args 2)]
      (case n
        0 (throw (clojure.lang.ArityException. n (.. this getClass getSimpleName)))
        1 (.invoke this (first args))
        2 (.invoke this (first args) (second args))
        (throw (clojure.lang.ArityException. n (.. this getClass getSimpleName))))))

  clojure.lang.Associative
  (containsKey [_ k]
    (valid-index? (flat-size root) k))
  (entryAt [this k]
    (when (.containsKey this k)
      (MapEntry. k (.nth this k))))
  (assoc [this k v]
    (let [n (flat-size root)
          i (long k)]
      (cond
        (not (insert-index? n i))
        (throw (IndexOutOfBoundsException.))

        (flat? root)
        (let [^clojure.lang.IPersistentVector fv root]
          (if (= i n)
            ;; append
            (if (< n +flat-threshold+)
              (Rope. (.cons fv v) alloc _meta)
              (with-tree alloc
                (Rope. (ropetree/rope-conj-right (ensure-tree-root root) v) alloc _meta)))
            ;; replace
            (Rope. (.assocN fv (int i) v) alloc _meta)))

        :else
        (with-tree alloc
          (if (= i n)
            (Rope. (ropetree/rope-conj-right root v) alloc _meta)
            (Rope. (ropetree/rope-assoc root (long i) v) alloc _meta))))))

  IPersistentVector
  (assocN [this i v]
    (.assoc this i v))
  (length [_]
    (flat-size root))

  IPersistentCollection
  (cons [_ o]
    (cond
      (nil? root)
      (Rope. [o] alloc _meta)

      (flat? root)
      (let [^clojure.lang.IPersistentVector fv root
            n (.length fv)]
        (if (< n +flat-threshold+)
          (Rope. (.cons fv o) alloc _meta)
          (with-tree alloc
            (Rope. (ropetree/rope-conj-right (ensure-tree-root root) o) alloc _meta))))

      :else
      (with-tree alloc
        (Rope. (ropetree/rope-conj-right root o) alloc _meta))))
  (empty [_]
    (Rope. nil alloc _meta))
  (equiv [this o]
    (rope-equiv this o))

  IPersistentStack
  (peek [_]
    (cond
      (nil? root)  nil
      (flat? root) (let [^clojure.lang.IPersistentStack fv root]
                     (.peek fv))
      :else        (ropetree/rope-peek-right root)))
  (pop [_]
    (cond
      (nil? root)
      (throw (IllegalStateException. "Can't pop empty vector"))

      (flat? root)
      (let [^clojure.lang.IPersistentVector fv root
            n (.length fv)]
        (if (<= n 1)
          (Rope. nil alloc _meta)
          (Rope. (.pop ^clojure.lang.IPersistentStack fv) alloc _meta)))

      :else
      (with-tree alloc
        (Rope. (ropetree/rope-pop-right root) alloc _meta))))

  clojure.lang.Seqable
  (seq [_]
    (cond
      (nil? root)  nil
      (flat? root) (seq root)
      :else        (ropetree/rope-seq root)))

  clojure.lang.Reversible
  (rseq [_]
    (cond
      (nil? root)  nil
      (flat? root) (rseq ^clojure.lang.Reversible root)
      :else        (ropetree/rope-rseq root)))

  clojure.lang.Sequential

  java.lang.Comparable
  (compareTo [this o]
    (if (identical? this o)
      0
      (seq-compare this o)))

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. (seq this)))

  IReduceInit
  (reduce [_ f init]
    (cond
      (nil? root)  init
      ;; clojure.core/reduce dispatches via CollReduce/IReduceInit as
      ;; appropriate — works for PersistentVector and SubVector alike.
      (flat? root) (clojure.core/reduce f init root)
      :else        (ropetree/rope-reduce f init root)))

  IReduce
  (reduce [_ f]
    (cond
      (nil? root)  (f)
      (flat? root) (clojure.core/reduce f root)
      :else        (ropetree/rope-reduce f root)))

  cp/CollReduce
  (coll-reduce [this f]
    (.reduce ^IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^IReduceInit this f init))

  r/CollFold
  (coll-fold [this n combinef reducef]
    (cond
      (nil? root)  (combinef)
      (flat? root) (clojure.core/reduce reducef (combinef) root)
      :else        (ropetree/rope-fold root (long n) combinef reducef)))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  java.util.Collection
  (toArray [_]
    (rope-to-array root))
  (isEmpty [_]
    (zero? (flat-size root)))
  (^boolean contains [_ x]
    (not (neg? (linear-index-of root x))))
  (containsAll [this c]
    (every? #(.contains this %) c))
  (size [_]
    (int (flat-size root)))
  (add [_ _]
    (throw (UnsupportedOperationException.)))
  (addAll [_ _]
    (throw (UnsupportedOperationException.)))
  (^boolean remove [_ _]
    (throw (UnsupportedOperationException.)))
  (removeAll [_ _]
    (throw (UnsupportedOperationException.)))
  (retainAll [_ _]
    (throw (UnsupportedOperationException.)))
  (clear [_]
    (throw (UnsupportedOperationException.)))

  java.util.List
  (get [this i]
    (.nth this (int i)))
  (indexOf [_ x]
    (linear-index-of root x))
  (lastIndexOf [_ x]
    (linear-last-index-of root x))
  (set [_ _ _]
    (throw (UnsupportedOperationException.)))
  (subList [this from to]
    (proto/rope-sub this from to))

  proto/PRope
  (rope-cat [this other]
    (when-not (instance? Rope other)
      (throw (IllegalArgumentException. "Rope rope-cat requires a Rope")))
    (let [other-root (.-root ^Rope other)
          fv1 (when (flat? root) root)
          fv2 (when (flat? other-root) other-root)]
      (cond
        ;; Both empty
        (and (nil? root) (nil? other-root))
        (Rope. nil alloc _meta)

        ;; Other empty
        (nil? other-root) this

        ;; This empty
        (nil? root) (Rope. other-root alloc _meta)

        ;; Fast path: both flat and combined fits
        (and fv1 fv2
             (<= (+ (count fv1) (count fv2)) +flat-threshold+))
        (Rope. (into fv1 fv2) alloc _meta)

        :else
        (with-tree alloc
          (Rope. (ropetree/rope-concat (ensure-tree-root root)
                                        (ensure-tree-root other-root))
                 alloc _meta)))))
  (rope-split [_ i]
    (let [n (flat-size root)]
      (check-insert-index! n i)
      (let [ii (long i)]
        (cond
          (nil? root)
          [(Rope. nil alloc _meta) (Rope. nil alloc _meta)]

          (flat? root)
          (let [^clojure.lang.IPersistentVector fv root]
            [(Rope. (when (pos? ii) (subvec fv 0 (int ii))) alloc _meta)
             (Rope. (when (< ii n) (subvec fv (int ii) (int n))) alloc _meta)])

          :else
          (with-tree alloc
            (let [[l r] (ropetree/ensure-split-parts
                          (ropetree/rope-split-at root ii))]
              [(Rope. l alloc _meta) (Rope. r alloc _meta)]))))))
  (rope-sub [_ start end]
    (let [n (flat-size root)]
      (check-range! start end n)
      (let [si (long start)
            ei (long end)]
        (cond
          (or (nil? root) (= si ei))
          (Rope. nil alloc _meta)

          (flat? root)
          (let [^clojure.lang.IPersistentVector fv root]
            (Rope. (subvec fv (int si) (int ei)) alloc _meta))

          :else
          (with-tree alloc
            (Rope. (ropetree/rope-subvec-root root si ei) alloc _meta))))))
  (rope-insert [this i coll]
    (let [n (flat-size root)]
      (check-insert-index! n i)
      (let [ii (long i)
            ins (if (instance? clojure.lang.APersistentVector coll) coll (vec coll))
            ins-n (.count ^clojure.lang.Counted ins)]
        (cond
          (zero? ins-n) this

          ;; Flat fast path
          (flat? root)
          (let [^clojure.lang.IPersistentVector fv root
                total (+ n ins-n)]
            (if (<= total +flat-threshold+)
              ;; Build new flat vector directly
              (let [new-v (-> []
                              (into (subvec fv 0 (int ii)))
                              (into ins)
                              (into (subvec fv (int ii) (int n))))]
                (Rope. new-v alloc _meta))
              ;; Promote to tree
              (with-tree alloc
                (Rope. (ropetree/rope-insert-root
                         (ensure-tree-root root) ii
                         (ensure-tree-root ins))
                       alloc _meta))))

          :else
          (or (when (and (<= ins-n +target-chunk-size+) (pos? ins-n))
                (when-let [new-root (ropetree/rope-splice-inplace
                                      root ii ii ins alloc)]
                  (Rope. new-root alloc _meta)))
              (with-tree alloc
                (Rope. (ropetree/rope-insert-root root ii
                                                   (ensure-tree-root ins))
                       alloc _meta)))))))
  (rope-remove [this start end]
    (let [n (flat-size root)]
      (check-range! start end n)
      (let [si (long start)
            ei (long end)]
        (cond
          (= si ei) this

          (flat? root)
          (let [^clojure.lang.IPersistentVector fv root
                new-v (into (subvec fv 0 (int si)) (subvec fv (int ei) (int n)))]
            (Rope. (when (pos? (count new-v)) new-v) alloc _meta))

          :else
          (or (when-let [new-root (ropetree/rope-splice-inplace
                                    root si ei nil alloc)]
                (Rope. new-root alloc _meta))
              (with-tree alloc
                (Rope. (ropetree/rope-remove-root root si ei) alloc _meta)))))))
  (rope-splice [this start end coll]
    (let [n (flat-size root)]
      (check-range! start end n)
      (let [si (long start)
            ei (long end)
            ins (if (instance? clojure.lang.APersistentVector coll) coll (vec coll))
            ins-n (.count ^clojure.lang.Counted ins)]
        (cond
          (flat? root)
          (let [^clojure.lang.IPersistentVector fv root
                total (+ (- n (- ei si)) ins-n)]
            (if (<= total +flat-threshold+)
              (let [new-v (-> []
                              (into (subvec fv 0 (int si)))
                              (into ins)
                              (into (subvec fv (int ei) (int n))))]
                (Rope. (when (pos? (count new-v)) new-v) alloc _meta))
              (with-tree alloc
                (Rope. (ropetree/rope-splice-root
                         (ensure-tree-root root) si ei
                         (ensure-tree-root ins))
                       alloc _meta))))

          (nil? root)
          (if (zero? ins-n)
            this
            (with-tree alloc
              (Rope. (make-root ins) alloc _meta)))

          :else
          (or (when (<= ins-n +target-chunk-size+)
                (let [rep-chunk (when (pos? ins-n) ins)]
                  (when-let [new-root (ropetree/rope-splice-inplace
                                        root si ei rep-chunk alloc)]
                    (Rope. new-root alloc _meta))))
              (with-tree alloc
                (Rope. (ropetree/rope-splice-root root si ei
                                                   (ensure-tree-root ins))
                       alloc _meta)))))))
  (rope-chunks [_]
    (cond
      (nil? root)  nil
      (flat? root) (list root)
      :else        (ropetree/rope-chunks-seq root)))
  (rope-str [_]
    (cond
      (nil? root)  ""
      (flat? root)
      (let [sb (StringBuilder.)]
        (run! #(.append sb %) root)
        (.toString sb))
      :else
      (ropetree/rope->str root)))

  IEditableCollection
  (asTransient [_]
    ;; TransientRope's internal machinery (rope-size, rope-nth, etc.)
    ;; expects a tree root, so promote any flat root on the way in.
    (->TransientRope
      (with-tree alloc (ensure-tree-root root))
      alloc (ArrayList.) (ArrayList.) 0 true _meta))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))
  (toString [this]
    (pr-str this)))


(defn- ->rope
  "Coerce x to a Rope, returning x if already a Rope. Small inputs
  stay in flat form (raw vector); larger inputs build a tree."
  [x]
  (if (instance? Rope x)
    x
    (let [v (if (instance? clojure.lang.APersistentVector x) x (vec x))
          n (.count ^clojure.lang.Counted v)]
      (cond
        (zero? n)
        (Rope. nil ropetree/rope-node-create {})

        (<= n +flat-threshold+)
        (Rope. v ropetree/rope-node-create {})

        :else
        (with-tree ropetree/rope-node-create
          (Rope. (ropetree/coll->root v) ropetree/rope-node-create {}))))))

(defn- ->tree-root
  "Coerce `x` to a tree root suitable for kernel operations.
  Caller must bind tree/*t-join* (and CSI vars).
  - `nil`              → `nil`
  - `Rope` with flat   → single-node tree from the flat vector
  - `Rope` with tree   → that tree
  - anything else      → promoted via coll->root"
  [x]
  (cond
    (nil? x) nil

    (instance? Rope x)
    (let [rt (.-root ^Rope x)]
      (cond
        (nil? rt)    nil
        (flat? rt)   (if (zero? (count rt))
                       nil
                       (ropetree/chunks->root [rt]))
        :else        rt))

    :else
    (ropetree/coll->root (if (instance? clojure.lang.APersistentVector x)
                           x
                           (vec x)))))

(defn rope-concat
  "Concatenate ropes or rope-coercible collections.
   One argument: returns it as a rope.
   Two arguments: O(log n) binary tree join.
   Three or more: O(total chunks) bulk construction."
  ([x]
   (->rope x))
  ([left right]
   (proto/rope-cat (->rope left) (->rope right)))
  ([left right & more]
   (with-tree ropetree/rope-node-create
     (Rope. (ropetree/chunks->root-csi
              (into []
                    (mapcat (fn [x]
                              (let [rt (->tree-root x)]
                                (if rt
                                  (ropetree/root->chunks rt)
                                  []))))
                    (list* left right more)))
            ropetree/rope-node-create
            (or (meta left) {})))))

(defn- transient-appended-root
  "Build a rope tree from flushed chunks + tail. Caller must bind *t-join*."
  [^ArrayList chunks ^ArrayList tail]
  (let [chunk-count (.size chunks)
        tail-empty? (.isEmpty tail)]
    (cond
      (and (zero? chunk-count) tail-empty?)
      nil

      (zero? chunk-count)
      (ropetree/chunks->root [(vec tail)])

      tail-empty?
      (ropetree/chunks->root (vec chunks))

      :else
      (ropetree/chunks->root-csi
        (conj (vec chunks) (vec tail))))))

(def ^:const +transient-rebuild-threshold+
  4)

(defn- transient-final-root
  "Merge original root with appended chunks/tail. Caller must bind *t-join*."
  [root ^ArrayList chunks ^ArrayList tail]
  (cond
    ;; Fast path: nothing appended — return original root unchanged
    (and (zero? (.size chunks)) (.isEmpty tail))
    root

    ;; Fast path: small tail only, no flushed chunks — append elements
    ;; directly via rope-conj-right, avoiding subtree construction + concat
    (and (zero? (.size chunks)) (<= (.size tail) 32))
    (reduce ropetree/rope-conj-right (or root (node/leaf)) tail)

    ;; Normal path: build appended content and merge
    :else
    (let [appended-root (transient-appended-root chunks tail)
          appended-chunks (+ (.size chunks) (if (.isEmpty tail) 0 1))]
      (cond
        (nil? root)
        appended-root

        (<= appended-chunks +transient-rebuild-threshold+)
        (ropetree/rope-concat root appended-root)

        :else
        (ropetree/chunks->root-csi
          (cond-> (vec (ropetree/root->chunks root))
            (pos? (.size chunks)) (into (vec chunks))
            (not (.isEmpty tail)) (conj (vec tail))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transient Rope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A TransientRope is a narrow construction-oriented companion to the
;; persistent Rope type. Its purpose is not to provide a fully mutable rope
;; editor, but to make append-heavy assembly cheaper by buffering a mutable
;; tail chunk and only flushing that chunk into the tree when it fills or when
;; `persistent!` is called.
;;
;; This design exists because bulk building and structural editing have
;; different needs:
;;
;; - persistent Rope operations optimize sharing, split/join, and snapshots
;; - transient construction wants cheap repeated `conj` without rebuilding
;;   tree structure for every appended element
;;
;; So the transient keeps the implementation deliberately small:
;;
;; - immutable tree prefix in `root`
;; - mutable append buffer in `tail`
;; - no attempt to support arbitrary in-place splice/update semantics
;;
;; That makes it useful for workloads like:
;;
;; - building a large rope from a stream of values
;; - accumulating output incrementally before freezing a persistent result
;; - amortizing append cost without weakening the persistent rope model
;;
;; In other words, TransientRope is a builder, not a mutable general-purpose
;; rope API.

(deftype TransientRope [^:unsynchronized-mutable root
                        alloc
                        ^ArrayList chunks
                        ^ArrayList tail
                        ^:unsynchronized-mutable chunk-elems
                        ^:unsynchronized-mutable edit
                        _meta]
  ITransientCollection
  (conj [this x]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (.add tail x)
    (when (>= (.size tail) +target-chunk-size+)
      (.add chunks (vec tail))
      (set! chunk-elems (+ chunk-elems +target-chunk-size+))
      (.clear tail))
    this)

  (persistent [_]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (set! edit false)
    (with-tree alloc
      (let [tree-root  (transient-final-root root chunks tail)
            ;; Demote to flat if the result is small enough
            final-root (if (and tree-root
                                (not (flat? tree-root))
                                (<= (ropetree/rope-size tree-root) +flat-threshold+))
                         (vec (mapcat identity (ropetree/root->chunks tree-root)))
                         tree-root)]
        (Rope. final-root alloc _meta))))

  clojure.lang.Counted
  (count [_]
    (+ (ropetree/rope-size root) chunk-elems (.size tail)))

  Indexed
  (nth [this i]
    (let [rs (ropetree/rope-size root)
          ts (.size tail)
          j  (- i rs)]
      (cond
        (and (>= i 0) (< i rs))   (ropetree/rope-nth root i)
        (and (>= j 0) (< j chunk-elems))
        (let [chunk-idx (quot j +target-chunk-size+)
              offset    (rem j +target-chunk-size+)
              chunk     (.get chunks chunk-idx)]
          (.nth ^clojure.lang.Indexed chunk offset))

        (< (- j chunk-elems) ts)   (.get tail (- j chunk-elems))
        :else                      (throw (IndexOutOfBoundsException.)))))
  (nth [this i not-found]
    (let [rs (ropetree/rope-size root)
          n  (+ rs chunk-elems (.size tail))]
      (if (and (>= i 0) (< i n))
        (.nth this i)
        not-found))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rope
  "Create a persistent rope from a collection.
   Small inputs (≤ 1024 elements) are stored as a raw PersistentVector
   root with zero tree overhead. Larger inputs build the chunked tree."
  ([] (Rope. nil ropetree/rope-node-create {}))
  ([coll]
   (->rope coll)))

(defn rope-concat-all
  "Bulk concatenation of rope values or rope-coercible collections.
  Collects all chunks and builds the tree directly in O(total chunks),
  avoiding pairwise tree operations."
  [& xs]
  (with-tree ropetree/rope-node-create
    (Rope. (ropetree/chunks->root-csi
             (into []
                   (mapcat (fn [x]
                             (let [rt (->tree-root x)]
                               (if rt
                                 (ropetree/root->chunks rt)
                                 []))))
                   xs))
      ropetree/rope-node-create
      (or (meta (first xs)) {}))))

(defn rope-chunks-reverse
  "Reverse seq of internal chunk vectors."
  [v]
  (let [^Rope r (->rope v)
        root (.-root r)]
    (cond
      (nil? root)  nil
      (flat? root) (list root)
      :else        (ropetree/rope-chunks-rseq root))))

(defn rope-chunk-count
  "Number of internal chunks. O(1)."
  [v]
  (let [^Rope r (->rope v)
        root (.-root r)]
    (cond
      (nil? root)  0
      (flat? root) 1
      :else        (ropetree/chunk-count root))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method Rope [^Rope r ^java.io.Writer w]
  (.write w "#vec/rope ")
  (print-method (into [] r) w))
