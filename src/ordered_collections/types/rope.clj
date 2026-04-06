(ns ordered-collections.types.rope
  "Persistent rope-like indexed sequence backed by an implicit-index
  weight-balanced tree."
  (:require [clojure.core.protocols :as cp]
            [clojure.core.reducers :as r]
            [ordered-collections.parallel :as par]
            [ordered-collections.protocol :as proto]
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

(defn- assoc-index?
  [^long n k]
  (and (integer? k)
       (let [i (long k)]
         (and (<= 0 i) (<= i n)))))

(defn- check-range!
  "Validate [start, end) against rope of size n."
  [^long start ^long end ^long n]
  (when (or (neg? start) (neg? end) (> start end) (> end n))
    (throw (IndexOutOfBoundsException.))))

(declare ->rope)

(defn- rope-equiv
  "Vector-style equality. If both are IPersistentVector, compare by index.
   Otherwise fall back to sequential element-wise comparison."
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
  "Lexicographic comparison of two sequential collections."
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
        (not (assoc-index? n k))
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
    (let [sz (ropetree/rope-size root)]
      (if (<= sz (long n))
        (.reduce ^IReduceInit this reducef (combinef))
        (letfn [(fold* [^Rope child]
                  (let [csz (count child)]
                    (if (<= csz (long n))
                      (.reduce ^IReduceInit child reducef (combinef))
                      (let [cmid   (quot csz 2)
                            [cl cr] (proto/rope-split child cmid)]
                        (par/fork-join
                          [lv (fold* cl) rv (fold* cr)]
                          (combinef lv rv))))))]
          (if (par/in-fork-join-pool?)
            (fold* this)
            (par/invoke-root #(fold* this)))))))

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
  (rope-concat [this other]
    (let [other-root (if (instance? Rope other)
                       (.-root ^Rope other)
                       (ropetree/coll->root other))]
      (Rope. (ropetree/rope-concat root other-root) _meta)))
  (rope-split [_ i]
    (let [[l r] (ropetree/ensure-split-parts
                  (ropetree/rope-split-at root (long i)))]
      [(Rope. l _meta) (Rope. r _meta)]))
  (rope-sub [_ start end]
    (let [n (ropetree/rope-size root)]
      (check-range! start end n)
      (Rope. (ropetree/rope-subvec-root root start end) _meta)))
  (rope-insert [this i coll]
    (let [n (ropetree/rope-size root)]
      (when (or (neg? i) (> i n))
        (throw (IndexOutOfBoundsException.)))
      (let [[l r] (proto/rope-split this i)
            mid   (->rope coll)]
        (proto/rope-concat (proto/rope-concat l mid) r))))
  (rope-remove [this start end]
    (check-range! start end (ropetree/rope-size root))
    (let [[l r]  (proto/rope-split this start)
          [_ rr] (proto/rope-split r (- end start))]
      (proto/rope-concat l rr)))
  (rope-splice [this start end coll]
    (check-range! start end (ropetree/rope-size root))
    (let [[l r]  (proto/rope-split this start)
          [_ rr] (proto/rope-split r (- end start))
          mid    (->rope coll)]
      (proto/rope-concat (proto/rope-concat l mid) rr)))
  (rope-chunks [_]
    (ropetree/rope-chunks-seq root))
  (rope-str [_]
    (ropetree/rope->str root))

  IEditableCollection
  (asTransient [_]
    (->TransientRope root (ArrayList.) true _meta))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transient Rope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype TransientRope [^:unsynchronized-mutable root
                        ^ArrayList tail
                        ^:unsynchronized-mutable edit
                        _meta]
  ITransientCollection
  (conj [this x]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (.add tail x)
    (when (>= (.size tail) ropetree/+target-chunk-size+)
      (let [chunk (vec tail)
            cnode (ropetree/chunks->root [chunk])]
        (.clear tail)
        (set! root (if (nil? root)
                     cnode
                     (ropetree/rope-concat root cnode)))))
    this)

  (persistent [_]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (set! edit false)
    (let [final-root (if (.isEmpty tail)
                       root
                       (let [cnode (ropetree/chunks->root [(vec tail)])]
                         (.clear tail)
                         (if (nil? root)
                           cnode
                           (ropetree/rope-concat root cnode))))]
      (Rope. final-root _meta)))

  clojure.lang.Counted
  (count [_]
    (+ (ropetree/rope-size root) (.size tail)))

  Indexed
  (nth [this i]
    (let [rs (ropetree/rope-size root)
          ts (.size tail)]
      (cond
        (and (>= i 0) (< i rs))   (ropetree/rope-nth root i)
        (< (- i rs) ts)            (.get tail (- i rs))
        :else                      (throw (IndexOutOfBoundsException.)))))
  (nth [this i not-found]
    (let [rs (ropetree/rope-size root)
          ts (.size tail)
          n  (+ rs ts)]
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
    {}))

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
