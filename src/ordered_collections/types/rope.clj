(ns ordered-collections.types.rope
  "Persistent rope-like indexed sequence backed by an implicit-index
  weight-balanced tree."
  (:require [clojure.core.protocols :as cp]
            [clojure.core.reducers :as r]
            [ordered-collections.protocol :as proto]
            [ordered-collections.kernel.node :as node]
            [ordered-collections.kernel.rope :as ropetree])
  (:import  [clojure.lang RT Murmur3 MapEntry Indexed Util
                           IPersistentCollection IPersistentStack IPersistentVector
                           IEditableCollection ITransientCollection
                           IReduce IReduceInit SeqIterator]
            [java.util ArrayList]))


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
  (let [n      (ropetree/rope-size root)
        result (ropetree/rope-reduce
                 (fn [^long i elem]
                   (if (Util/equiv elem x)
                     (reduced i)
                     (unchecked-inc i)))
                 (long 0)
                 root)]
    (if (< (long result) n) (long result) -1)))

(defn- linear-last-index-of
  "Forward linear scan tracking the last matching index. O(n)."
  [root x]
  (let [found (volatile! (long -1))]
    (ropetree/rope-reduce
      (fn [^long i elem]
        (when (Util/equiv elem x) (vreset! found i))
        (unchecked-inc i))
      (long 0)
      root)
    (long @found)))

(defn- rope-to-array
  ^objects [root]
  (let [n   (ropetree/rope-size root)
        arr (object-array n)]
    (ropetree/rope-reduce
      (fn [^long i x]
        (aset arr i x)
        (unchecked-inc i))
      (long 0)
      root)
    arr))

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

(deftype Rope [root _meta]

  java.io.Serializable
  java.util.RandomAccess

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m] (Rope. root m))

  clojure.lang.Counted
  (count [_]
    (ropetree/rope-size root))

  Indexed
  (nth [_ i]
    (if (valid-index? (ropetree/rope-size root) i)
      (ropetree/rope-nth root (long i))
      (throw (IndexOutOfBoundsException.))))
  (nth [_ i not-found]
    (if (valid-index? (ropetree/rope-size root) i)
      (ropetree/rope-nth root (long i))
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
    (valid-index? (ropetree/rope-size root) k))
  (entryAt [this k]
    (when (.containsKey this k)
      (MapEntry. k (.nth this k))))
  (assoc [this k v]
    (let [n (ropetree/rope-size root)]
      (cond
        (not (insert-index? n k))
        (throw (IndexOutOfBoundsException.))

        (= (long k) n)
        (Rope. (ropetree/rope-conj-right root v) _meta)

        :else
        (Rope. (ropetree/rope-assoc root (long k) v) _meta))))

  IPersistentVector
  (assocN [this i v]
    (.assoc this i v))
  (length [_]
    (ropetree/rope-size root))

  IPersistentCollection
  (cons [_ o]
    (Rope. (ropetree/rope-conj-right root o) _meta))
  (empty [_]
    (Rope. nil _meta))
  (equiv [this o]
    (rope-equiv this o))

  IPersistentStack
  (peek [_]
    (ropetree/rope-peek-right root))
  (pop [_]
    (Rope. (ropetree/rope-pop-right root) _meta))

  clojure.lang.Seqable
  (seq [_]
    (ropetree/rope-seq root))

  clojure.lang.Reversible
  (rseq [_]
    (ropetree/rope-rseq root))

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
    (ropetree/rope-reduce f init root))

  IReduce
  (reduce [_ f]
    (ropetree/rope-reduce f root))

  cp/CollReduce
  (coll-reduce [this f]
    (.reduce ^IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^IReduceInit this f init))

  r/CollFold
  (coll-fold [this n combinef reducef]
    (ropetree/rope-fold root (long n) combinef reducef))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  java.util.Collection
  (toArray [_]
    (rope-to-array root))
  (isEmpty [_]
    (nil? root))
  (^boolean contains [_ x]
    (not (neg? (linear-index-of root x))))
  (containsAll [this c]
    (every? #(.contains this %) c))
  (size [_]
    (ropetree/rope-size root))
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
  (get [_ i]
    (if (valid-index? (ropetree/rope-size root) i)
      (ropetree/rope-nth root (long i))
      (throw (IndexOutOfBoundsException.))))
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
    (Rope. (ropetree/rope-concat root (.-root ^Rope other)) _meta))
  (rope-split [_ i]
    (check-insert-index! (ropetree/rope-size root) i)
    (let [[l r] (ropetree/ensure-split-parts
                  (ropetree/rope-split-at root (long i)))]
      [(Rope. l _meta) (Rope. r _meta)]))
  (rope-sub [_ start end]
    (let [n (ropetree/rope-size root)]
      (check-range! start end n)
      (Rope. (ropetree/rope-subvec-root root (long start) (long end)) _meta)))
  (rope-insert [this i coll]
    (let [n (ropetree/rope-size root)]
      (check-insert-index! n i)
      (let [mid (->rope coll)]
        (Rope. (ropetree/rope-insert-root root (long i) (.-root ^Rope mid)) _meta))))
  (rope-remove [this start end]
    (check-range! start end (ropetree/rope-size root))
    (let [start (long start)
          end   (long end)]
      (Rope. (ropetree/rope-remove-root root start end) _meta)))
  (rope-splice [this start end coll]
    (check-range! start end (ropetree/rope-size root))
    (let [start (long start)
          end   (long end)
          mid   (->rope coll)]
      (Rope. (ropetree/rope-splice-root root start end (.-root ^Rope mid)) _meta)))
  (rope-chunks [_]
    (ropetree/rope-chunks-seq root))
  (rope-str [_]
    (ropetree/rope->str root))

  IEditableCollection
  (asTransient [_]
    (->TransientRope root (ArrayList.) (ArrayList.) 0 true _meta))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))
  (toString [this]
    (pr-str this)))


(defn- ->rope
  "Coerce x to a Rope, returning x if already a Rope."
  [x]
  (if (instance? Rope x)
    x
    (Rope. (ropetree/coll->root x) {})))

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
   (Rope. (ropetree/chunks->root-csi
            (into [] (mapcat (comp ropetree/root->chunks rope-root))
                  (list* left right more)))
     (or (meta left) {}))))

(defn- transient-appended-root
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
                        ^ArrayList chunks
                        ^ArrayList tail
                        ^:unsynchronized-mutable chunk-elems
                        ^:unsynchronized-mutable edit
                        _meta]
  ITransientCollection
  (conj [this x]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (.add tail x)
    (when (>= (.size tail) ropetree/+target-chunk-size+)
      (.add chunks (vec tail))
      (set! chunk-elems (+ chunk-elems ropetree/+target-chunk-size+))
      (.clear tail))
    this)

  (persistent [_]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (set! edit false)
    (Rope. (transient-final-root root chunks tail) _meta))

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
        (let [chunk-idx (quot j ropetree/+target-chunk-size+)
              offset    (rem j ropetree/+target-chunk-size+)
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
  ([] (Rope. nil {}))
  ([coll]
   (Rope. (ropetree/coll->root coll) {})))

(defn- rope-root
  [x]
  (if (instance? Rope x)
    (.-root ^Rope x)
    (ropetree/coll->root x)))

(defn rope-concat-all
  "Bulk concatenation of rope values or rope-coercible collections.
  Collects all chunks and builds the tree directly in O(total chunks),
  avoiding pairwise tree operations."
  [& xs]
  (Rope. (ropetree/chunks->root-csi
           (into [] (mapcat (comp ropetree/root->chunks rope-root)) xs))
    (or (meta (first xs)) {})))

(defn rope-chunks-reverse
  "Reverse seq of internal chunk vectors."
  [v]
  (ropetree/rope-chunks-rseq (rope-root v)))

(defn rope-chunk-count
  "Number of internal chunks. O(1)."
  [v]
  (let [root (rope-root v)]
    (if (nil? root) 0 (ropetree/chunk-count root))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method Rope [^Rope r ^java.io.Writer w]
  (.write w "#ordered/rope ")
  (print-method (into [] r) w))
